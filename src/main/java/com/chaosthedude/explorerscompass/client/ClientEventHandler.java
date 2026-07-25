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
		final double bearing = (Math.toDegrees(Math.atan2(x + 0.5D - player.getX(), player.getZ() - (z + 0.5D))) + 360.0D) % 360.0D;
		return WIND_POINTS[(int) Math.round(bearing / 45.0D) % 8];
	}

}