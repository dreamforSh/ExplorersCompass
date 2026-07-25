package com.chaosthedude.explorerscompass.network;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.items.ExplorersCompassItem;
import com.chaosthedude.explorerscompass.util.ItemUtils;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

public class CompassSearchPacket {

	private ResourceLocation groupKey;
	private List<ResourceLocation> structureKeys;
	private int x;
	private int y;
	private int z;

	public CompassSearchPacket() {}

	public CompassSearchPacket(ResourceLocation groupKey, List<ResourceLocation> structureKeys, BlockPos pos) {
		this.groupKey = groupKey;
		this.structureKeys = structureKeys;

		this.x = pos.getX();
		this.y = pos.getY();
		this.z = pos.getZ();
	}

	public CompassSearchPacket(FriendlyByteBuf buf) {
		groupKey = buf.readResourceLocation();
		
		structureKeys = new ArrayList<ResourceLocation>();
		int numStructures = buf.readInt();
		for (int i = 0; i < numStructures; i++) {
			structureKeys.add(buf.readResourceLocation());
		}

		x = buf.readInt();
		y = buf.readInt();
		z = buf.readInt();
	}

	public void toBytes(FriendlyByteBuf buf) {
		buf.writeResourceLocation(groupKey);
		
		buf.writeInt(structureKeys.size());
		for (ResourceLocation key : structureKeys) {
			buf.writeResourceLocation(key);
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
					explorersCompass.searchForStructure(player.getLevel(), player, groupKey, structureKeys, new BlockPos(x, y, z), stack);
				} catch (Throwable t) {
					// This runs on the server thread, so an exception here would take down the server
					ExplorersCompass.LOGGER.error("Failed to start a search for " + groupKey, t);
					explorersCompass.setNotFound(stack, 0, 0);
				}
			}
		});
		ctx.get().setPacketHandled(true);
	}

}
