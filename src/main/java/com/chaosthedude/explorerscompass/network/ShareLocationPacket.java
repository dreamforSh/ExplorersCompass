package com.chaosthedude.explorerscompass.network;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.items.ExplorersCompassItem;
import com.chaosthedude.explorerscompass.util.ItemUtils;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Announces a located structure to the other players on the server. */
public record ShareLocationPacket(int bookmarkIndex) implements CustomPacketPayload {

	/** Shares the location the compass is currently pointing at rather than a remembered one. */
	public static final int CURRENT_TARGET = -1;

	public static final CustomPacketPayload.Type<ShareLocationPacket> TYPE = new CustomPacketPayload.Type<ShareLocationPacket>(ResourceLocation.fromNamespaceAndPath(ExplorersCompass.MODID, "share_location"));

	public static final StreamCodec<RegistryFriendlyByteBuf, ShareLocationPacket> STREAM_CODEC = StreamCodec.composite(
			// Signed, since CURRENT_TARGET is negative
			ByteBufCodecs.INT, ShareLocationPacket::bookmarkIndex,
			ShareLocationPacket::new);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public static void handle(ShareLocationPacket packet, IPayloadContext ctx) {
		if (!(ctx.player() instanceof ServerPlayer player)) {
			return;
		}

		final ItemStack stack = ItemUtils.getHeldItem(player, ExplorersCompass.explorersCompass);
		if (!stack.isEmpty()) {
			((ExplorersCompassItem) stack.getItem()).shareLocation(player, stack, packet.bookmarkIndex());
		}
	}

}
