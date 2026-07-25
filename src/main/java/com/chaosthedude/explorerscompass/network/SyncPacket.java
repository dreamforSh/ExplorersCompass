package com.chaosthedude.explorerscompass.network;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.config.StructureGroupsConfig;
import com.chaosthedude.explorerscompass.util.StructureUtils;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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

	// The version of the structure data each connected player was last sent, so that using the
	// compass again does not re-send an unchanged list. Entries are dropped when a player logs out:
	// after a relog the client may have been on another server meanwhile.
	private static final Map<UUID, Integer> lastSyncedVersions = new HashMap<UUID, Integer>();

	private boolean canTeleport;
	private boolean listUnchanged;
	private boolean firstBatch;
	private boolean lastBatch;
	private List<StructureEntry> entries;
	private Map<ResourceLocation, String> groupNames;

	private SyncPacket(boolean canTeleport, boolean listUnchanged, boolean firstBatch, boolean lastBatch, List<StructureEntry> entries) {
		this.canTeleport = canTeleport;
		this.listUnchanged = listUnchanged;
		this.firstBatch = firstBatch;
		this.lastBatch = lastBatch;
		this.entries = entries;
		groupNames = Map.of();
	}

	public SyncPacket(FriendlyByteBuf buf) {
		canTeleport = buf.readBoolean();
		listUnchanged = buf.readBoolean();
		entries = new ArrayList<StructureEntry>();
		groupNames = new HashMap<ResourceLocation, String>();
		if (listUnchanged) {
			return;
		}

		firstBatch = buf.readBoolean();
		lastBatch = buf.readBoolean();

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

		if (lastBatch) {
			int numGroupNames = buf.readVarInt();
			for (int i = 0; i < numGroupNames; i++) {
				groupNames.put(buf.readResourceLocation(), buf.readUtf());
			}
		}
	}

	public void toBytes(FriendlyByteBuf buf) {
		buf.writeBoolean(canTeleport);
		buf.writeBoolean(listUnchanged);
		if (listUnchanged) {
			return;
		}

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

		if (lastBatch) {
			buf.writeVarInt(groupNames.size());
			for (Map.Entry<ResourceLocation, String> entry : groupNames.entrySet()) {
				buf.writeResourceLocation(entry.getKey());
				buf.writeUtf(entry.getValue());
			}
		}
	}

	/**
	 * The packets that bring the given player up to date. When this player was already sent the
	 * current version of the structure data, that is a single small packet carrying only
	 * {@code canTeleport}: the full list is only re-sent when it actually changed, since on a large
	 * pack it spans many packets.
	 */
	public static List<SyncPacket> createForPlayer(ServerPlayer player, boolean canTeleport, ServerLevel level) {
		final int version = StructureUtils.getStructureDataVersion(level);
		final Integer lastSynced = lastSyncedVersions.get(player.getUUID());
		if (lastSynced != null && lastSynced.intValue() == version) {
			return List.of(new SyncPacket(canTeleport, true, false, false, List.of()));
		}

		lastSyncedVersions.put(player.getUUID(), version);
		return create(canTeleport, StructureUtils.getAllowedStructureKeys(level), StructureUtils.getGeneratingDimensionsForAllowedStructures(level), StructureUtils.getStructureKeysToTypeKeys(level));
	}

	/** Forgets what was synced to the given player, so that their next use syncs from scratch. */
	public static void forgetPlayer(UUID playerId) {
		lastSyncedVersions.remove(playerId);
	}

	/**
	 * Splits the structure list into as many packets as it takes for each one to stay below the
	 * custom payload size limit. Always returns at least one packet, so that a client is still
	 * updated when there is nothing to send. The group to structure mapping is not sent: it is the
	 * inverse of the structure to group mapping and is rebuilt by the client.
	 */
	private static List<SyncPacket> create(boolean canTeleport, List<ResourceLocation> allowedStructureKeys, ListMultimap<ResourceLocation, ResourceLocation> dimensionKeysForAllowedStructureKeys, Map<ResourceLocation, ResourceLocation> structureKeysToTypeKeys) {
		final List<SyncPacket> packets = new ArrayList<SyncPacket>();
		List<StructureEntry> batch = new ArrayList<StructureEntry>();
		int batchBytes = 0;

		for (ResourceLocation structureKey : allowedStructureKeys) {
			ResourceLocation typeKey = structureKeysToTypeKeys.get(structureKey);
			StructureEntry entry = new StructureEntry(structureKey, typeKey != null ? typeKey : StructureUtils.NO_TYPE_KEY, new ArrayList<ResourceLocation>(dimensionKeysForAllowedStructureKeys.get(structureKey)));
			int entryBytes = entry.maxByteSize();

			if (!batch.isEmpty() && (batch.size() >= MAX_BATCH_ENTRIES || batchBytes + entryBytes > MAX_BATCH_BYTES)) {
				packets.add(new SyncPacket(canTeleport, false, packets.isEmpty(), false, batch));
				batch = new ArrayList<StructureEntry>();
				batchBytes = 0;
			}

			batch.add(entry);
			batchBytes += entryBytes;
		}

		final SyncPacket last = new SyncPacket(canTeleport, false, packets.isEmpty(), true, batch);
		last.groupNames = StructureGroupsConfig.getGroupNames();
		packets.add(last);
		return packets;
	}

	public void handle(Supplier<NetworkEvent.Context> ctx) {
		ctx.get().enqueueWork(this::apply);
		ctx.get().setPacketHandled(true);
	}

	void apply() {
		if (listUnchanged) {
			// The client already holds the current structure list; only this can have changed
			ExplorersCompass.canTeleport = canTeleport;
			return;
		}

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
			ExplorersCompass.groupNames = new HashMap<ResourceLocation, String>(groupNames);
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
