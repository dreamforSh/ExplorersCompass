package com.chaosthedude.explorerscompass.network;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.chaosthedude.explorerscompass.preview.StructurePreview;
import com.chaosthedude.explorerscompass.preview.StructurePreviewService;
import com.chaosthedude.explorerscompass.util.StructureUtils;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Asks what a structure looks like, so that it can be shown before a search is spent on it. */
public record StructurePreviewRequestPacket(ResourceLocation structureKey) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<StructurePreviewRequestPacket> TYPE = new CustomPacketPayload.Type<StructurePreviewRequestPacket>(ResourceLocation.fromNamespaceAndPath(ExplorersCompass.MODID, "structure_preview_request"));

	public static final StreamCodec<RegistryFriendlyByteBuf, StructurePreviewRequestPacket> STREAM_CODEC = StreamCodec.composite(
			ResourceLocation.STREAM_CODEC, StructurePreviewRequestPacket::structureKey,
			StructurePreviewRequestPacket::new);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	/**
	 * Handled on the server thread, which the context puts this on. A preview already worked out is
	 * answered right away; one still to be built is built off the server thread, and the answer goes
	 * out once it lands — see {@link StructurePreviewService}.
	 */
	public static void handle(StructurePreviewRequestPacket packet, IPayloadContext ctx) {
		if (!(ctx.player() instanceof ServerPlayer player)) {
			return;
		}

		final ResourceLocation structureKey = packet.structureKey();
		if (!ConfigHandler.GENERAL.allowStructurePreview.get().booleanValue()) {
			answer(player, structureKey, null);
			return;
		}

		// Dropped rather than answered: the client asks again shortly, and answering would mean doing
		// the work this is here to rate limit
		if (StructurePreviewService.isOnCooldown(player)) {
			return;
		}
		StructurePreviewService.recordRequest(player);

		// Only what the compass may search for may be previewed. A blacklisted structure is one the
		// server has decided its players are not to be shown, and a modified client must not be able
		// to ask about it by naming it directly.
		if (!StructureUtils.getAllowedStructureKeys(player.serverLevel()).contains(structureKey)) {
			answer(player, structureKey, null);
			return;
		}

		try {
			StructurePreviewService.request(player, structureKey, (waiter, preview) -> answer(waiter, structureKey, preview));
		} catch (Throwable t) {
			// This runs on the server thread, so an exception here would take down the server
			ExplorersCompass.LOGGER.error("Failed to request a preview of " + structureKey, t);
		}
	}

	/**
	 * Answers the player, with nothing when there is nothing to show, so no request goes unanswered.
	 * A preview shown one cell to one block is larger than a single packet may be, so it goes out as
	 * the run of packets that carries it.
	 */
	private static void answer(ServerPlayer player, ResourceLocation structureKey, StructurePreview preview) {
		for (StructurePreviewPacket packet : StructurePreviewPacket.createFor(structureKey, preview)) {
			PacketDistributor.sendToPlayer(player, packet);
		}
	}

}
