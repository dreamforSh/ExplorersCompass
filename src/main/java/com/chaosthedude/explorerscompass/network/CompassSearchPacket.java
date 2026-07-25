package com.chaosthedude.explorerscompass.network;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.items.ExplorersCompassItem;
import com.chaosthedude.explorerscompass.util.ItemUtils;

import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

/**
 * Asks for a search: either for the nearest of a set of structures, or for the nearest member of
 * a group.
 */
public class CompassSearchPacket {

	// Far more structures than any real selection holds; a count past this is a malformed packet
	private static final int MAX_STRUCTURE_KEYS = 4096;

	private boolean isGroup;
	private ResourceLocation groupKey;
	private List<ResourceLocation> structureKeys;
	private int x;
	private int y;
	private int z;

	private CompassSearchPacket(boolean isGroup, ResourceLocation groupKey, List<ResourceLocation> structureKeys, BlockPos pos) {
		this.isGroup = isGroup;
		this.groupKey = groupKey;
		this.structureKeys = structureKeys;

		x = pos.getX();
		y = pos.getY();
		z = pos.getZ();
	}

	/** A search for the nearest of the given structures. */
	public static CompassSearchPacket forStructures(List<ResourceLocation> structureKeys, BlockPos pos) {
		return new CompassSearchPacket(false, null, structureKeys, pos);
	}

	/** A search for the nearest member of the given group. */
	public static CompassSearchPacket forGroup(ResourceLocation groupKey, BlockPos pos) {
		return new CompassSearchPacket(true, groupKey, List.of(), pos);
	}

	public CompassSearchPacket(FriendlyByteBuf buf) {
		isGroup = buf.readBoolean();
		if (isGroup) {
			groupKey = buf.readResourceLocation();
			structureKeys = List.of();
		} else {
			int numKeys = buf.readVarInt();
			if (numKeys < 1 || numKeys > MAX_STRUCTURE_KEYS) {
				throw new DecoderException("Search requested for " + numKeys + " structures");
			}
			structureKeys = new ArrayList<ResourceLocation>(numKeys);
			for (int i = 0; i < numKeys; i++) {
				structureKeys.add(buf.readResourceLocation());
			}
		}

		x = buf.readInt();
		y = buf.readInt();
		z = buf.readInt();
	}

	public void toBytes(FriendlyByteBuf buf) {
		buf.writeBoolean(isGroup);
		if (isGroup) {
			buf.writeResourceLocation(groupKey);
		} else {
			buf.writeVarInt(structureKeys.size());
			for (ResourceLocation structureKey : structureKeys) {
				buf.writeResourceLocation(structureKey);
			}
		}

		buf.writeInt(x);
		buf.writeInt(y);
		buf.writeInt(z);
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
				try {
					if (isGroup) {
						explorersCompass.searchForGroup(player.getLevel(), player, groupKey, new BlockPos(x, y, z), stack);
					} else {
						explorersCompass.searchForStructures(player.getLevel(), player, structureKeys, new BlockPos(x, y, z), stack);
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
