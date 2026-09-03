package com.chaosthedude.explorerscompass.network;

import java.util.Optional;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.items.ExplorersCompassItem;
import com.chaosthedude.explorerscompass.util.ItemUtils;
import com.chaosthedude.explorerscompass.util.SearchTarget;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Asks for the held compass to be pointed at a place the client has on record: one of the waypoints
 * it keeps for every location the compass has found. The place is named outright rather than by an
 * index into anything the server holds, since the waypoints live on the client alone.
 *
 * <p>Nothing about this reaches past the player's own compass, so the only thing checked on arrival
 * is that the coordinates lie somewhere a world can have at all.
 */
public record PointAtPacket(SearchTarget searchTarget, ResourceLocation targetKey, int x, int y, int z, Optional<ResourceLocation> dimension) implements CustomPacketPayload {

	/** The edge of the world, past which no coordinate means anything. */
	private static final int MAX_COORDINATE = 30_000_000;

	public static final CustomPacketPayload.Type<PointAtPacket> TYPE = new CustomPacketPayload.Type<PointAtPacket>(ResourceLocation.fromNamespaceAndPath(ExplorersCompass.MODID, "point_at"));

	public static final StreamCodec<RegistryFriendlyByteBuf, PointAtPacket> STREAM_CODEC = StreamCodec.composite(
			SearchTarget.STREAM_CODEC, PointAtPacket::searchTarget,
			ResourceLocation.STREAM_CODEC, PointAtPacket::targetKey,
			ByteBufCodecs.INT, PointAtPacket::x,
			ByteBufCodecs.INT, PointAtPacket::y,
			ByteBufCodecs.INT, PointAtPacket::z,
			ByteBufCodecs.optional(ResourceLocation.STREAM_CODEC), PointAtPacket::dimension,
			PointAtPacket::new);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public static void handle(PointAtPacket packet, IPayloadContext ctx) {
		if (!(ctx.player() instanceof ServerPlayer player)) {
			return;
		}

		final ItemStack stack = ItemUtils.getHeldItem(player, ExplorersCompass.explorersCompass);
		if (stack.isEmpty()) {
			return;
		}
		if (Math.abs(packet.x()) > MAX_COORDINATE || Math.abs(packet.z()) > MAX_COORDINATE) {
			ExplorersCompass.LOGGER.warn("Ignoring a request from " + player.getDisplayName().getString() + " to point a compass at " + packet.x() + ", " + packet.z() + ": outside any world");
			return;
		}

		final ExplorersCompassItem explorersCompass = (ExplorersCompassItem) stack.getItem();
		explorersCompass.pointAt(stack, packet.searchTarget(), packet.targetKey(), packet.x(), packet.y(), packet.z(), packet.dimension().orElse(null));
	}

}
