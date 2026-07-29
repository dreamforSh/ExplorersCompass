package com.chaosthedude.explorerscompass.client;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.chaosthedude.explorerscompass.gui.GuiTheme;
import com.chaosthedude.explorerscompass.items.ExplorersCompassItem;
import com.chaosthedude.explorerscompass.util.CompassState;
import com.chaosthedude.explorerscompass.util.RenderUtils;
import com.chaosthedude.explorerscompass.util.SearchTarget;
import com.chaosthedude.explorerscompass.util.StructureUtils;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * The lines the compass carries in its own tooltip.
 *
 * <p>Everything it names has to be named in the player's own language, and those names only resolve
 * on this side, which is why this is not part of the item itself. It reports the same things the HUD
 * does in the same colours, since the tooltip is what stands in for the HUD whenever a compass is
 * being looked at rather than held.
 *
 * <p>What it says at a glance is kept to the state and whatever follows from it. Everything else is
 * a held shift key away, so that a chest full of compasses does not become a wall of text.
 */
@OnlyIn(Dist.CLIENT)
public class CompassTooltip {

	// The same mark the HUD panel and the status strip put in front of the state, so that all three
	// read as one readout rather than three
	private static final String DOT_GLYPH = "●";

	// How many of a multi-target search are named before the tooltip only says how many there are.
	// This is also what bounds how much of the stored selection is read back: a compass can carry
	// hundreds of keys, and a tooltip is built again for every frame the pointer rests on the stack.
	private static final int MAX_TARGETS_LISTED = 8;

	/** How near the located place counts as having arrived at it, as the HUD reckons it. */
	private static final int ARRIVED_DISTANCE = 32;

	// A name long enough to push the tooltip off the screen is cut down. A name stays recognisable
	// shortened, where the coordinates under it would not, so only the name is ever cut.
	private static final int MAX_NAME_WIDTH = 160;

	public static void appendHoverText(ExplorersCompassItem compass, ItemStack stack, List<Component> tooltip, TooltipFlag flag) {
		final TooltipDetail detail = ConfigHandler.CLIENT.tooltipDetail.get();
		if (detail == TooltipDetail.NONE) {
			return;
		}

		final CompassState state = compass.getState(stack);
		if (state == null || state == CompassState.INACTIVE) {
			// A compass that has not been pointed at anything has nothing else to report, and nothing
			// else anywhere says that right clicking it is what starts it off
			tooltip.add(hint("string.explorerscompass.tooltip.usage.open"));
			return;
		}

		// Collected apart from the lines that always show, so that whether to show them at all is
		// decided once, after every state has had its say about what belongs in there
		final List<Component> details = new ArrayList<Component>();
		final Player player = Minecraft.getInstance().player;
		if (state == CompassState.SEARCHING) {
			appendSearching(compass, stack, tooltip, details);
		} else if (state == CompassState.FOUND) {
			appendFound(compass, stack, player, tooltip, details);
		} else {
			appendNotFound(compass, stack, tooltip, details);
		}
		appendGroupAndSource(compass, stack, state, details);
		appendLocated(compass, stack, details);
		details.add(hint("string.explorerscompass.tooltip.usage.cancel"));

		if (detail == TooltipDetail.FULL || Screen.hasShiftDown()) {
			tooltip.addAll(details);
		} else {
			tooltip.add(Component.translatable("string.explorerscompass.tooltip.holdShift").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
		}

		if (flag.isAdvanced()) {
			appendKeys(compass, stack, state, tooltip);
		}
	}

	private static void appendSearching(ExplorersCompassItem compass, ItemStack stack, List<Component> tooltip, List<Component> details) {
		tooltip.add(headline("string.explorerscompass.searching", ClientEventHandler.searchTargetName(compass, stack), GuiTheme.ACCENT));

		// How far it has looked is the only thing that changes while a search runs, and the needle says
		// nothing at all until there is something for it to point at
		final int radius = compass.getSearchRadius(stack);
		if (radius >= 0) {
			final int maxRadius = ConfigHandler.GENERAL.maxRadius.get();
			final String reached = radius <= maxRadius && maxRadius > 0
					? String.format("%,d", radius) + " / " + String.format("%,d", maxRadius)
					: String.format("%,d", radius);
			tooltip.add(labeled("string.explorerscompass.radius", value(reached, GuiTheme.TEXT_SECONDARY)));
		}

		appendTargets(compass, stack, details);
	}

	private static void appendFound(ExplorersCompassItem compass, ItemStack stack, @Nullable Player player, List<Component> tooltip, List<Component> details) {
		final int x = compass.getFoundStructureX(stack);
		final int y = compass.getFoundStructureY(stack);
		final int z = compass.getFoundStructureZ(stack);
		final String name = compass.getSearchTarget(stack).getPrettyName(compass.getTargetKey(stack));
		// A compass from before the dimension was recorded cannot say it is anywhere else, and neither
		// can one whose tooltip is being built with no player behind it
		final ResourceLocation foundDimension = compass.getFoundDimension(stack);
		final boolean here = foundDimension == null || player == null || foundDimension.equals(player.level.dimension().location());
		final boolean coordinates = compass.shouldDisplayCoordinates(stack);

		tooltip.add(headline("string.explorerscompass.found", name, here ? GuiTheme.TEXT_SUCCESS : GuiTheme.TEXT_WARNING));
		if (!here) {
			tooltip.add(labeled("string.explorerscompass.dimension", value(StructureUtils.getDimensionName(foundDimension), GuiTheme.TEXT_WARNING)));
			details.add(hint("string.explorerscompass.wrongDimension"));
			if (coordinates) {
				// Worth noting down for whenever the player is over there, but not worth a line beside a
				// distance that would mean nothing from where they are standing
				details.add(labeled("string.explorerscompass.coordinates", value(StructureUtils.formatCoordinates(x, y, z), GuiTheme.TEXT_MUTED)));
			}
		} else {
			if (coordinates) {
				tooltip.add(labeled("string.explorerscompass.coordinates", value(StructureUtils.formatCoordinates(x, y, z), GuiTheme.TEXT_PRIMARY)));
				// Where the location is stands on its own, but how far away it is and which way it lies
				// are both measured from the player, so they need one to be there
				if (player != null) {
					final int distance = StructureUtils.getHorizontalDistanceToLocation(player, x, z);
					tooltip.add(labeled("string.explorerscompass.distance", value(String.format("%,d", distance) + " (" + ClientEventHandler.compassDirection(player, x, z) + ")",
							distance <= ARRIVED_DISTANCE ? GuiTheme.TEXT_SUCCESS : GuiTheme.TEXT_PRIMARY)));
				}
			}
			// Which dimension it is in only earns a line of its own once it is not the warning above it
			if (foundDimension != null) {
				details.add(labeled("string.explorerscompass.dimension", value(StructureUtils.getDimensionName(foundDimension), GuiTheme.TEXT_SECONDARY)));
			}
		}

		// The location being pointed at is part of the collected list, but is not a previous one
		final int previousLocations = compass.getPrevPosCount(stack) - 1;
		if (previousLocations > 0) {
			details.add(labeled("string.explorerscompass.previousLocations", value(String.valueOf(previousLocations), GuiTheme.TEXT_SECONDARY)));
		}
	}

	private static void appendNotFound(ExplorersCompassItem compass, ItemStack stack, List<Component> tooltip, List<Component> details) {
		tooltip.add(headline("string.explorerscompass.notFound", compass.getSearchTarget(stack).getPrettyName(compass.getTargetKey(stack)), GuiTheme.TEXT_WARNING));

		// How far it looked before giving up is what says whether searching again from somewhere else
		// is worth trying, so it belongs here rather than a shift away
		final int radius = compass.getSearchRadius(stack);
		if (radius >= 0) {
			tooltip.add(labeled("string.explorerscompass.radius", value(String.format("%,d", radius), GuiTheme.TEXT_SECONDARY)));
		}
		final int samples = compass.getSamples(stack);
		if (samples >= 0) {
			details.add(labeled("string.explorerscompass.samples", value(String.format("%,d", samples), GuiTheme.TEXT_SECONDARY)));
		}
		details.add(hint("string.explorerscompass.tooltip.notFoundHint"));
	}

	/**
	 * Names the several targets a search was asked for at once. The count is what decides this, so
	 * that a large selection is never read back only for the headline's own "(+N)" to summarise it.
	 */
	private static void appendTargets(ExplorersCompassItem compass, ItemStack stack, List<Component> details) {
		final int targetCount = compass.getTargetCount(stack);
		if (targetCount <= 1 || targetCount > MAX_TARGETS_LISTED) {
			return;
		}

		final SearchTarget searchTarget = compass.getSearchTarget(stack);
		final List<String> names = new ArrayList<String>();
		for (ResourceLocation key : compass.getTargetKeys(stack)) {
			names.add(searchTarget.getPrettyName(key));
		}
		if (!names.isEmpty()) {
			details.add(labeled("string.explorerscompass.targets", value(String.join(", ", names), GuiTheme.TEXT_SECONDARY)));
		}
	}

	/** Where the target came from and what it is grouped with, as the selection screen also lists it. */
	private static void appendGroupAndSource(ExplorersCompassItem compass, ItemStack stack, CompassState state, List<Component> details) {
		final ResourceLocation targetKey = compass.getTargetKey(stack);
		if (targetKey.getPath().isEmpty()) {
			return;
		}
		// While a search for several runs, the key held here is only the first of them, and a group or a
		// mod named after that one alone would read as if it covered the rest. What was asked for is
		// already spelled out by the targets line. Once one of them is located the key is that one's,
		// and naming its group and its mod says something true again.
		if (state == CompassState.SEARCHING && !compass.getIsGroup(stack) && compass.getTargetCount(stack) > 1) {
			return;
		}

		final SearchTarget searchTarget = compass.getSearchTarget(stack);
		// A group search already holds the group's own key. Otherwise the group has to be looked up in
		// what the server synced, and a key it has not sent yet cannot be grouped at all — saying
		// nothing about it reads better than naming the wrong group.
		final ResourceLocation groupKey = compass.getIsGroup(stack) ? targetKey : searchTarget.getGroupKey(targetKey);
		if (groupKey != null) {
			details.add(labeled("string.explorerscompass.group", value(searchTarget.getPrettyGroupName(groupKey), GuiTheme.TEXT_SECONDARY)));
		}
		details.add(labeled("string.explorerscompass.source", value(StructureUtils.getPrettySourceName(targetKey), GuiTheme.TEXT_SECONDARY)));
	}

	/** How many places this compass is holding on to, so that the screen listing them is worth opening. */
	private static void appendLocated(ExplorersCompassItem compass, ItemStack stack, List<Component> details) {
		final int bookmarks = compass.getBookmarkCount(stack);
		if (bookmarks > 0) {
			details.add(labeled("string.explorerscompass.bookmarks", value(String.valueOf(bookmarks), GuiTheme.TEXT_SECONDARY)));
		}
	}

	/**
	 * The registry keys behind the names, for whoever is putting a pack together rather than playing.
	 * An advanced tooltip already means "name the registry entries", so this does not also wait for a
	 * held shift key.
	 */
	private static void appendKeys(ExplorersCompassItem compass, ItemStack stack, CompassState state, List<Component> tooltip) {
		final ResourceLocation targetKey = compass.getTargetKey(stack);
		if (!targetKey.getPath().isEmpty()) {
			tooltip.add(Component.literal(targetKey.toString()).withStyle(ChatFormatting.DARK_GRAY));
		}
		final ResourceLocation foundDimension = compass.getFoundDimension(stack);
		if (state == CompassState.FOUND && foundDimension != null) {
			tooltip.add(Component.literal(foundDimension.toString()).withStyle(ChatFormatting.DARK_GRAY));
		}
	}

	/** The line saying what the compass is doing, marked with the dot the other readouts mark it with. */
	private static Component headline(String stateKey, String name, int color) {
		final Component state = Component.translatable("string.explorerscompass.labeledValue",
				Component.translatable(stateKey), value(RenderUtils.trimToWidth(name, MAX_NAME_WIDTH), color))
				.withStyle((style) -> style.withColor(TextColor.fromRgb(GuiTheme.TEXT_PRIMARY)));
		return Component.literal(DOT_GLYPH + " ").withStyle((style) -> style.withColor(TextColor.fromRgb(color))).append(state);
	}

	/**
	 * A labelled value. What stands between the label and the value is a translation of its own rather
	 * than a colon written into the code, since neither that punctuation nor the spacing around it is
	 * the same in every language. The line is muted throughout and the value carries its own colour on
	 * top of that, which is how one component ends up reading as a quiet label and a bright value.
	 */
	private static Component labeled(String labelKey, Component value) {
		return Component.translatable("string.explorerscompass.labeledValue", Component.translatable(labelKey), value)
				.withStyle((style) -> style.withColor(TextColor.fromRgb(GuiTheme.TEXT_MUTED)));
	}

	private static Component value(String text, int color) {
		return Component.literal(text).withStyle((style) -> style.withColor(TextColor.fromRgb(color)));
	}

	/** A whole sentence rather than a labelled value, held back to the colour of a label. */
	private static Component hint(String key) {
		return Component.translatable(key).withStyle((style) -> style.withColor(TextColor.fromRgb(GuiTheme.TEXT_MUTED)));
	}

}
