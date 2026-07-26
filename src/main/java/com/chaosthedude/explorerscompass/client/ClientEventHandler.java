package com.chaosthedude.explorerscompass.client;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.chaosthedude.explorerscompass.gui.GuiTheme;
import com.chaosthedude.explorerscompass.items.ExplorersCompassItem;
import com.chaosthedude.explorerscompass.util.CompassState;
import com.chaosthedude.explorerscompass.util.ItemUtils;
import com.chaosthedude.explorerscompass.util.RenderUtils;
import com.chaosthedude.explorerscompass.util.StructureUtils;
import com.mojang.blaze3d.vertex.PoseStack;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.TickEvent.Phase;
import net.minecraftforge.event.TickEvent.RenderTickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

@OnlyIn(Dist.CLIENT)
public class ClientEventHandler {

	private static final int MAX_PREVIOUS_LOCATIONS_SHOWN = 2;

	private static final String[] WIND_POINTS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

	private static final String DOT_GLYPH = "●";

	// The panel of compass information, laid out as a headline and a column of labelled values
	private static final int HUD_MARGIN = 5;
	private static final int HUD_PADDING = 5;
	private static final int HUD_ROW_HEIGHT = 10;
	private static final int HUD_LABEL_GAP = 8;
	private static final int HUD_PROGRESS_HEIGHT = 3;
	private static final int HUD_PROGRESS_BACKGROUND = 0x60000000;

	private static final int DIRECTION_BAR_HEIGHT = 14;
	// Marks are drawn this many degrees apart, with the ones at the eight wind points drawn taller
	private static final int DIRECTION_BAR_TICK_DEGREES = 15;
	// How far the strip fades out towards each end, so that marks appear and leave gradually
	private static final int DIRECTION_BAR_FADE_WIDTH = 24;
	// How near the middle of the strip the target has to sit to count as being faced
	private static final double ON_TARGET_DEGREES = 3.0D;
	// How near the located structure counts as having arrived at it
	private static final int ARRIVED_DISTANCE = 32;
	// How long the panel picks itself out after a search finishes
	private static final long ANNOUNCE_MILLIS = 2500L;

	// Filled shapes take their alpha from the top byte, where text has it added for it
	private static final int MARKER_COLOR = 0xFF000000 | GuiTheme.ACCENT;
	private static final int ON_TARGET_COLOR = 0xFF000000 | GuiTheme.TEXT_SUCCESS;
	private static final int BAR_BORDER_COLOR = 0xB0000000;
	private static final int BAR_BACKGROUND_COLOR = 0x90101010;
	private static final int BAR_FADE_COLOR = 0xD0101010;
	private static final int BAR_FADE_CLEAR_COLOR = 0x00101010;
	private static final int BAR_CENTER_COLOR = 0x60FFFFFF;
	private static final int BAR_MAJOR_TICK_COLOR = 0xC0FFFFFF;
	private static final int BAR_MINOR_TICK_COLOR = 0x60FFFFFF;
	private static final int NORTH_LABEL_COLOR = 0xFF6060;
	private static final int CARDINAL_LABEL_COLOR = 0xFFFFFF;
	private static final int INTERCARDINAL_LABEL_COLOR = 0xA0A0A0;

	private static final Minecraft mc = Minecraft.getInstance();

	private CompoundTag lastPrevPosTag;
	private List<BlockPos> cachedPrevPos = List.of();
	// A search finishing is the one thing the player is not watching the panel for, so the panel says
	// so by picking itself out for a moment afterwards
	private CompassState lastState;
	private long announceUntil;

	/** One labelled value in the panel of compass information. */
	private static class HudRow {

		private final String label;
		private final String value;
		private final int color;

		private HudRow(String label, String value, int color) {
			this.label = label;
			this.value = value;
			this.color = color;
		}

	}

	@SubscribeEvent
	public void onRenderTick(RenderTickEvent event) {
		if (event.phase != Phase.END || mc.player == null || mc.level == null || mc.options.hideGui || mc.options.renderDebug) {
			return;
		}
		if (mc.screen != null && !(ConfigHandler.CLIENT.displayWithChatOpen.get() && mc.screen instanceof ChatScreen)) {
			return;
		}

		final Player player = mc.player;
		final ItemStack stack = ItemUtils.getHeldItem(player, ExplorersCompass.explorersCompass);
		if (stack == null || !(stack.getItem() instanceof ExplorersCompassItem)) {
			return;
		}

		final ExplorersCompassItem compass = (ExplorersCompassItem) stack.getItem();
		final CompassState state = compass.getState(stack);
		if (state != lastState) {
			// Only a search ending is worth announcing: putting the compass away and taking it out again
			// runs through the same states without anything having happened
			if (lastState == CompassState.SEARCHING && (state == CompassState.FOUND || state == CompassState.NOT_FOUND)) {
				announceUntil = Util.getMillis() + ANNOUNCE_MILLIS;
			}
			lastState = state;
		}
		if (state == null || state == CompassState.INACTIVE) {
			return;
		}

		final PoseStack poseStack = new PoseStack();
		if (state == CompassState.SEARCHING) {
			// Let the next result create a waypoint, even if it is the same location as the last one
			XaeroMinimapIntegration.reset();
		} else if (state == CompassState.FOUND && isInFoundDimension(player, compass, stack)) {
			// A waypoint in another dimension's coordinates would point at the wrong place
			XaeroMinimapIntegration.createWaypointForLocatedStructure(player, compass, stack);
			if (ConfigHandler.CLIENT.showDirectionBar.get()) {
				renderDirectionBar(poseStack, player, compass, stack);
			}
		}

		renderInfoPanel(poseStack, player, compass, stack, state);
	}

	/**
	 * Draws what the compass is doing as a panel of its own: a headline saying which state it is in
	 * and what it is aimed at, then a column of labelled values under it. Reading a value takes
	 * finding its label on the same line, rather than counting lines between two columns of text.
	 */
	private void renderInfoPanel(PoseStack poseStack, Player player, ExplorersCompassItem compass, ItemStack stack, CompassState state) {
		final List<HudRow> rows = new ArrayList<HudRow>();
		final String headline;
		final int dotColor;
		String target;
		float progress = -1.0F;

		if (state == CompassState.SEARCHING) {
			headline = I18n.get("string.explorerscompass.searching");
			// Fading the marker in and out is what says the search is still running, on a readout where
			// only the radius changes and only every so often
			final float pulse = (Mth.sin(Util.getMillis() / 200.0F) + 1.0F) / 2.0F;
			dotColor = (GuiTheme.ACCENT & 0xFFFFFF) | ((int) (110 + 145 * pulse) << 24);
			target = searchTargetName(compass, stack);

			final int radius = compass.getSearchRadius(stack);
			final int maxRadius = ConfigHandler.GENERAL.maxRadius.get();
			// Only claim to be a fraction of the whole while the search is still inside the radius it is
			// allowed to reach: the search for a structure placed in rings can pass it
			rows.add(new HudRow(I18n.get("string.explorerscompass.radius"), radius <= maxRadius && maxRadius > 0 ? String.format("%,d", radius) + " / " + String.format("%,d", maxRadius) : String.format("%,d", radius), GuiTheme.TEXT_PRIMARY));
			if (maxRadius > 0) {
				progress = (float) radius / maxRadius;
			}
		} else if (state == CompassState.FOUND) {
			headline = I18n.get("string.explorerscompass.found");
			target = StructureUtils.getPrettyStructureName(compass.getStructureKey(stack));
			final boolean here = isInFoundDimension(player, compass, stack);
			dotColor = here ? GuiTheme.TEXT_SUCCESS : GuiTheme.TEXT_WARNING;

			if (!here) {
				// The coordinates belong to another dimension, where the distance to them means nothing,
				// so which dimension they are in takes their place
				rows.add(new HudRow(I18n.get("string.explorerscompass.dimension"), StructureUtils.getDimensionName(compass.getFoundDimension(stack)), GuiTheme.TEXT_WARNING));
			} else if (compass.shouldDisplayCoordinates(stack)) {
				final int x = compass.getFoundStructureX(stack);
				final int z = compass.getFoundStructureZ(stack);
				final int y = compass.getFoundStructureY(stack);
				rows.add(new HudRow(I18n.get("string.explorerscompass.coordinates"), y != ExplorersCompassItem.UNKNOWN_Y ? x + ", " + y + ", " + z : x + ", " + z, GuiTheme.TEXT_PRIMARY));
				// Standing on the structure, the pointer spins and the direction it gives is worthless,
				// so the distance is what has to say that there is nothing left to walk
				final int distance = StructureUtils.getHorizontalDistanceToLocation(player, x, z);
				rows.add(new HudRow(I18n.get("string.explorerscompass.distance"), String.format("%,d", distance) + " (" + compassDirection(player, x, z) + ")", distance <= ARRIVED_DISTANCE ? GuiTheme.TEXT_SUCCESS : GuiTheme.TEXT_PRIMARY));

				final String previous = previousLocations(compass, stack);
				if (!previous.isEmpty()) {
					rows.add(new HudRow(I18n.get("string.explorerscompass.previousLocations"), previous, GuiTheme.TEXT_SECONDARY));
				}
			}
		} else {
			headline = I18n.get("string.explorerscompass.notFound");
			dotColor = GuiTheme.TEXT_WARNING;
			target = StructureUtils.getPrettyStructureName(compass.getStructureKey(stack));
			rows.add(new HudRow(I18n.get("string.explorerscompass.radius"), String.format("%,d", compass.getSearchRadius(stack)), GuiTheme.TEXT_PRIMARY));
			// A search can also end because it ran out of samples, in which case the radius alone is far
			// below the configured maximum and looks like a premature stop
			rows.add(new HudRow(I18n.get("string.explorerscompass.samples"), String.format("%,d", compass.getSamples(stack)), GuiTheme.TEXT_PRIMARY));
		}

		int labelWidth = 0;
		int valueWidth = 0;
		for (HudRow row : rows) {
			labelWidth = Math.max(labelWidth, mc.font.width(row.label));
			valueWidth = Math.max(valueWidth, mc.font.width(row.value));
		}

		// A structure name can be arbitrarily long, and the panel is not allowed to grow across the
		// screen with it. The values are given more room than the headline, since a name can be cut
		// short and still be recognised where a coordinate cannot.
		final int screenWidth = mc.getWindow().getGuiScaledWidth();
		final int maxHeadlineWidth = Math.max(80, screenWidth / 3);
		final int maxContentWidth = Math.max(120, screenWidth / 2);
		final int headlineFixedWidth = mc.font.width(DOT_GLYPH) + 4 + mc.font.width(headline) + 5;
		target = RenderUtils.trimToWidth(target, maxHeadlineWidth - headlineFixedWidth);

		final int contentWidth = Math.min(maxContentWidth, Math.max(headlineFixedWidth + mc.font.width(target), labelWidth + HUD_LABEL_GAP + valueWidth));
		final int contentHeight = HUD_ROW_HEIGHT + rows.size() * HUD_ROW_HEIGHT + (progress >= 0.0F ? HUD_PROGRESS_HEIGHT + 2 : 0);
		final int panelLeft = ConfigHandler.CLIENT.overlaySide.get() == OverlaySide.LEFT ? HUD_MARGIN : screenWidth - HUD_MARGIN - contentWidth - HUD_PADDING * 2;
		final int panelTop = HUD_MARGIN + ConfigHandler.CLIENT.overlayLineOffset.get() * 9;

		if (ConfigHandler.CLIENT.overlayBackground.get()) {
			// Just after a search ends the border takes the colour of the outcome and pulses, which is
			// what catches the eye of a player who was busy doing something else while it ran
			int borderColor = GuiTheme.PANEL_BORDER;
			if (Util.getMillis() < announceUntil) {
				final float pulse = (Mth.sin(Util.getMillis() / 150.0F) + 1.0F) / 2.0F;
				borderColor = (dotColor & 0xFFFFFF) | ((int) (90 + 165 * pulse) << 24);
			}
			RenderUtils.drawPanel(panelLeft, panelTop, panelLeft + contentWidth + HUD_PADDING * 2, panelTop + contentHeight + HUD_PADDING * 2, GuiTheme.PANEL_TOP, GuiTheme.PANEL_BOTTOM, borderColor);
		}

		final int textLeft = panelLeft + HUD_PADDING;
		int y = panelTop + HUD_PADDING;
		mc.font.drawShadow(poseStack, DOT_GLYPH, textLeft, y, dotColor);
		mc.font.drawShadow(poseStack, headline, textLeft + mc.font.width(DOT_GLYPH) + 4, y, GuiTheme.TEXT_PRIMARY);
		mc.font.drawShadow(poseStack, target, textLeft + headlineFixedWidth, y, GuiTheme.TEXT_SECONDARY);
		y += HUD_ROW_HEIGHT;

		for (HudRow row : rows) {
			mc.font.drawShadow(poseStack, row.label, textLeft, y, GuiTheme.TEXT_MUTED);
			mc.font.drawShadow(poseStack, RenderUtils.trimToWidth(row.value, contentWidth - labelWidth - HUD_LABEL_GAP), textLeft + labelWidth + HUD_LABEL_GAP, y, row.color);
			y += HUD_ROW_HEIGHT;
		}

		if (progress >= 0.0F) {
			RenderUtils.drawProgressBar(textLeft, y + 1, textLeft + contentWidth, y + 1 + HUD_PROGRESS_HEIGHT, progress, HUD_PROGRESS_BACKGROUND, MARKER_COLOR);
		}
	}

	/**
	 * The locations found before the current one, most recent first, so that a player searching for
	 * further instances can still see where the last ones were.
	 */
	private String previousLocations(ExplorersCompassItem compass, ItemStack stack) {
		final List<BlockPos> prevPos = getPrevPosCached(compass, stack);
		// The last entry is the location the compass currently points at, already shown above
		final int newest = prevPos.size() - 2;
		final StringBuilder locations = new StringBuilder();
		for (int i = 0; i < MAX_PREVIOUS_LOCATIONS_SHOWN && newest - i >= 0; i++) {
			final BlockPos pos = prevPos.get(newest - i);
			locations.append(locations.length() == 0 ? "" : " · ").append(pos.getX()).append(", ").append(pos.getZ());
		}
		return locations.toString();
	}

	/**
	 * The previously found locations, re-parsed from NBT only when the stack's tag object changes:
	 * the server replaces the whole stack when it syncs a change, so the tag's identity tracks it.
	 * This runs every frame, and parsing the list that often adds up.
	 */
	private List<BlockPos> getPrevPosCached(ExplorersCompassItem compass, ItemStack stack) {
		if (stack.getTag() != lastPrevPosTag) {
			cachedPrevPos = compass.getPrevPos(stack);
			lastPrevPosTag = stack.getTag();
		}
		return cachedPrevPos;
	}

	/** Whether the located coordinates mean anything where the player currently is. */
	private static boolean isInFoundDimension(Player player, ExplorersCompassItem compass, ItemStack stack) {
		final ResourceLocation foundDimension = compass.getFoundDimension(stack);
		return foundDimension == null || foundDimension.equals(player.level.dimension().location());
	}

	/** What a search is aiming at: the structure or group name, plus how many more it considers. */
	public static String searchTargetName(ExplorersCompassItem compass, ItemStack stack) {
		String name = compass.getIsGroup(stack) ? StructureUtils.getPrettyGroupName(compass.getStructureKey(stack)) : StructureUtils.getPrettyStructureName(compass.getStructureKey(stack));
		final int targetCount = compass.getTargetCount(stack);
		if (targetCount > 1) {
			name += " (+" + (targetCount - 1) + ")";
		}
		return name;
	}

	/** The eight-wind compass point from the player towards the location. */
	public static String compassDirection(Player player, int x, int z) {
		return WIND_POINTS[(int) Math.round(bearingTo(player, x, z) / 45.0D) % 8];
	}

	/**
	 * The compass bearing from the player towards the location, in degrees clockwise from north.
	 * North is negative Z, and east is positive X.
	 */
	private static double bearingTo(Player player, int x, int z) {
		return (Math.toDegrees(Math.atan2(x + 0.5D - player.getX(), player.getZ() - (z + 0.5D))) + 360.0D) % 360.0D;
	}

	/**
	 * Draws a strip of the horizon across the top of the screen, marking which way the located
	 * structure lies. Reading a direction off it takes a glance, where the pointer on the item has
	 * to be studied and the coordinates have to be worked out.
	 */
	private void renderDirectionBar(PoseStack poseStack, Player player, ExplorersCompassItem compass, ItemStack stack) {
		final int targetX = compass.getFoundStructureX(stack);
		final int targetY = compass.getFoundStructureY(stack);
		final int targetZ = compass.getFoundStructureZ(stack);

		final int barWidth = ConfigHandler.CLIENT.directionBarWidth.get();
		final int centerX = mc.getWindow().getGuiScaledWidth() / 2;
		final int left = centerX - barWidth / 2;
		final int right = left + barWidth;
		final int top = ConfigHandler.CLIENT.directionBarY.get();
		final int bottom = top + DIRECTION_BAR_HEIGHT;
		final double halfSpan = ConfigHandler.CLIENT.directionBarSpan.get() / 2.0D;
		final double pixelsPerDegree = (barWidth / 2.0D) / halfSpan;

		// An outline keeps the strip legible against a bright sky
		RenderUtils.drawOutline(left, top, right, bottom, BAR_BORDER_COLOR);
		RenderUtils.drawRect(left, top, right, bottom, BAR_BACKGROUND_COLOR);

		// Yaw 0 looks south, while bearings are counted from north
		final double playerBearing = player.getYRot() + 180.0D;

		drawBarTicks(centerX, top, bottom, halfSpan, pixelsPerDegree, playerBearing);
		drawBarLabels(poseStack, centerX, top, halfSpan, pixelsPerDegree, playerBearing);

		// Fading the ends hides marks appearing and disappearing at the edges, and is drawn over the
		// marks rather than under them
		final int fadeWidth = Math.min(DIRECTION_BAR_FADE_WIDTH, barWidth / 4);
		RenderUtils.drawHorizontalGradient(left, top, left + fadeWidth, bottom, BAR_FADE_COLOR, BAR_FADE_CLEAR_COLOR);
		RenderUtils.drawHorizontalGradient(right - fadeWidth, top, right, bottom, BAR_FADE_CLEAR_COLOR, BAR_FADE_COLOR);

		// Straight ahead, so that how far off the target lies reads at a glance
		RenderUtils.drawRect(centerX, top, centerX + 1, bottom, BAR_CENTER_COLOR);

		final double relative = Mth.wrapDegrees(bearingTo(player, targetX, targetZ) - playerBearing);
		// Turning the marker green the moment the target is straight ahead saves lining it up against
		// a one pixel wide line by eye
		final boolean onTarget = Math.abs(relative) <= ON_TARGET_DEGREES;
		final int markerColor = onTarget ? ON_TARGET_COLOR : MARKER_COLOR;
		if (Math.abs(relative) <= halfSpan) {
			drawTargetMarker(centerX + (int) Math.round(relative * pixelsPerDegree), top, bottom, markerColor);
		} else {
			// The structure lies outside the stretch of horizon the strip covers, so point the way to
			// turn to bring it into view instead
			drawEdgeArrow(relative > 0.0D ? right - 2 : left + 1, top + 2, bottom - 2, relative > 0.0D, markerColor);
		}

		// The distance is as much of a coordinate as the coordinates themselves, so it is held back
		// wherever they are
		if (compass.shouldDisplayCoordinates(stack)) {
			drawBarReadout(poseStack, player, centerX, bottom, targetX, targetY, targetZ, markerColor & 0xFFFFFF);
		}
	}

	/** Marks every fifteen degrees along the bottom of the strip, the wind points taller. */
	private void drawBarTicks(int centerX, int top, int bottom, double halfSpan, double pixelsPerDegree, double playerBearing) {
		final int firstTick = (int) Math.ceil((playerBearing - halfSpan) / DIRECTION_BAR_TICK_DEGREES) * DIRECTION_BAR_TICK_DEGREES;
		for (double degrees = firstTick; degrees <= playerBearing + halfSpan; degrees += DIRECTION_BAR_TICK_DEGREES) {
			final int x = centerX + (int) Math.round((degrees - playerBearing) * pixelsPerDegree);
			final boolean major = Math.floorMod((int) degrees, 45) == 0;
			RenderUtils.drawRect(x, major ? bottom - 5 : bottom - 3, x + 1, bottom - 1, major ? BAR_MAJOR_TICK_COLOR : BAR_MINOR_TICK_COLOR);
		}
	}

	/** Names all eight wind points, with north picked out and the diagonals held back. */
	private void drawBarLabels(PoseStack poseStack, int centerX, int top, double halfSpan, double pixelsPerDegree, double playerBearing) {
		for (int point = 0; point < WIND_POINTS.length; point++) {
			final double relative = Mth.wrapDegrees(point * 45.0D - playerBearing);
			if (Math.abs(relative) > halfSpan) {
				continue;
			}

			final String label = WIND_POINTS[point];
			final int color = point == 0 ? NORTH_LABEL_COLOR : (point % 2 == 0 ? CARDINAL_LABEL_COLOR : INTERCARDINAL_LABEL_COLOR);
			mc.font.draw(poseStack, label, centerX + (int) Math.round(relative * pixelsPerDegree) - mc.font.width(label) / 2.0F, top + 3, color);
		}
	}

	/** A line down the strip under a pointer, so the exact bearing is readable but the marks are not hidden. */
	private void drawTargetMarker(int markerX, int top, int bottom, int color) {
		RenderUtils.drawRect(markerX, top, markerX + 1, bottom, color);
		RenderUtils.drawRect(markerX - 2, top, markerX + 3, top + 1, color);
		RenderUtils.drawRect(markerX - 1, top + 1, markerX + 2, top + 2, color);
	}

	/** A triangle at one end of the strip, pointing the way to turn to bring the target into view. */
	private void drawEdgeArrow(int tipX, int top, int bottom, boolean pointingRight, int color) {
		final int halfHeight = (bottom - top) / 2;
		for (int column = 0; column < halfHeight; column++) {
			final int x = pointingRight ? tipX - halfHeight + 1 + column : tipX + halfHeight - 1 - column;
			RenderUtils.drawRect(x, top + column, x + 1, bottom - column, color);
		}
	}

	/** The distance under the strip, and how far up or down the structure sits when that is known. */
	private void drawBarReadout(PoseStack poseStack, Player player, int centerX, int bottom, int targetX, int targetY, int targetZ, int color) {
		String readout = String.format("%,d", StructureUtils.getHorizontalDistanceToLocation(player, targetX, targetZ));
		if (targetY != ExplorersCompassItem.UNKNOWN_Y) {
			final int climb = targetY - player.getBlockY();
			if (climb != 0) {
				readout += "  " + (climb > 0 ? "↑ " : "↓ ") + Math.abs(climb);
			}
		}
		mc.font.drawShadow(poseStack, readout, centerX - mc.font.width(readout) / 2.0F, bottom + 3, color);
	}

}
