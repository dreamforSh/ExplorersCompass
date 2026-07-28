package com.chaosthedude.explorerscompass.network;

import java.util.function.Supplier;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.items.ExplorersCompassItem;
import com.chaosthedude.explorerscompass.util.ItemUtils;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

/** Asks for another instance of whatever the compass has already located. */
public class CompassSearchForNextPacket {

	private int x;
	private int y;
	private int z;

	public CompassSearchForNextPacket(BlockPos pos) {
		this.x = pos.getX();
		this.y = pos.getY();
		this.z = pos.getZ();
	}

	public CompassSearchForNextPacket(FriendlyByteBuf buf) {
		x = buf.readInt();
		y = buf.readInt();
		z = buf.readInt();
	}

	public void toBytes(FriendlyByteBuf buf) {
		buf.writeInt(x);
		buf.writeInt(y);
		buf.writeInt(z);
	}

	public void handle(Supplier<NetworkEvent.Context> ctx) {
		ctx.get().enqueueWork(() -> {
			final ServerPlayer player = ctx.get().getSender();
			if (player == null) {
				return;
			}

			final ItemStack stack = ItemUtils.getHeldItem(player, ExplorersCompass.explorersCompass);
			if (!stack.isEmpty()) {
				final ExplorersCompassItem explorersCompass = (ExplorersCompassItem) stack.getItem();
				try {
					explorersCompass.searchForNext(player.getLevel(), player, new BlockPos(x, y, z), stack);
				} catch (Throwable t) {
					// This runs on the server thread, so an exception here would take down the server
					ExplorersCompass.LOGGER.error("Failed to start a search for a further instance", t);
					explorersCompass.setNotFound(stack, 0, 0);
				}
			}
		});
		ctx.get().setPacketHandled(true);
	}

}
