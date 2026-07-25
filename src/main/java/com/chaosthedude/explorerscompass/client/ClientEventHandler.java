package com.chaosthedude.explorerscompass.client;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.chaosthedude.explorerscompass.items.ExplorersCompassItem;
import com.chaosthedude.explorerscompass.util.CompassState;
import com.chaosthedude.explorerscompass.util.ItemUtils;
import com.chaosthedude.explorerscompass.util.RenderUtils;
import com.chaosthedude.explorerscompass.util.StructureUtils;
import com.mojang.blaze3d.vertex.PoseStack;

import java.util.List;

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

	private static final int DIRECTION_BAR_HEIGHT = 14;
	// Marks are drawn this many degrees apart, with the ones at the eight wind points drawn taller
	private static final int DIRECTION_BAR_TICK_DEGREES = 15;
	// How far the strip fades out towards each end, so that marks appear and leave gradually
	private static final int DIRECTION_BAR_FADE_WIDTH = 24;

	// Filled shapes take their alpha from the top byte, where text has it added for it
	private static final int MARKER_COLOR = 0xFFFFAA00;
	private static final int MARKER_TEXT_COLOR = 0xFFAA00;
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

	@SubscribeEvent
	public void onRenderTick(RenderTickEvent event) {
		if (event.phase == Phase.END && mc.player != null && mc.level != null && !mc.options.hideGui && !mc.options.renderDebug && (mc.screen == null || (ConfigHandler.CLIENT.displayWithChatOpen.get() && mc.screen instanceof ChatScreen))) {
			final Player player = mc.player;
			final ItemStack stack = ItemUtils.getHeldItem(player, ExplorersCompass.explorersCompass);
			if (stack != null && stack.getItem() instanceof ExplorersCompassItem) {
				PoseStack poseStack = new PoseStack();
				final ExplorersCompassItem compass = (ExplorersCompassItem) stack.getItem();
				if (compass.getState(stack) == CompassState.SEARCHING) {
					RenderUtils.drawConfiguredStringOnHUD(poseStack, I18n.get("string.explorerscompass.status"), 5, 5, 0xFFFFFF, 0);
					RenderUtils.drawConfiguredStringOnHUD(poseStack, I18n.get("string.explorerscompass.searching"), 5, 5, 0xAAAAAA, 1);

					RenderUtils.drawConfiguredStringOnHUD(poseStack, I18n.get("string.explorerscompass.structure"), 5, 5, 0xFFFFFF, 3);
					RenderUtils.drawConfiguredStringOnHUD(poseStack, searchTargetName(compass, stack), 5, 5, 0xAAAAAA, 4);

					RenderUtils.drawConfiguredStringOnHUD(poseStack, I18n.get("string.explorerscompass.radius"), 5, 5, 0xFFFFFF, 6);
 					RenderUtils.drawConfiguredStringOnHUD(poseStack, String.format("%,d", compass.getSearchRadius(stack)), 5, 5, 0xAAAAAA, 7);

					// Let the next result create a waypoint, even if it is the same location as the last one
					XaeroMinimapIntegration.reset();
				} else if (compass.getState(stack) == CompassState.FOUND) {
					RenderUtils.drawConfiguredStringOnHUD(poseStack, I18n.get("string.explorerscompass.status"), 5, 5, 0xFFFFFF, 0);
					RenderUtils.drawConfiguredStringOnHUD(poseStack, I18n.get("string.explorerscompass.found"), 5, 5, 0xAAAAAA, 1);

					RenderUtils.drawConfiguredStringOnHUD(poseStack, I18n.get("string.explorerscompass.structure"), 5, 5, 0xFFFFFF, 3);
					RenderUtils.drawConfiguredStringOnHUD(poseStack, StructureUtils.getPrettyStructureName(compass.getStructureKey(stack)), 5, 5, 0xAAAAAA, 4);

					final ResourceLocation foundDimension = compass.getFoundDimension(stack);
					final boolean inFoundDimension = foundDimension == null || foundDimension.equals(player.level.dimension().location());
					if (!inFoundDimension) {
						// The coordinates belong to another dimension, where the distance to them means
						// nothing, so show which dimension they are in instead
						RenderUtils.drawConfiguredStringOnHUD(poseStack, I18n.get("string.explorerscompass.dimension"), 5, 5, 0xFFFFFF, 6);
						RenderUtils.drawConfiguredStringOnHUD(poseStack, StructureUtils.getDimensionName(foundDimension), 5, 5, 0xFF5555, 7);
					} else if (compass.shouldDisplayCoordinates(stack)) {
						final int x = compass.getFoundStructureX(stack);
						final int z = compass.getFoundStructureZ(stack);
						final int y = compass.getFoundStructureY(stack);
						RenderUtils.drawConfiguredStringOnHUD(poseStack, I18n.get("string.explorerscompass.coordinates"), 5, 5, 0xFFFFFF, 6);
						RenderUtils.drawConfiguredStringOnHUD(poseStack, y != ExplorersCompassItem.UNKNOWN_Y ? x + ", " + y + ", " + z : x + ", " + z, 5, 5, 0xAAAAAA, 7);

						RenderUtils.drawConfiguredStringOnHUD(poseStack, I18n.get("string.explorerscompass.distance"), 5, 5, 0xFFFFFF, 9);
						RenderUtils.drawConfiguredStringOnHUD(poseStack, String.format("%,d", StructureUtils.getHorizontalDistanceToLocation(player, x, z)) + " (" + compassDirection(player, x, z) + ")", 5, 5, 0xAAAAAA, 10);

						drawPreviousLocations(poseStack, compass, stack, 12);
					}
					// A waypoint in another dimension's coordinates would point at the wrong place
					if (inFoundDimension) {
						XaeroMinimapIntegration.createWaypointForLocatedStructure(player, compass, stack);
						if (ConfigHandler.CLIENT.showDirectionBar.get()) {
							renderDirectionBar(poseStack, player, compass.getFoundStructureX(stack), compass.getFoundStructureY(stack), compass.getFoundStructureZ(stack));
						}
					}
				} else if (compass.getState(stack) == CompassState.NOT_FOUND) {
					RenderUtils.drawConfiguredStringOnHUD(poseStack, I18n.get("string.explorerscompass.status"), 5, 5, 0xFFFFFF, 0);
					RenderUtils.drawConfiguredStringOnHUD(poseStack, I18n.get("string.explorerscompass.notFound"), 5, 5, 0xAAAAAA, 1);

					RenderUtils.drawConfiguredStringOnHUD(poseStack, I18n.get("string.explorerscompass.structure"), 5, 5, 0xFFFFFF, 3);
					RenderUtils.drawConfiguredStringOnHUD(poseStack, StructureUtils.getPrettyStructureName(compass.getStructureKey(stack)), 5, 5, 0xAAAAAA, 4);

					RenderUtils.drawConfiguredStringOnHUD(poseStack, I18n.get("string.explorerscompass.radius"), 5, 5, 0xFFFFFF, 6);
					RenderUtils.drawConfiguredStringOnHUD(poseStack, String.format("%,d", compass.getSearchRadius(stack)), 5, 5, 0xAAAAAA, 7);

					// A search can also end because it ran out of samples, in which case the radius alone
					// is far below the configured maximum and looks like a premature stop
					RenderUtils.drawConfiguredStringOnHUD(poseStack, I18n.get("string.explorerscompass.samples"), 5, 5, 0xFFFFFF, 9);
					RenderUtils.drawConfiguredStringOnHUD(poseStack, String.format("%,d", compass.getSamples(stack)), 5, 5, 0xAAAAAA, 10);
				}
			}
		}
	}

	/**
	 * Lists the locations found before the current one, most recent first, so that a player
	 * searching for further instances can still see where the last ones were.
	 */
	private void drawPreviousLocations(PoseStack poseStack, ExplorersCompassItem compass, ItemStack stack, int relLineOffset) {
		final List<BlockPos> prevPos = getPrevPosCached(compass, stack);
		// The last entry is the location the compass currently points at, already shown above
		final int newest = prevPos.size() - 2;
		if (newest < 0) {
			return;
		}

		RenderUtils.drawConfiguredStringOnHUD(poseStack, I18n.get("string.explorerscompass.previousLocations"), 5, 5, 0xFFFFFF, relLineOffset);
		for (int i = 0; i < MAX_PREVIOUS_LOCATIONS_SHOWN && newest - i >= 0; i++) {
			final BlockPos pos = prevPos.get(newest - i);
			RenderUtils.drawConfiguredStringOnHUD(poseStack, pos.getX() + ", " + pos.getZ(), 5, 5, 0xAAAAAA, relLineOffset + 1 + i);
		}
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
	private void renderDirectionBar(PoseStack poseStack, Player player, int targetX, int targetY, int targetZ) {
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
		if (Math.abs(relative) <= halfSpan) {
			drawTargetMarker(centerX + (int) Math.round(relative * pixelsPerDegree), top, bottom);
		} else {
			// The structure lies outside the stretch of horizon the strip covers, so point the way to
			// turn to bring it into view instead
			drawEdgeArrow(relative > 0.0D ? right - 2 : left + 1, top + 2, bottom - 2, relative > 0.0D);
		}

		drawBarReadout(poseStack, player, centerX, bottom, targetX, targetY, targetZ);
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
	private void drawTargetMarker(int markerX, int top, int bottom) {
		RenderUtils.drawRect(markerX, top, markerX + 1, bottom, MARKER_COLOR);
		RenderUtils.drawRect(markerX - 2, top, markerX + 3, top + 1, MARKER_COLOR);
		RenderUtils.drawRect(markerX - 1, top + 1, markerX + 2, top + 2, MARKER_COLOR);
	}

	/** A triangle at one end of the strip, pointing the way to turn to bring the target into view. */
	private void drawEdgeArrow(int tipX, int top, int bottom, boolean pointingRight) {
		final int halfHeight = (bottom - top) / 2;
		for (int column = 0; column < halfHeight; column++) {
			final int x = pointingRight ? tipX - halfHeight + 1 + column : tipX + halfHeight - 1 - column;
			RenderUtils.drawRect(x, top + column, x + 1, bottom - column, MARKER_COLOR);
		}
	}

	/** The distance under the strip, and how far up or down the structure sits when that is known. */
	private void drawBarReadout(PoseStack poseStack, Player player, int centerX, int bottom, int targetX, int targetY, int targetZ) {
		String readout = String.format("%,d", StructureUtils.getHorizontalDistanceToLocation(player, targetX, targetZ));
		if (targetY != ExplorersCompassItem.UNKNOWN_Y) {
			final int climb = targetY - player.getBlockY();
			if (climb != 0) {
				readout += (climb > 0 ? "  ↑ " : "  ↓ ") + Math.abs(climb);
			}
		}
		mc.font.drawShadow(poseStack, readout, centerX - mc.font.width(readout) / 2.0F, bottom + 3, MARKER_TEXT_COLOR);
	}

}