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

/** Asks for another instance of whatever the compass has already located. */
public record CompassSearchForNextPacket() implements CustomPacketPayload {

	public static final CompassSearchForNextPacket INSTANCE = new CompassSearchForNextPacket();

	public static final CustomPacketPayload.Type<CompassSearchForNextPacket> TYPE = new CustomPacketPayload.Type<CompassSearchForNextPacket>(ResourceLocation.fromNamespaceAndPath(ExplorersCompass.MODID, "search_for_next"));

	public static final StreamCodec<RegistryFriendlyByteBuf, CompassSearchForNextPacket> STREAM_CODEC = StreamCodec.unit(INSTANCE);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public static void handle(CompassSearchForNextPacket packet, IPayloadContext ctx) {
		if (!(ctx.player() instanceof ServerPlayer player)) {
			return;
		}

		final ItemStack stack = ItemUtils.getHeldItem(player, ExplorersCompass.explorersCompass);
		if (!stack.isEmpty()) {
			final ExplorersCompassItem explorersCompass = (ExplorersCompassItem) stack.getItem();
			try {
				// Where the search starts is taken from the player rather than from the packet, so that
				// a modified client cannot search around coordinates it is nowhere near
				explorersCompass.searchForNext(player.serverLevel(), player, player.blockPosition(), stack);
			} catch (Throwable t) {
				// This runs on the server thread, so an exception here would take down the server
				ExplorersCompass.LOGGER.error("Failed to start a search for a further instance", t);
				explorersCompass.setNotFound(stack, 0, 0);
			}
		}
	}

}
