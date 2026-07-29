package com.chaosthedude.explorerscompass.network;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.items.ExplorersCompassItem;
import com.chaosthedude.explorerscompass.util.ItemUtils;
import com.chaosthedude.explorerscompass.util.SearchTarget;

import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

/**
 * Asks for a search: either for the nearest of a set of structures or biomes, or for the nearest
 * member of a group of either.
 */
public class CompassSearchPacket {

	/** Far more keys than any real selection holds; a count past this is a malformed packet. */
	private static final int MAX_TARGET_KEYS = 4096;

	private SearchTarget searchTarget;
	private boolean isGroup;
	private ResourceLocation groupKey;
	private List<ResourceLocation> targetKeys;

	private CompassSearchPacket(SearchTarget searchTarget, boolean isGroup, ResourceLocation groupKey, List<ResourceLocation> targetKeys) {
		this.searchTarget = searchTarget;
		this.isGroup = isGroup;
		this.groupKey = groupKey;
		this.targetKeys = targetKeys;
	}

	/** A search for the nearest of the given structures or biomes. */
	public static CompassSearchPacket forTargets(SearchTarget searchTarget, List<ResourceLocation> targetKeys) {
		return new CompassSearchPacket(searchTarget, false, null, targetKeys);
	}

	/** A search for the nearest member of the given group. */
	public static CompassSearchPacket forGroup(SearchTarget searchTarget, ResourceLocation groupKey) {
		return new CompassSearchPacket(searchTarget, true, groupKey, List.of());
	}

	public CompassSearchPacket(FriendlyByteBuf buf) {
		searchTarget = SearchTarget.fromID(buf.readVarInt());
		isGroup = buf.readBoolean();
		if (isGroup) {
			groupKey = buf.readResourceLocation();
			targetKeys = List.of();
		} else {
			int numKeys = buf.readVarInt();
			if (numKeys < 1 || numKeys > MAX_TARGET_KEYS) {
				throw new DecoderException("Search requested for " + numKeys + " targets");
			}
			targetKeys = new ArrayList<ResourceLocation>(numKeys);
			for (int i = 0; i < numKeys; i++) {
				targetKeys.add(buf.readResourceLocation());
			}
		}
	}

	public void toBytes(FriendlyByteBuf buf) {
		buf.writeVarInt(searchTarget.getID());
		buf.writeBoolean(isGroup);
		if (isGroup) {
			buf.writeResourceLocation(groupKey);
		} else {
			buf.writeVarInt(targetKeys.size());
			for (ResourceLocation targetKey : targetKeys) {
				buf.writeResourceLocation(targetKey);
			}
		}
	}

	public void handle(Supplier<NetworkEvent.Context> ctx) {
		ctx.get().enqueueWork(() -> {
			final ServerPlayer player = ctx.get().getSender();
			if (player == null) {
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
					if (isGroup) {
						explorersCompass.searchForGroup(player.getLevel(), player, searchTarget, groupKey, startPos, stack);
					} else {
						explorersCompass.searchForTargets(player.getLevel(), player, searchTarget, targetKeys, startPos, stack);
					}
				} catch (Throwable t) {
					// This runs on the server thread, so an exception here would take down the server
					ExplorersCompass.LOGGER.error("Failed to start a search", t);
					explorersCompass.setNotFound(stack, 0, 0);
				}
			}
		});
		ctx.get().setPacketHandled(true);
	}

}
