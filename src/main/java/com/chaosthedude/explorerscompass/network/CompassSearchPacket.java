package com.chaosthedude.explorerscompass.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.items.ExplorersCompassItem;
import com.chaosthedude.explorerscompass.util.ItemUtils;
import com.chaosthedude.explorerscompass.util.SearchTarget;

import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Asks for a search: either for the nearest of a set of structures or biomes, or for the nearest
 * member of a group of either.
 */
public record CompassSearchPacket(SearchTarget searchTarget, boolean isGroup, Optional<ResourceLocation> groupKey, List<ResourceLocation> targetKeys) implements CustomPacketPayload {

	/**
	 * Far more keys than any real selection holds; a count past this is a malformed packet.
	 *
	 * <p>This used to be 4096. A payload travelling to the server may not exceed roughly 32 KiB, and
	 * that many resource locations comfortably passes it, so a large enough selection would have
	 * disconnected the player rather than searching. The compass only ever remembers
	 * {@code MAX_PERSISTED_TARGET_KEYS} of them anyway, so nothing beyond this was being carried
	 * forward even when it did fit.
	 */
	private static final int MAX_TARGET_KEYS = 512;

	public static final CustomPacketPayload.Type<CompassSearchPacket> TYPE = new CustomPacketPayload.Type<CompassSearchPacket>(ResourceLocation.fromNamespaceAndPath(ExplorersCompass.MODID, "compass_search"));

	/**
	 * Written out by hand rather than composed, since which of the two payloads follows depends on
	 * the flag before it.
	 */
	public static final StreamCodec<RegistryFriendlyByteBuf, CompassSearchPacket> STREAM_CODEC = StreamCodec.of(
			(buf, packet) -> {
				SearchTarget.STREAM_CODEC.encode(buf, packet.searchTarget());
				buf.writeBoolean(packet.isGroup());
				if (packet.isGroup()) {
					ResourceLocation.STREAM_CODEC.encode(buf, packet.groupKey().orElseThrow());
				} else {
					ByteBufCodecs.VAR_INT.encode(buf, packet.targetKeys().size());
					for (ResourceLocation targetKey : packet.targetKeys()) {
						ResourceLocation.STREAM_CODEC.encode(buf, targetKey);
					}
				}
			},
			buf -> {
				final SearchTarget searchTarget = SearchTarget.STREAM_CODEC.decode(buf);
				final boolean isGroup = buf.readBoolean();
				if (isGroup) {
					return new CompassSearchPacket(searchTarget, true, Optional.of(ResourceLocation.STREAM_CODEC.decode(buf)), List.of());
				}

				final int numKeys = ByteBufCodecs.VAR_INT.decode(buf);
				if (numKeys < 1 || numKeys > MAX_TARGET_KEYS) {
					throw new DecoderException("Search requested for " + numKeys + " targets");
				}
				final List<ResourceLocation> targetKeys = new ArrayList<ResourceLocation>(numKeys);
				for (int i = 0; i < numKeys; i++) {
					targetKeys.add(ResourceLocation.STREAM_CODEC.decode(buf));
				}
				return new CompassSearchPacket(searchTarget, false, Optional.empty(), targetKeys);
			});

	/** A search for the nearest of the given structures or biomes. */
	public static CompassSearchPacket forTargets(SearchTarget searchTarget, List<ResourceLocation> targetKeys) {
		// Trimmed here rather than at the far end, since a request that would not fit is refused on
		// arrival and the player would be disconnected instead of told nothing was found
		if (targetKeys.size() > MAX_TARGET_KEYS) {
			ExplorersCompass.LOGGER.warn("Searching for only the first " + MAX_TARGET_KEYS + " of " + targetKeys.size() + " selected targets");
			targetKeys = targetKeys.subList(0, MAX_TARGET_KEYS);
		}
		return new CompassSearchPacket(searchTarget, false, Optional.empty(), List.copyOf(targetKeys));
	}

	/** A search for the nearest member of the given group. */
	public static CompassSearchPacket forGroup(SearchTarget searchTarget, ResourceLocation groupKey) {
		return new CompassSearchPacket(searchTarget, true, Optional.of(groupKey), List.of());
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public static void handle(CompassSearchPacket packet, IPayloadContext ctx) {
		if (!(ctx.player() instanceof ServerPlayer player)) {
			return;
		}

		final ItemStack stack = ItemUtils.getHeldItem(player, ExplorersCompass.explorersCompass);
		if (!stack.isEmpty()) {
			final ExplorersCompassItem explorersCompass = (ExplorersCompassItem) stack.getItem();
			// Where the search starts is taken from the player rather than from the packet. A client
			// has nothing to say about it that the server does not already know, and one that has been
			// modified could otherwise search around any coordinates it likes.
			final BlockPos startPos = player.blockPosition();
			try {
				if (packet.isGroup()) {
					explorersCompass.searchForGroup(player.serverLevel(), player, packet.searchTarget(), packet.groupKey().orElseThrow(), startPos, stack);
				} else {
					explorersCompass.searchForTargets(player.serverLevel(), player, packet.searchTarget(), packet.targetKeys(), startPos, stack);
				}
			} catch (Throwable t) {
				// This runs on the server thread, so an exception here would take down the server
				ExplorersCompass.LOGGER.error("Failed to start a search", t);
				explorersCompass.setNotFound(stack, 0, 0);
			}
		}
	}

}
