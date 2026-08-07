package com.chaosthedude.explorerscompass.client;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.chaosthedude.explorerscompass.gui.GuiTheme;
import com.chaosthedude.explorerscompass.items.ExplorersCompassItem;
import com.chaosthedude.explorerscompass.util.CompassState;
import com.chaosthedude.explorerscompass.util.ItemUtils;
import com.chaosthedude.explorerscompass.util.RenderUtils;
import com.chaosthedude.explorerscompass.util.SearchTarget;
import com.chaosthedude.explorerscompass.util.StructureUtils;
import com.mojang.blaze3d.vertex.PoseStack;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.TickEvent.ClientTickEvent;
import net.minecraftforge.event.TickEvent.Phase;
import net.minecraftforge.event.TickEvent.RenderTickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

@OnlyIn(Dist.CLIENT)
public class ClientEventHandler {

	private static final int MAX_PREVIOUS_LOCATIONS_SHOWN = 2;
	/**
	 * How many of the previously found instances the direction strip marks at once, before it stops
	 * saying anything and just looks busy.
	 */
	private static final int MAX_PREVIOUS_MARKERS = 6;

	/**
	 * The eight wind points, clockwise from north. They are named in the player's own language, since
	 * the letters that abbreviate them are not the same in every one.
	 */
	private static final String[] WIND_POINT_KEYS = {"north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest"};

	private static final String DOT_GLYPH = "●";

	// The panel of compass information, laid out as a headline and a column of labelled values
	private static final int HUD_MARGIN = 5;
	private static final int HUD_PADDING = 5;
	private static final int HUD_ROW_HEIGHT = 10;
	private static final int HUD_LABEL_GAP = 8;
	private static final int HUD_PROGRESS_HEIGHT = 3;
	private static final int HUD_PROGRESS_BACKGROUND = 0x60000000;
	/**
	 * The track the progress runs along has to stay readable without being one more dark box, so with
	 * the panel turned off it is drawn as a light track rather than a dark one.
	 */
	private static final int HUD_PROGRESS_BACKGROUND_PLAIN = 0x40FFFFFF;

	private static final int DIRECTION_BAR_HEIGHT = 14;
	/** Marks are drawn this many degrees apart, with the ones at the eight wind points drawn taller. */
	private static final int DIRECTION_BAR_TICK_DEGREES = 15;
	/** How far the strip fades out towards each end, so that marks appear and leave gradually. */
	private static final int DIRECTION_BAR_FADE_WIDTH = 24;
	/**
	 * Faded below this, a mark is left out altogether: a colour carrying almost no transparency left
	 * is drawn as if it carried none at all.
	 */
	private static final float MINIMUM_FADE = 0.05F;
	/** How near the middle of the strip the target has to sit to count as being faced. */
	private static final double ON_TARGET_DEGREES = 3.0D;
	/** How near the located structure counts as having arrived at it. */
	private static final int ARRIVED_DISTANCE = 32;
	/** How long the panel picks itself out after a search finishes. */
	private static final long ANNOUNCE_MILLIS = 2500L;

	// Filled shapes take their alpha from the top byte, where text has it added for it
	private static final int MARKER_COLOR = 0xFF000000 | GuiTheme.ACCENT;
	private static final int ON_TARGET_COLOR = 0xFF000000 | GuiTheme.TEXT_SUCCESS;
	private static final int PREVIOUS_MARKER_COLOR = 0x59FFC24B;
	/** What the marks on the strip are shadowed with when there is no panel behind them. */
	private static final int MARK_SHADOW_COLOR = 0xA0000000;
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

	private Tag lastPrevPosTag;
	private List<BlockPos> cachedPrevPos = List.of();
	// A search finishing is the one thing the player is not watching the panel for, so the panel says
	// so by picking itself out for a moment afterwards
	private CompassState lastState;
	private long announceUntil;
	private HudData hudData;

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

	/** Everything the HUD can resolve once per client tick rather than once per rendered frame. */
	private static class HudData {

		private final CompassState state;
		private final String headline;
		private final String target;
		private final int dotColor;
		private final float progress;
		private final List<HudRow> rows;
		private final boolean inFoundDimension;
		private final boolean displayCoordinates;
		private final int targetX;
		private final int targetY;
		private final int targetZ;
		private final List<BlockPos> previousLocations;

		private HudData(CompassState state, String headline, String target, int dotColor,
				float progress, List<HudRow> rows, boolean inFoundDimension,
				boolean displayCoordinates, int targetX, int targetY, int targetZ,
				List<BlockPos> previousLocations) {
			this.state = state;
			this.headline = headline;
			this.target = target;
			this.dotColor = dotColor;
			this.progress = progress;
			this.rows = rows;
			this.inFoundDimension = inFoundDimension;
			this.displayCoordinates = displayCoordinates;
			this.targetX = targetX;
			this.targetY = targetY;
			this.targetZ = targetZ;
			this.previousLocations = previousLocations;
		}

	}

	@SubscribeEvent
	public void onClientTick(ClientTickEvent event) {
		if (event.phase != Phase.END) {
			return;
		}
		if (mc.player == null || mc.level == null) {
			hudData = null;
			lastState = null;
			lastPrevPosTag = null;
			cachedPrevPos = List.of();
			// Not in a world any more, so nothing held about the last one still means anything. What a
			// structure looks like follows from a server's data packs, and the next server joined may
			// load different ones.
			StructurePreviewCache.clear();
			return;
		}

		final Player player = mc.player;
		final ItemStack stack = ItemUtils.getHeldItem(player, ExplorersCompass.explorersCompass);
		if (stack == null || !(stack.getItem() instanceof ExplorersCompassItem compass)) {
			hudData = null;
			return;
		}

		final CompassState state = compass.getState(stack);
		if (state != lastState) {
			if (lastState == CompassState.SEARCHING
					&& (state == CompassState.FOUND || state == CompassState.NOT_FOUND)) {
				announceUntil = Util.getMillis() + ANNOUNCE_MILLIS;
			}
			lastState = state;
		}
		if (state == null || state == CompassState.INACTIVE) {
			hudData = null;
			return;
		}

		if (state == CompassState.SEARCHING) {
			XaeroMinimapIntegration.reset();
		} else if (state == CompassState.FOUND && isInFoundDimension(player, compass, stack)) {
			XaeroMinimapIntegration.createWaypointForLocation(player, compass, stack);
		}
		hudData = createHudData(player, compass, stack, state);
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
		final HudData data = hudData;
		if (data == null) {
			return;
		}

		final PoseStack poseStack = new PoseStack();
		if (data.state == CompassState.FOUND && data.inFoundDimension) {
			if (ConfigHandler.CLIENT.showDirectionBar.get()) {
				renderDirectionBar(poseStack, player, data);
			}
		}

		renderInfoPanel(poseStack, data);
	}

	/**
	 * Draws what the compass is doing as a panel of its own: a headline saying which state it is in
	 * and what it is aimed at, then a column of labelled values under it. Reading a value takes
	 * finding its label on the same line, rather than counting lines between two columns of text.
	 */
	private void renderInfoPanel(PoseStack poseStack, HudData data) {
		final int dotColor;
		if (data.state == CompassState.SEARCHING) {
			// Fading the marker in and out is what says the search is still running, on a readout where
			// only the radius changes and only every so often
			final float pulse = (Mth.sin(Util.getMillis() / 200.0F) + 1.0F) / 2.0F;
			dotColor = (GuiTheme.ACCENT & 0xFFFFFF) | ((int) (110 + 145 * pulse) << 24);
		} else {
			dotColor = data.dotColor;
		}

		int labelWidth = 0;
		int valueWidth = 0;
		for (HudRow row : data.rows) {
			labelWidth = Math.max(labelWidth, mc.font.width(row.label));
			valueWidth = Math.max(valueWidth, mc.font.width(row.value));
		}

		// A structure name can be arbitrarily long, and the panel is not allowed to grow across the
		// screen with it. The values are given more room than the headline, since a name can be cut
		// short and still be recognised where a coordinate cannot.
		final int screenWidth = mc.getWindow().getGuiScaledWidth();
		final int maxHeadlineWidth = Math.max(80, screenWidth / 3);
		final int maxContentWidth = Math.max(120, screenWidth / 2);
		final int headlineFixedWidth = mc.font.width(DOT_GLYPH) + 4
				+ mc.font.width(data.headline) + 5;
		final String target = RenderUtils.trimToWidth(data.target,
				maxHeadlineWidth - headlineFixedWidth);

		final int contentWidth = Math.min(maxContentWidth, Math.max(headlineFixedWidth + mc.font.width(target), labelWidth + HUD_LABEL_GAP + valueWidth));
		final int contentHeight = HUD_ROW_HEIGHT + data.rows.size() * HUD_ROW_HEIGHT
				+ (data.progress >= 0.0F ? HUD_PROGRESS_HEIGHT + 2 : 0);
		final int panelLeft = ConfigHandler.CLIENT.overlaySide.get() == OverlaySide.LEFT ? HUD_MARGIN : screenWidth - HUD_MARGIN - contentWidth - HUD_PADDING * 2;
		final int panelTop = HUD_MARGIN + ConfigHandler.CLIENT.overlayLineOffset.get() * 9;

		final boolean background = ConfigHandler.CLIENT.overlayBackground.get();
		if (background) {
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
		mc.font.drawShadow(poseStack, data.headline,
				textLeft + mc.font.width(DOT_GLYPH) + 4, y, GuiTheme.TEXT_PRIMARY);
		mc.font.drawShadow(poseStack, target, textLeft + headlineFixedWidth, y, GuiTheme.TEXT_SECONDARY);
		y += HUD_ROW_HEIGHT;

		for (HudRow row : data.rows) {
			mc.font.drawShadow(poseStack, row.label, textLeft, y, GuiTheme.TEXT_MUTED);
			mc.font.drawShadow(poseStack, RenderUtils.trimToWidth(row.value, contentWidth - labelWidth - HUD_LABEL_GAP), textLeft + labelWidth + HUD_LABEL_GAP, y, row.color);
			y += HUD_ROW_HEIGHT;
		}

		if (data.progress >= 0.0F) {
			RenderUtils.drawProgressBar(textLeft, y + 1, textLeft + contentWidth,
					y + 1 + HUD_PROGRESS_HEIGHT, data.progress,
					background ? HUD_PROGRESS_BACKGROUND : HUD_PROGRESS_BACKGROUND_PLAIN,
					MARKER_COLOR);
		}
	}

	private HudData createHudData(Player player, ExplorersCompassItem compass, ItemStack stack,
			CompassState state) {
		final List<HudRow> rows = new ArrayList<HudRow>();
		final String headline;
		final String target;
		int dotColor = GuiTheme.TEXT_WARNING;
		float progress = -1.0F;
		boolean inFoundDimension = false;
		boolean displayCoordinates = false;
		int targetX = 0;
		int targetY = ExplorersCompassItem.UNKNOWN_Y;
		int targetZ = 0;
		List<BlockPos> previousLocations = List.of();

		if (state == CompassState.SEARCHING) {
			headline = I18n.get("string.explorerscompass.searching");
			target = searchTargetName(compass, stack);
			final int radius = compass.getSearchRadius(stack);
			final int maxRadius = ConfigHandler.GENERAL.maxRadius.get();
			final String radiusText = radius <= maxRadius && maxRadius > 0
					? String.format("%,d", radius) + " / " + String.format("%,d", maxRadius)
					: String.format("%,d", radius);
			rows.add(new HudRow(I18n.get("string.explorerscompass.radius"), radiusText,
					GuiTheme.TEXT_PRIMARY));
			if (maxRadius > 0) {
				progress = (float) radius / maxRadius;
			}
		} else if (state == CompassState.FOUND) {
			headline = I18n.get("string.explorerscompass.found");
			target = compass.getSearchTarget(stack).getPrettyName(compass.getTargetKey(stack));
			inFoundDimension = isInFoundDimension(player, compass, stack);
			dotColor = inFoundDimension ? GuiTheme.TEXT_SUCCESS : GuiTheme.TEXT_WARNING;
			targetX = compass.getFoundStructureX(stack);
			targetY = compass.getFoundStructureY(stack);
			targetZ = compass.getFoundStructureZ(stack);
			displayCoordinates = compass.shouldDisplayCoordinates(stack);
			previousLocations = List.copyOf(getPrevPosCached(compass, stack));

			if (!inFoundDimension) {
				rows.add(new HudRow(I18n.get("string.explorerscompass.dimension"),
						StructureUtils.getDimensionName(compass.getFoundDimension(stack)),
						GuiTheme.TEXT_WARNING));
			} else if (displayCoordinates) {
				rows.add(new HudRow(I18n.get("string.explorerscompass.coordinates"),
						StructureUtils.formatCoordinates(targetX, targetY, targetZ),
						GuiTheme.TEXT_PRIMARY));

				final int distance = StructureUtils.getHorizontalDistanceToLocation(
						player, targetX, targetZ);
				rows.add(new HudRow(I18n.get("string.explorerscompass.distance"),
						String.format("%,d", distance) + " ("
								+ compassDirection(player, targetX, targetZ) + ")",
						distance <= ARRIVED_DISTANCE
								? GuiTheme.TEXT_SUCCESS : GuiTheme.TEXT_PRIMARY));

				final String previous = previousLocations(previousLocations);
				if (!previous.isEmpty()) {
					rows.add(new HudRow(I18n.get("string.explorerscompass.previousLocations"),
							previous, GuiTheme.TEXT_SECONDARY));
				}
			}
		} else {
			headline = I18n.get("string.explorerscompass.notFound");
			target = compass.getSearchTarget(stack).getPrettyName(compass.getTargetKey(stack));
			rows.add(new HudRow(I18n.get("string.explorerscompass.radius"),
					String.format("%,d", compass.getSearchRadius(stack)),
					GuiTheme.TEXT_PRIMARY));
			rows.add(new HudRow(I18n.get("string.explorerscompass.samples"),
					String.format("%,d", compass.getSamples(stack)), GuiTheme.TEXT_PRIMARY));
		}

		return new HudData(state, headline, target, dotColor, progress, List.copyOf(rows),
				inFoundDimension, displayCoordinates, targetX, targetY, targetZ,
				previousLocations);
	}

	/**
	 * The locations found before the current one, most recent first, so that a player searching for
	 * further instances can still see where the last ones were.
	 */
	private String previousLocations(List<BlockPos> prevPos) {
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
	 * The previously found locations, re-parsed from NBT only when the list itself changes: it is
	 * replaced wholesale when the server syncs a change and removed when the compass is cleared, so
	 * its identity tracks its contents. Watching the stack's tag instead would miss the compass being
	 * cleared on this side, where the tag is emptied rather than replaced, and would leave the marks
	 * for forgotten locations on the strip until the server happened to send a new stack. This runs
	 * every frame, and parsing the list that often adds up.
	 */
	private List<BlockPos> getPrevPosCached(ExplorersCompassItem compass, ItemStack stack) {
		final Tag prevPosTag = compass.getPrevPosTag(stack);
		if (prevPosTag != lastPrevPosTag) {
			cachedPrevPos = compass.getPrevPos(stack);
			lastPrevPosTag = prevPosTag;
		}
		return cachedPrevPos;
	}

	/** Whether the located coordinates mean anything where the player currently is. */
	private static boolean isInFoundDimension(Player player, ExplorersCompassItem compass, ItemStack stack) {
		final ResourceLocation foundDimension = compass.getFoundDimension(stack);
		return foundDimension == null || foundDimension.equals(player.level.dimension().location());
	}

	/** What a search is aiming at: the name of what or of the group, plus how many more it considers. */
	public static String searchTargetName(ExplorersCompassItem compass, ItemStack stack) {
		final SearchTarget searchTarget = compass.getSearchTarget(stack);
		String name = compass.getIsGroup(stack) ? searchTarget.getPrettyGroupName(compass.getTargetKey(stack)) : searchTarget.getPrettyName(compass.getTargetKey(stack));
		final int targetCount = compass.getTargetCount(stack);
		if (targetCount > 1) {
			name += " (+" + (targetCount - 1) + ")";
		}
		return name;
	}

	/** The eight-wind compass point from the player towards the location. */
	public static String compassDirection(Player player, int x, int z) {
		return windPoint((int) Math.round(bearingTo(player, x, z) / 45.0D) % 8);
	}

	private static String windPoint(int index) {
		return I18n.get("string.explorerscompass.direction." + WIND_POINT_KEYS[index]);
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
	private void renderDirectionBar(PoseStack poseStack, Player player, HudData data) {
		final int targetX = data.targetX;
		final int targetY = data.targetY;
		final int targetZ = data.targetZ;
		final BarLayout bar = new BarLayout(mc.getWindow().getGuiScaledWidth(), player.getYRot());

		if (bar.background) {
			// An outline keeps the strip legible against a bright sky
			RenderUtils.drawOutline(bar.left, bar.top, bar.right, bar.bottom, BAR_BORDER_COLOR);
			RenderUtils.drawRect(bar.left, bar.top, bar.right, bar.bottom, BAR_BACKGROUND_COLOR);
		}

		// Drawn first, so that the marks of the horizon and their names stay crisp over them
		drawPreviousMarkers(player, data.previousLocations, bar);
		drawBarTicks(bar);
		drawBarLabels(poseStack, bar);

		if (bar.background) {
			// Fading the ends hides marks appearing and disappearing at the edges, and is drawn over the
			// marks rather than under them. With no panel to fade, each mark fades itself instead.
			RenderUtils.drawHorizontalGradient(bar.left, bar.top, bar.left + bar.fadeWidth, bar.bottom, BAR_FADE_COLOR, BAR_FADE_CLEAR_COLOR);
			RenderUtils.drawHorizontalGradient(bar.right - bar.fadeWidth, bar.top, bar.right, bar.bottom, BAR_FADE_CLEAR_COLOR, BAR_FADE_COLOR);
		}

		// Straight ahead, so that how far off the target lies reads at a glance
		drawBarMark(bar, bar.centerX, bar.top, bar.bottom, BAR_CENTER_COLOR, 1.0F);

		final double relative = Mth.wrapDegrees(bearingTo(player, targetX, targetZ) - bar.playerBearing);
		// Turning the marker green the moment the target is straight ahead saves lining it up against
		// a one pixel wide line by eye
		final int markerColor = Math.abs(relative) <= ON_TARGET_DEGREES ? ON_TARGET_COLOR : MARKER_COLOR;
		final boolean inSpan = Math.abs(relative) <= bar.halfSpan;
		if (inSpan) {
			drawTargetMarker(bar, bar.xFor(relative), markerColor);
		} else {
			// The structure lies outside the stretch of horizon the strip covers, so point the way to
			// turn to bring it into view instead
			drawEdgeArrow(bar, relative > 0.0D ? bar.right - 2 : bar.left + 1, relative > 0.0D, markerColor);
		}

		drawBarReadout(poseStack, player, data.displayCoordinates, bar.centerX, bar.bottom,
				targetX, targetY, targetZ, relative, inSpan, markerColor & 0xFFFFFF);
	}

	/**
	 * Where the strip sits this frame and which way the player is looking. The several parts of the
	 * strip all measure themselves against the same handful of numbers, so they are worked out once
	 * and handed around rather than passed one by one.
	 */
	private static class BarLayout {

		private final int left;
		private final int right;
		private final int top;
		private final int bottom;
		private final int centerX;
		private final int fadeWidth;
		private final double halfSpan;
		private final double pixelsPerDegree;
		private final double playerBearing;
		private final boolean background;

		private BarLayout(int screenWidth, float playerYRot) {
			// A strip configured wider than the screen is cut down to it, which is what lets any large
			// width simply span whatever screen the game is being played on
			final int width = Math.min(ConfigHandler.CLIENT.directionBarWidth.get(), screenWidth - 2);
			centerX = screenWidth / 2;
			left = centerX - width / 2;
			right = left + width;
			top = ConfigHandler.CLIENT.directionBarY.get();
			bottom = top + DIRECTION_BAR_HEIGHT;
			halfSpan = ConfigHandler.CLIENT.directionBarSpan.get() / 2.0D;
			pixelsPerDegree = (width / 2.0D) / halfSpan;
			fadeWidth = Math.min(DIRECTION_BAR_FADE_WIDTH, width / 4);
			background = ConfigHandler.CLIENT.directionBarBackground.get();
			// Yaw 0 looks south, while bearings are counted from north
			playerBearing = playerYRot + 180.0D;
		}

		/** Where along the strip something lying this many degrees off straight ahead is drawn. */
		private int xFor(double relative) {
			return centerX + (int) Math.round(relative * pixelsPerDegree);
		}

		/**
		 * How much of its colour something at this point along the strip keeps. Without a panel there
		 * is nothing to fade at the ends, so what is drawn on the strip fades out towards them itself.
		 */
		private float fade(int x) {
			if (background || fadeWidth <= 0) {
				return 1.0F;
			}
			return Mth.clamp((float) Math.min(x - left, right - x) / fadeWidth, 0.0F, 1.0F);
		}

	}

	/** Marks every fifteen degrees along the bottom of the strip, the wind points taller. */
	private void drawBarTicks(BarLayout bar) {
		final int firstTick = (int) Math.ceil((bar.playerBearing - bar.halfSpan) / DIRECTION_BAR_TICK_DEGREES) * DIRECTION_BAR_TICK_DEGREES;
		for (double degrees = firstTick; degrees <= bar.playerBearing + bar.halfSpan; degrees += DIRECTION_BAR_TICK_DEGREES) {
			final int x = bar.xFor(degrees - bar.playerBearing);
			final boolean major = Math.floorMod((int) degrees, 45) == 0;
			// The taller marks stop short of the labels above them, which sit at those same degrees
			drawBarMark(bar, x, major ? bar.bottom - 4 : bar.bottom - 3, bar.bottom - 1, major ? BAR_MAJOR_TICK_COLOR : BAR_MINOR_TICK_COLOR, bar.fade(x));
		}
	}

	/** Names all eight wind points, with north picked out and the diagonals held back. */
	private void drawBarLabels(PoseStack poseStack, BarLayout bar) {
		for (int point = 0; point < WIND_POINT_KEYS.length; point++) {
			final double relative = Mth.wrapDegrees(point * 45.0D - bar.playerBearing);
			if (Math.abs(relative) > bar.halfSpan) {
				continue;
			}

			final int x = bar.xFor(relative);
			final float fade = bar.fade(x);
			if (fade < MINIMUM_FADE) {
				continue;
			}

			final String label = windPoint(point);
			final int color = point == 0 ? NORTH_LABEL_COLOR : (point % 2 == 0 ? CARDINAL_LABEL_COLOR : INTERCARDINAL_LABEL_COLOR);
			// Shadowed rather than plain, since without a panel these letters can end up on a bright sky
			mc.font.drawShadow(poseStack, label, x - mc.font.width(label) / 2.0F, bar.top + 2, fadeText(color, fade));
		}
	}

	/**
	 * Marks the other instances of the same structure that this compass has already found, so that a
	 * player who searched past one can see where the ones behind them lie. They are drawn faint, and
	 * before the ends of the strip are faded, so that the structure being pointed at stays the one
	 * mark that stands out.
	 */
	private void drawPreviousMarkers(Player player, List<BlockPos> prevPos, BarLayout bar) {
		// The last entry is the location the strip already marks
		int drawn = 0;
		for (int i = prevPos.size() - 2; i >= 0 && drawn < MAX_PREVIOUS_MARKERS; i--) {
			final BlockPos pos = prevPos.get(i);
			final double relative = Mth.wrapDegrees(bearingTo(player, pos.getX(), pos.getZ()) - bar.playerBearing);
			if (Math.abs(relative) > bar.halfSpan) {
				continue;
			}
			final int x = bar.xFor(relative);
			drawBarMark(bar, x, bar.top + 1, bar.bottom - 1, PREVIOUS_MARKER_COLOR, bar.fade(x));
			drawn++;
		}
	}

	/**
	 * A one pixel wide mark down the strip. With no panel behind it, it carries a shadow of its own,
	 * the way the text on the strip does.
	 */
	private void drawBarMark(BarLayout bar, int x, int top, int bottom, int color, float fade) {
		if (fade < MINIMUM_FADE) {
			return;
		}
		if (!bar.background) {
			RenderUtils.drawRect(x + 1, top + 1, x + 2, bottom + 1, fadeFill(MARK_SHADOW_COLOR, fade));
		}
		RenderUtils.drawRect(x, top, x + 1, bottom, fadeFill(color, fade));
	}

	/** A line down the strip under a pointer, so the exact bearing is readable but the marks are not hidden. */
	private void drawTargetMarker(BarLayout bar, int markerX, int color) {
		if (!bar.background) {
			drawTargetMarkerShape(markerX + 1, bar.top + 1, bar.bottom + 1, MARK_SHADOW_COLOR);
		}
		drawTargetMarkerShape(markerX, bar.top, bar.bottom, color);
	}

	private void drawTargetMarkerShape(int markerX, int top, int bottom, int color) {
		RenderUtils.drawRect(markerX, top, markerX + 1, bottom, color);
		RenderUtils.drawRect(markerX - 2, top, markerX + 3, top + 1, color);
		RenderUtils.drawRect(markerX - 1, top + 1, markerX + 2, top + 2, color);
	}

	/** A triangle at one end of the strip, pointing the way to turn to bring the target into view. */
	private void drawEdgeArrow(BarLayout bar, int tipX, boolean pointingRight, int color) {
		if (!bar.background) {
			drawEdgeArrowShape(tipX + 1, bar.top + 3, bar.bottom - 1, pointingRight, MARK_SHADOW_COLOR);
		}
		drawEdgeArrowShape(tipX, bar.top + 2, bar.bottom - 2, pointingRight, color);
	}

	private void drawEdgeArrowShape(int tipX, int top, int bottom, boolean pointingRight, int color) {
		final int halfHeight = (bottom - top) / 2;
		for (int column = 0; column < halfHeight; column++) {
			final int x = pointingRight ? tipX - halfHeight + 1 + column : tipX + halfHeight - 1 - column;
			RenderUtils.drawRect(x, top + column, x + 1, bottom - column, color);
		}
	}

	/** Scales the transparency a filled shape already carries. */
	private static int fadeFill(int color, float fade) {
		return (color & 0xFFFFFF) | ((int) ((color >>> 24) * fade) << 24);
	}

	/** Gives a text colour, which carries no transparency of its own, one to fade out with. */
	private static int fadeText(int color, float fade) {
		return (color & 0xFFFFFF) | ((int) (255 * fade) << 24);
	}

	/**
	 * What is left to travel, under the strip: the distance, how far up or down the structure sits
	 * when that is known, and how far there is left to turn while it lies off the strip altogether.
	 */
	private void drawBarReadout(PoseStack poseStack, Player player,
			boolean displayCoordinates, int centerX, int bottom, int targetX, int targetY,
			int targetZ, double relative, boolean inSpan, int color) {
		final StringBuilder readout = new StringBuilder();
		// The distance is as much of a coordinate as the coordinates themselves, so it is held back
		// wherever they are. Which way to turn is not: that is what the strip is for.
		if (displayCoordinates) {
			readout.append(String.format("%,d", StructureUtils.getHorizontalDistanceToLocation(player, targetX, targetZ)));
			if (targetY != ExplorersCompassItem.UNKNOWN_Y) {
				final int climb = targetY - player.getBlockY();
				if (climb != 0) {
					readout.append("  ").append(climb > 0 ? "↑ " : "↓ ").append(Math.abs(climb));
				}
			}
		}
		if (!inSpan) {
			// Off the strip every bearing looks the same, so say how much of a turn is left
			readout.append(readout.length() == 0 ? "" : "   ").append(relative > 0.0D ? "→ " : "← ").append(Math.round(Math.abs(relative))).append("°");
		}
		if (readout.length() == 0) {
			return;
		}

		mc.font.drawShadow(poseStack, readout.toString(), centerX - mc.font.width(readout.toString()) / 2.0F, bottom + 3, color);
	}

}
