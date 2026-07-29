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

/** Forgets the locations the compass has collected, so that searching finds the closest one again. */
public record ClearCachePacket() implements CustomPacketPayload {

	public static final ClearCachePacket INSTANCE = new ClearCachePacket();

	public static final CustomPacketPayload.Type<ClearCachePacket> TYPE = new CustomPacketPayload.Type<ClearCachePacket>(ResourceLocation.fromNamespaceAndPath(ExplorersCompass.MODID, "clear_cache"));

	public static final StreamCodec<RegistryFriendlyByteBuf, ClearCachePacket> STREAM_CODEC = StreamCodec.unit(INSTANCE);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public static void handle(ClearCachePacket packet, IPayloadContext ctx) {
		if (!(ctx.player() instanceof ServerPlayer player)) {
			return;
		}

		final ItemStack stack = ItemUtils.getHeldItem(player, ExplorersCompass.explorersCompass);
		if (!stack.isEmpty()) {
			((ExplorersCompassItem) stack.getItem()).clearPrevPos(stack);
		}
	}

}
