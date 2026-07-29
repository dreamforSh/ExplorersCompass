package com.chaosthedude.explorerscompass.network;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.items.ExplorersCompassItem;
import com.chaosthedude.explorerscompass.util.ItemUtils;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Stops the search the compass is running and takes the structure it was aimed at back off it, so
 * that it can be pointed at something else without waiting for the current search to end.
 */
public record CancelSearchPacket() implements CustomPacketPayload {

	public static final CancelSearchPacket INSTANCE = new CancelSearchPacket();

	public static final CustomPacketPayload.Type<CancelSearchPacket> TYPE = new CustomPacketPayload.Type<CancelSearchPacket>(ResourceLocation.fromNamespaceAndPath(ExplorersCompass.MODID, "cancel_search"));

	public static final StreamCodec<RegistryFriendlyByteBuf, CancelSearchPacket> STREAM_CODEC = StreamCodec.unit(INSTANCE);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public static void handle(CancelSearchPacket packet, IPayloadContext ctx) {
		if (!(ctx.player() instanceof ServerPlayer player)) {
			return;
		}

		final ItemStack stack = ItemUtils.getHeldItem(player, ExplorersCompass.explorersCompass);
		if (!stack.isEmpty()) {
			((ExplorersCompassItem) stack.getItem()).cancelSearch(player.serverLevel(), player, stack);
		}
	}

}
