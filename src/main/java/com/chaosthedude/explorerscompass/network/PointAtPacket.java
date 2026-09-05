package com.chaosthedude.explorerscompass.network;

import java.util.function.Supplier;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.items.ExplorersCompassItem;
import com.chaosthedude.explorerscompass.util.ItemUtils;
import com.chaosthedude.explorerscompass.util.SearchTarget;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

/**
 * Asks for the held compass to be pointed at a place the client has on record: one of the waypoints
 * it keeps for every location the compass has found. The place is named outright rather than by an
 * index into anything the server holds, since the waypoints live on the client alone.
 *
 * <p>Nothing about this reaches past the player's own compass, so the only thing checked on arrival
 * is that the coordinates lie somewhere a world can have at all.
 */
public class PointAtPacket {

	/** The edge of the world, past which no coordinate means anything. */
	private static final int MAX_COORDINATE = 30_000_000;

	private final SearchTarget searchTarget;
	private final ResourceLocation targetKey;
	private final int x;
	private final int y;
	private final int z;
	/** The dimension the place lies in, or null for a record from before that was written down. */
	private final ResourceLocation dimension;

	public PointAtPacket(SearchTarget searchTarget, ResourceLocation targetKey, int x, int y, int z, ResourceLocation dimension) {
		this.searchTarget = searchTarget;
		this.targetKey = targetKey;
		this.x = x;
		this.y = y;
		this.z = z;
		this.dimension = dimension;
	}

	public PointAtPacket(FriendlyByteBuf buf) {
		searchTarget = SearchTarget.fromID(buf.readVarInt());
		targetKey = buf.readResourceLocation();
		x = buf.readInt();
		y = buf.readInt();
		z = buf.readInt();
		dimension = buf.readBoolean() ? buf.readResourceLocation() : null;
	}

	public void toBytes(FriendlyByteBuf buf) {
		buf.writeVarInt(searchTarget.getID());
		buf.writeResourceLocation(targetKey);
		buf.writeInt(x);
		buf.writeInt(y);
		buf.writeInt(z);
		buf.writeBoolean(dimension != null);
		if (dimension != null) {
			buf.writeResourceLocation(dimension);
		}
	}

	public void handle(Supplier<NetworkEvent.Context> ctx) {
		ctx.get().enqueueWork(() -> {
			final ServerPlayer player = ctx.get().getSender();
			if (player == null) {
				return;
			}

			final ItemStack stack = ItemUtils.getHeldItem(player, ExplorersCompass.explorersCompass);
			if (stack.isEmpty()) {
				return;
			}
			if (Math.abs(x) > MAX_COORDINATE || Math.abs(z) > MAX_COORDINATE) {
				ExplorersCompass.LOGGER.warn("Ignoring a request from " + player.getDisplayName().getString() + " to point a compass at " + x + ", " + z + ": outside any world");
				return;
			}

			final ExplorersCompassItem explorersCompass = (ExplorersCompassItem) stack.getItem();
			explorersCompass.pointAt(stack, searchTarget, targetKey, x, y, z, dimension);
		});
		ctx.get().setPacketHandled(true);
	}

}
