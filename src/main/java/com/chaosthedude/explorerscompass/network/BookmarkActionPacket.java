package com.chaosthedude.explorerscompass.network;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.items.ExplorersCompassItem;
import com.chaosthedude.explorerscompass.util.ItemUtils;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Acts on the locations the compass has remembered. */
public record BookmarkActionPacket(Action action, int index) implements CustomPacketPayload {

	public enum Action {
		/** Points the compass back at a remembered location. */
		SELECT,
		/** Forgets a single remembered location. */
		REMOVE,
		/** Forgets all of them. */
		CLEAR;

		/**
		 * An ordinal that names no action is read as {@link #CLEAR}, the one action that ignores the
		 * index, since a payload that fails to decode drops the connection.
		 */
		private static Action fromOrdinal(int ordinal) {
			final Action[] actions = values();
			return ordinal >= 0 && ordinal < actions.length ? actions[ordinal] : CLEAR;
		}
	}

	public static final CustomPacketPayload.Type<BookmarkActionPacket> TYPE = new CustomPacketPayload.Type<BookmarkActionPacket>(ResourceLocation.fromNamespaceAndPath(ExplorersCompass.MODID, "bookmark_action"));

	private static final StreamCodec<ByteBuf, Action> ACTION_STREAM_CODEC = ByteBufCodecs.VAR_INT.map(Action::fromOrdinal, Action::ordinal);

	public static final StreamCodec<RegistryFriendlyByteBuf, BookmarkActionPacket> STREAM_CODEC = StreamCodec.composite(
			ACTION_STREAM_CODEC, BookmarkActionPacket::action,
			ByteBufCodecs.VAR_INT, BookmarkActionPacket::index,
			BookmarkActionPacket::new);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public static void handle(BookmarkActionPacket packet, IPayloadContext ctx) {
		if (!(ctx.player() instanceof ServerPlayer player)) {
			return;
		}

		final ItemStack stack = ItemUtils.getHeldItem(player, ExplorersCompass.explorersCompass);
		if (stack.isEmpty()) {
			return;
		}

		// The index arrives over the network, so every one of these bounds-checks it
		final ExplorersCompassItem explorersCompass = (ExplorersCompassItem) stack.getItem();
		if (packet.action() == Action.SELECT) {
			explorersCompass.selectBookmark(stack, packet.index());
		} else if (packet.action() == Action.REMOVE) {
			explorersCompass.removeBookmark(stack, packet.index());
		} else {
			explorersCompass.clearBookmarks(stack);
		}
	}

}
