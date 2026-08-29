package com.chaosthedude.explorerscompass.network;

import java.util.function.Supplier;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.chaosthedude.explorerscompass.preview.StructurePreview;
import com.chaosthedude.explorerscompass.preview.StructurePreviewService;
import com.chaosthedude.explorerscompass.util.StructureUtils;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

/** Asks what a structure looks like, so that it can be shown before a search is spent on it. */
public class StructurePreviewRequestPacket {

	private ResourceLocation structureKey;

	public StructurePreviewRequestPacket(ResourceLocation structureKey) {
		this.structureKey = structureKey;
	}

	public StructurePreviewRequestPacket(FriendlyByteBuf buf) {
		structureKey = buf.readResourceLocation();
	}

	public void toBytes(FriendlyByteBuf buf) {
		buf.writeResourceLocation(structureKey);
	}

	/**
	 * Handled on the server thread, which the context puts this on. A preview already worked out is
	 * answered right away; one still to be built is built off the server thread, and the answer goes
	 * out once it lands — see {@link StructurePreviewService}.
	 */
	public void handle(Supplier<NetworkEvent.Context> ctx) {
		ctx.get().enqueueWork(() -> {
			final ServerPlayer player = ctx.get().getSender();
			if (player == null) {
				return;
			}

			if (!ConfigHandler.GENERAL.allowStructurePreview.get()) {
				answer(player, null);
				return;
			}

			// Dropped rather than answered: the client asks again shortly, and answering would mean
			// doing the work this is here to rate limit
			if (StructurePreviewService.isOnCooldown(player)) {
				return;
			}
			StructurePreviewService.recordRequest(player);

			// Only what the compass may search for may be previewed. A blacklisted structure is one the
			// server has decided its players are not to be shown, and a modified client must not be able
			// to ask about it by naming it directly.
			if (!StructureUtils.getAllowedStructureKeys(player.serverLevel()).contains(structureKey)) {
				answer(player, null);
				return;
			}

			try {
				StructurePreviewService.request(player, structureKey, this::answer);
			} catch (Throwable t) {
				// This runs on the server thread, so an exception here would take down the server
				ExplorersCompass.LOGGER.error("Failed to request a preview of " + structureKey, t);
			}
		});
		ctx.get().setPacketHandled(true);
	}

	/**
	 * Answers the player, with nothing when there is nothing to show, so no request goes unanswered.
	 * A preview shown one cell to one block is larger than a single packet may be, so it goes out as
	 * the run of packets that carries it.
	 */
	private void answer(ServerPlayer player, StructurePreview preview) {
		for (StructurePreviewPacket packet : StructurePreviewPacket.createFor(structureKey, preview)) {
			ExplorersCompass.network.send(PacketDistributor.PLAYER.with(() -> player), packet);
		}
	}

}
