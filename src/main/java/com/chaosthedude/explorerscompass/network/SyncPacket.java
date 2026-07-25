package com.chaosthedude.explorerscompass.network;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.util.StructureUtils;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;

public class SyncPacket {

	// A custom payload may not exceed 1048576 bytes, and a large modpack has more than enough
	// structures to push a single packet past that, which disconnects the player as soon as they use
	// the compass. Send the structure list in batches that stay well below the limit instead.
	private static final int MAX_BATCH_BYTES = 32768;
	private static final int MAX_BATCH_ENTRIES = 512;

	// The batches received so far, applied all at once when the final batch arrives
	private static final List<ResourceLocation> receivedStructureKeys = new ArrayList<ResourceLocation>();
	private static final ListMultimap<ResourceLocation, ResourceLocation> receivedDimensionKeys = ArrayListMultimap.create();
	private static final Map<ResourceLocation, ResourceLocation> receivedTypeKeys = new HashMap<ResourceLocation, ResourceLocation>();

	private boolean canTeleport;
	private boolean firstBatch;
	private boolean lastBatch;
	private List<StructureEntry> entries;

	private SyncPacket(boolean canTeleport, boolean firstBatch, boolean lastBatch, List<StructureEntry> entries) {
		this.canTeleport = canTeleport;
		this.firstBatch = firstBatch;
		this.lastBatch = lastBatch;
		this.entries = entries;
	}

	public SyncPacket(FriendlyByteBuf buf) {
		canTeleport = buf.readBoolean();
		firstBatch = buf.readBoolean();
		lastBatch = buf.readBoolean();

		entries = new ArrayList<StructureEntry>();
		int numEntries = buf.readVarInt();
		for (int i = 0; i < numEntries; i++) {
			ResourceLocation structureKey = buf.readResourceLocation();
			ResourceLocation typeKey = buf.readResourceLocation();
			List<ResourceLocation> dimensionKeys = new ArrayList<ResourceLocation>();
			int numDimensions = buf.readVarInt();
			for (int j = 0; j < numDimensions; j++) {
				dimensionKeys.add(buf.readResourceLocation());
			}
			entries.add(new StructureEntry(structureKey, typeKey, dimensionKeys));
		}
	}

	public void toBytes(FriendlyByteBuf buf) {
		buf.writeBoolean(canTeleport);
		buf.writeBoolean(firstBatch);
		buf.writeBoolean(lastBatch);

		buf.writeVarInt(entries.size());
		for (StructureEntry entry : entries) {
			buf.writeResourceLocation(entry.structureKey);
			buf.writeResourceLocation(entry.typeKey);
			buf.writeVarInt(entry.dimensionKeys.size());
			for (ResourceLocation dimensionKey : entry.dimensionKeys) {
				buf.writeResourceLocation(dimensionKey);
			}
		}
	}

	/**
	 * Splits the structure list into as many packets as it takes for each one to stay below the
	 * custom payload size limit. Always returns at least one packet, so that a client is still
	 * updated when there is nothing to send. The group to structure mapping is not sent: it is the
	 * inverse of the structure to group mapping and is rebuilt by the client.
	 */
	public static List<SyncPacket> create(boolean canTeleport, List<ResourceLocation> allowedStructureKeys, ListMultimap<ResourceLocation, ResourceLocation> dimensionKeysForAllowedStructureKeys, Map<ResourceLocation, ResourceLocation> structureKeysToTypeKeys) {
		final List<SyncPacket> packets = new ArrayList<SyncPacket>();
		List<StructureEntry> batch = new ArrayList<StructureEntry>();
		int batchBytes = 0;

		for (ResourceLocation structureKey : allowedStructureKeys) {
			ResourceLocation typeKey = structureKeysToTypeKeys.get(structureKey);
			StructureEntry entry = new StructureEntry(structureKey, typeKey != null ? typeKey : StructureUtils.NO_TYPE_KEY, new ArrayList<ResourceLocation>(dimensionKeysForAllowedStructureKeys.get(structureKey)));
			int entryBytes = entry.maxByteSize();

			if (!batch.isEmpty() && (batch.size() >= MAX_BATCH_ENTRIES || batchBytes + entryBytes > MAX_BATCH_BYTES)) {
				packets.add(new SyncPacket(canTeleport, packets.isEmpty(), false, batch));
				batch = new ArrayList<StructureEntry>();
				batchBytes = 0;
			}

			batch.add(entry);
			batchBytes += entryBytes;
		}

		packets.add(new SyncPacket(canTeleport, packets.isEmpty(), true, batch));
		return packets;
	}

	public void handle(Supplier<NetworkEvent.Context> ctx) {
		ctx.get().enqueueWork(this::apply);
		ctx.get().setPacketHandled(true);
	}

	void apply() {
		if (firstBatch) {
			clearReceived();
		}

		for (StructureEntry entry : entries) {
			receivedStructureKeys.add(entry.structureKey);
			receivedTypeKeys.put(entry.structureKey, entry.typeKey);
			receivedDimensionKeys.putAll(entry.structureKey, entry.dimensionKeys);
		}

		if (lastBatch) {
			// Publish everything at once, so the GUI never sees half of a structure list
			ExplorersCompass.canTeleport = canTeleport;
			ExplorersCompass.allowedStructureKeys = new ArrayList<ResourceLocation>(receivedStructureKeys);
			ExplorersCompass.dimensionKeysForAllowedStructureKeys = ArrayListMultimap.create(receivedDimensionKeys);
			ExplorersCompass.structureKeysToTypeKeys = new HashMap<ResourceLocation, ResourceLocation>(receivedTypeKeys);
			ExplorersCompass.typeKeysToStructureKeys = StructureUtils.getTypeKeysToStructureKeys(ExplorersCompass.structureKeysToTypeKeys);
			clearReceived();
		}
	}

	private static void clearReceived() {
		receivedStructureKeys.clear();
		receivedTypeKeys.clear();
		receivedDimensionKeys.clear();
	}

	private static class StructureEntry {

		private final ResourceLocation structureKey;
		private final ResourceLocation typeKey;
		private final List<ResourceLocation> dimensionKeys;

		private StructureEntry(ResourceLocation structureKey, ResourceLocation typeKey, List<ResourceLocation> dimensionKeys) {
			this.structureKey = structureKey;
			this.typeKey = typeKey;
			this.dimensionKeys = dimensionKeys;
		}

		/**
		 * An upper bound on the number of bytes this entry occupies on the wire. Resource locations are
		 * ASCII, so their encoded length is their string length plus a prefix of at most three bytes.
		 */
		private int maxByteSize() {
			int size = structureKey.toString().length() + typeKey.toString().length() + 11;
			for (ResourceLocation dimensionKey : dimensionKeys) {
				size += dimensionKey.toString().length() + 3;
			}
			return size;
		}

	}

}
