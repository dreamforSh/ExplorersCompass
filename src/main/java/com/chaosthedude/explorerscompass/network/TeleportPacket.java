package com.chaosthedude.explorerscompass.network;

import java.util.function.Supplier;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.chaosthedude.explorerscompass.items.ExplorersCompassItem;
import com.chaosthedude.explorerscompass.util.CompassState;
import com.chaosthedude.explorerscompass.util.ItemUtils;
import com.chaosthedude.explorerscompass.util.PlayerUtils;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.network.NetworkEvent;

public class TeleportPacket {

	public TeleportPacket() {}

	public TeleportPacket(FriendlyByteBuf buf) {}

	public void fromBytes(FriendlyByteBuf buf) {}

	public void toBytes(FriendlyByteBuf buf) {}

	public void handle(Supplier<NetworkEvent.Context> ctx) {
		ctx.get().enqueueWork(() -> {
			final ServerPlayer player = ctx.get().getSender();
			if (player == null) {
				return;
			}

			final ItemStack stack = ItemUtils.getHeldItem(player, ExplorersCompass.explorersCompass);
			if (!stack.isEmpty()) {
				final ExplorersCompassItem explorersCompass = (ExplorersCompassItem) stack.getItem();
				if (ConfigHandler.GENERAL.allowTeleport.get() && PlayerUtils.canTeleport(player.getServer(), player)) {
					if (explorersCompass.getState(stack) == CompassState.FOUND) {
						final ResourceLocation foundDimension = explorersCompass.getFoundDimension(stack);
						final ServerLevel targetLevel = resolveTargetLevel(player, foundDimension);
						if (targetLevel == null) {
							player.displayClientMessage(Component.translatable("string.explorerscompass.wrongDimension"), true);
							ExplorersCompass.LOGGER.warn("Could not teleport " + player.getDisplayName().getString()
									+ " to " + foundDimension + ": that dimension is not loaded");
							return;
						}

						teleportWhenChunkIsReady(player, targetLevel, explorersCompass.getFoundStructureX(stack),
								explorersCompass.getFoundStructureY(stack), explorersCompass.getFoundStructureZ(stack));
					}
				} else {
					ExplorersCompass.LOGGER.warn("Player " + player.getDisplayName().getString() + " tried to teleport but does not have permission.");
				}
			}
		});
		ctx.get().setPacketHandled(true);
	}
	
	/**
	 * The level the compass coordinates belong to. A compass from before the dimension was recorded
	 * has no way to tell, and keeps the old behavior of landing wherever the player currently is.
	 */
	private static ServerLevel resolveTargetLevel(ServerPlayer player, ResourceLocation foundDimension) {
		if (foundDimension == null) {
			return player.getLevel();
		}
		return player.getServer().getLevel(ResourceKey.create(Registry.DIMENSION_REGISTRY, foundDimension));
	}

	/**
	 * Requests the target chunk in the dimension the structure was found in, and teleports once it
	 * is ready. The search never generated this chunk (it stops at structure starts), so loading it
	 * here usually means generating it.
	 *
	 * <p>The destination is held with a teleport ticket before generation is asked for. Custom
	 * dimensions have no spawn chunks, so without that ticket the column unloads as soon as
	 * generation finishes; the player then falls into the void and respawns in the overworld. The
	 * teleport itself goes through {@link ServerPlayer#teleportTo(ServerLevel, double, double,
	 * double, float, float)}, which is what {@code /tp} uses in this version: it changes dimension
	 * when the structure is not in the world the player is standing in.
	 */
	private void teleportWhenChunkIsReady(ServerPlayer player, ServerLevel level, int x, int structureY, int z) {
		final ChunkPos chunkPos = new ChunkPos(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z));
		level.getChunkSource().addRegionTicket(TicketType.POST_TELEPORT, chunkPos, 1, player.getId());
		level.getChunkSource().getChunkFuture(chunkPos.x, chunkPos.z, ChunkStatus.FULL, true).thenAcceptAsync((either) -> {
			if (either.left().isEmpty()) {
				ExplorersCompass.LOGGER.warn("Could not load the chunk at " + x + ", " + z + " in "
						+ level.dimension().location() + " to teleport " + player.getDisplayName().getString());
				return;
			}
			// Logged out or already gone: do not pull a player back in. Changing dimension while the
			// chunk generated is not a reason to abort — the destination is this level, not wherever
			// they happen to be now.
			if (player.hasDisconnected() || player.isRemoved() || !player.isAlive()) {
				return;
			}

			final int y = findValidTeleportHeight(level, x, structureY, z);
			player.teleportTo(level, x + 0.5D, y, z + 0.5D, player.getYRot(), player.getXRot());

			if (!player.isFallFlying()) {
				player.setDeltaMovement(player.getDeltaMovement().x(), 0, player.getDeltaMovement().z());
				player.setOnGround(true);
			}
		}, player.getServer());
	}

	/**
	 * The Y level to land at: the safe position closest to the structure's height when the compass
	 * recorded one, and to sea level otherwise. The column was just loaded, so every read is served
	 * from memory, and each block of it is read at most once.
	 */
	private int findValidTeleportHeight(Level level, int x, int structureY, int z) {
		final int minY = level.getMinBuildHeight();
		final int maxY = level.getMaxBuildHeight() - 1;
		final int scanCenter = structureY != ExplorersCompassItem.UNKNOWN_Y ? Mth.clamp(structureY, minY, maxY) : level.getSeaLevel();
		final BlockState[] states = new BlockState[maxY - minY + 1];

		// Search outwards from the center, but stop at the build limits: a column without a valid
		// position anywhere in it (a structure over the void, for example) would otherwise loop
		// forever.
		for (int offset = 0; scanCenter + offset <= maxY || scanCenter - offset >= minY; offset++) {
			int upY = scanCenter + offset;
			if (upY <= maxY && isValidTeleportPosition(level, x, z, upY, states)) {
				return upY;
			}

			int downY = scanCenter - offset;
			if (downY >= minY && isValidTeleportPosition(level, x, z, downY, states)) {
				return downY;
			}
		}

		return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
	}

	private boolean isValidTeleportPosition(Level level, int x, int z, int y, BlockState[] states) {
		return isSafe(stateAt(level, x, z, y, states)) && isSafe(stateAt(level, x, z, y + 1, states)) && !isPassable(stateAt(level, x, z, y - 1, states));
	}

	private BlockState stateAt(Level level, int x, int z, int y, BlockState[] states) {
		final int index = y - level.getMinBuildHeight();
		if (index < 0 || index >= states.length) {
			// Outside the build height everything reads as air
			return Blocks.AIR.defaultBlockState();
		}
		if (states[index] == null) {
			states[index] = level.getBlockState(new BlockPos(x, y, z));
		}
		return states[index];
	}

	/** Whether the block puts up no physical barrier, so a player could occupy or sink through it. */
	private static boolean isPassable(BlockState state) {
		return state.isAir() || state.getMaterial().isLiquid() || state.getMaterial().isReplaceable();
	}

	/** Passable and also harmless: fire and lava are passable, but must not be teleported into. */
	private static boolean isSafe(BlockState state) {
		if (state.is(BlockTags.FIRE) || state.getFluidState().is(FluidTags.LAVA)) {
			return false;
		}
		return isPassable(state);
	}

}
