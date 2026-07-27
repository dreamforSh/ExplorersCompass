package com.chaosthedude.explorerscompass.network;

import java.util.function.Supplier;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.items.ExplorersCompassItem;
import com.chaosthedude.explorerscompass.util.ItemUtils;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

/**
 * Stops the search the compass is running and takes the structure it was aimed at back off it, so
 * that it can be pointed at something else without waiting for the current search to end.
 */
public class CancelSearchPacket {

	public CancelSearchPacket() {}

	public CancelSearchPacket(FriendlyByteBuf buf) {}

	public void toBytes(FriendlyByteBuf buf) {}

	public void handle(Supplier<NetworkEvent.Context> ctx) {
		ctx.get().enqueueWork(() -> {
			final ServerPlayer player = ctx.get().getSender();
			if (player == null) {
				return;
			}

			final ItemStack stack = ItemUtils.getHeldItem(player, ExplorersCompass.explorersCompass);
			if (!stack.isEmpty()) {
				((ExplorersCompassItem) stack.getItem()).cancelSearch(player.getLevel(), player, stack);
			}
		});
		ctx.get().setPacketHandled(true);
	}

}
