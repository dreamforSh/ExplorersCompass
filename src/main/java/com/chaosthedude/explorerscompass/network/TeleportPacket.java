package com.chaosthedude.explorerscompass.network;

import java.util.function.Supplier;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.chaosthedude.explorerscompass.items.ExplorersCompassItem;
import com.chaosthedude.explorerscompass.util.CompassState;
import com.chaosthedude.explorerscompass.util.ItemUtils;
import com.chaosthedude.explorerscompass.util.PlayerUtils;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
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
						final int x = explorersCompass.getFoundStructureX(stack);
						final int z = explorersCompass.getFoundStructureZ(stack);
						final int y = findValidTeleportHeight(player.level, x, z);

						player.stopRiding();
						player.connection.teleport(x, y, z, player.getYRot(), player.getXRot());

						if (!player.isFallFlying()) {
							player.setDeltaMovement(player.getDeltaMovement().x(), 0, player.getDeltaMovement().z());
							player.setOnGround(true);
						}
					}
				} else {
					ExplorersCompass.LOGGER.warn("Player " + player.getDisplayName().getString() + " tried to teleport but does not have permission.");
				}
			}
		});
		ctx.get().setPacketHandled(true);
	}
	
	private int findValidTeleportHeight(Level level, int x, int z) {
		final int seaLevel = level.getSeaLevel();
		final int minY = level.getMinBuildHeight();
		final int maxY = level.getMaxBuildHeight() - 1;

		// Search outwards from sea level, but stop at the build limits: a column without a valid
		// position anywhere in it (a structure over the void, for example) would otherwise loop
		// forever.
		for (int offset = 0; seaLevel + offset <= maxY || seaLevel - offset >= minY; offset++) {
			int upY = seaLevel + offset;
			if (upY <= maxY && isValidTeleportPosition(level, new BlockPos(x, upY, z))) {
				return upY;
			}

			int downY = seaLevel - offset;
			if (downY >= minY && isValidTeleportPosition(level, new BlockPos(x, downY, z))) {
				return downY;
			}
		}

		return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
	}
	
	private boolean isValidTeleportPosition(Level level, BlockPos pos) {
		return !level.isOutsideBuildHeight(pos) && isFree(level, pos) && isFree(level, pos.above()) && !isFree(level, pos.below());
	}
	
	private boolean isFree(Level level, BlockPos pos) {
		return level.getBlockState(pos).isAir() || level.getBlockState(pos).is(BlockTags.FIRE) || level.getBlockState(pos).getMaterial().isLiquid() || level.getBlockState(pos).getMaterial().isReplaceable();
	}

}
