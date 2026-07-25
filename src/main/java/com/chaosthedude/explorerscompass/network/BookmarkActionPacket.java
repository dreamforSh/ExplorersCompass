package com.chaosthedude.explorerscompass.network;

import java.util.function.Supplier;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.items.ExplorersCompassItem;
import com.chaosthedude.explorerscompass.util.ItemUtils;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

/** Acts on the locations the compass has remembered. */
public class BookmarkActionPacket {

	public enum Action {
		/** Points the compass back at a remembered location. */
		SELECT,
		/** Forgets a single remembered location. */
		REMOVE,
		/** Forgets all of them. */
		CLEAR
	}

	private Action action;
	private int index;

	public BookmarkActionPacket(Action action, int index) {
		this.action = action;
		this.index = index;
	}

	public BookmarkActionPacket(FriendlyByteBuf buf) {
		action = buf.readEnum(Action.class);
		index = buf.readVarInt();
	}

	public void toBytes(FriendlyByteBuf buf) {
		buf.writeEnum(action);
		buf.writeVarInt(index);
	}

	public void handle(Supplier<NetworkEvent.Context> ctx) {
		ctx.get().enqueueWork(() -> {
			final ServerPlayer player = ctx.get().getSender();
			if (player == null) {
				return;
			}

			final ItemStack stack = ItemUtils.getHeldItem(player, ExplorersCompass.explorersCompass);
			if (stack.isEmpty()) {
				return;
			}

			// The index arrives over the network, so every one of these bounds-checks it
			final ExplorersCompassItem explorersCompass = (ExplorersCompassItem) stack.getItem();
			if (action == Action.SELECT) {
				explorersCompass.selectBookmark(stack, index);
			} else if (action == Action.REMOVE) {
				explorersCompass.removeBookmark(stack, index);
			} else {
				explorersCompass.clearBookmarks(stack);
			}
		});
		ctx.get().setPacketHandled(true);
	}

}
