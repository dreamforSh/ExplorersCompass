package com.chaosthedude.explorerscompass.network;

import java.util.function.Supplier;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.items.ExplorersCompassItem;
import com.chaosthedude.explorerscompass.util.ItemUtils;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

/** Announces a located structure to the other players on the server. */
public class ShareLocationPacket {

	/** Shares the location the compass is currently pointing at rather than a remembered one. */
	public static final int CURRENT_TARGET = -1;

	private int bookmarkIndex;

	public ShareLocationPacket(int bookmarkIndex) {
		this.bookmarkIndex = bookmarkIndex;
	}

	public ShareLocationPacket(FriendlyByteBuf buf) {
		bookmarkIndex = buf.readInt();
	}

	public void toBytes(FriendlyByteBuf buf) {
		buf.writeInt(bookmarkIndex);
	}

	public void handle(Supplier<NetworkEvent.Context> ctx) {
		ctx.get().enqueueWork(() -> {
			final ServerPlayer player = ctx.get().getSender();
			if (player == null) {
				return;
			}

			final ItemStack stack = ItemUtils.getHeldItem(player, ExplorersCompass.explorersCompass);
			if (!stack.isEmpty()) {
				((ExplorersCompassItem) stack.getItem()).shareLocation(player, stack, bookmarkIndex);
			}
		});
		ctx.get().setPacketHandled(true);
	}

}
