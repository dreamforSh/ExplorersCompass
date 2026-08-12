package com.chaosthedude.explorerscompass.network;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.chaosthedude.explorerscompass.config.StructureGroupsConfig;
import com.chaosthedude.explorerscompass.util.BiomeUtils;
import com.chaosthedude.explorerscompass.util.SearchTarget;
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
	// structures and biomes to push a single packet past that, which disconnects the player as soon
	// as they use the compass. Send the lists in batches that stay well below the limit instead.
	private static final int MAX_BATCH_BYTES = 32768;
	private static final int MAX_BATCH_ENTRIES = 512;

	// The batches received so far, applied all at once when the final batch arrives
	private static final List<ResourceLocation> receivedStructureKeys = new ArrayList<ResourceLocation>();
	private static final ListMultimap<ResourceLocation, ResourceLocation> receivedStructureDimensionKeys = ArrayListMultimap.create();
	private static final Map<ResourceLocation, ResourceLocation> receivedStructureTypeKeys = new HashMap<ResourceLocation, ResourceLocation>();
	private static final List<ResourceLocation> receivedBiomeKeys = new ArrayList<ResourceLocation>();
	private static final ListMultimap<ResourceLocation, ResourceLocation> receivedBiomeDimensionKeys = ArrayListMultimap.create();
	private static final Map<ResourceLocation, ResourceLocation> receivedBiomeGroupKeys = new HashMap<ResourceLocation, ResourceLocation>();

	/**
	 * The version of the data each connected player was last sent, so that using the compass again
	 * does not re-send an unchanged list. Entries are dropped when a player logs out: after a relog
	 * the client may have been on another server meanwhile.
	 */
	private static final Map<UUID, Long> lastSyncedVersions = new HashMap<UUID, Long>();

	private boolean canTeleport;
	/**
	 * Whether this server will show a player what a structure looks like. Read from the config rather
	 * than passed in: unlike teleporting, it is the same answer for every player.
	 */
	private boolean allowStructurePreview;
	private boolean listUnchanged;
	private boolean firstBatch;
	private boolean lastBatch;
	private List<Entry> entries;
	private Map<ResourceLocation, String> groupNames;

	private SyncPacket(boolean canTeleport, boolean listUnchanged, boolean firstBatch, boolean lastBatch, List<Entry> entries) {
		this.canTeleport = canTeleport;
		allowStructurePreview = ConfigHandler.GENERAL.allowStructurePreview.get();
		this.listUnchanged = listUnchanged;
		this.firstBatch = firstBatch;
		this.lastBatch = lastBatch;
		this.entries = entries;
		groupNames = Map.of();
	}

	public SyncPacket(FriendlyByteBuf buf) {
		canTeleport = buf.readBoolean();
		allowStructurePreview = buf.readBoolean();
		listUnchanged = buf.readBoolean();
		entries = new ArrayList<Entry>();
		groupNames = new HashMap<ResourceLocation, String>();
		if (listUnchanged) {
			return;
		}

		firstBatch = buf.readBoolean();
		lastBatch = buf.readBoolean();

		int numEntries = buf.readVarInt();
		for (int i = 0; i < numEntries; i++) {
			SearchTarget searchTarget = SearchTarget.fromID(buf.readVarInt());
			ResourceLocation key = buf.readResourceLocation();
			ResourceLocation groupKey = buf.readResourceLocation();
			List<ResourceLocation> dimensionKeys = new ArrayList<ResourceLocation>();
			int numDimensions = buf.readVarInt();
			for (int j = 0; j < numDimensions; j++) {
				dimensionKeys.add(buf.readResourceLocation());
			}
			entries.add(new Entry(searchTarget, key, groupKey, dimensionKeys));
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
		buf.writeBoolean(allowStructurePreview);
		buf.writeBoolean(listUnchanged);
		if (listUnchanged) {
			return;
		}

		buf.writeBoolean(firstBatch);
		buf.writeBoolean(lastBatch);

		buf.writeVarInt(entries.size());
		for (Entry entry : entries) {
			buf.writeVarInt(entry.searchTarget.getID());
			buf.writeResourceLocation(entry.key);
			buf.writeResourceLocation(entry.groupKey);
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
	 * current version of the data, that is a single small packet carrying only {@code canTeleport}:
	 * the full lists are only re-sent when they actually changed, since on a large pack they span
	 * many packets.
	 */
	public static List<SyncPacket> createForPlayer(ServerPlayer player, boolean canTeleport, ServerLevel level) {
		final long version = dataVersion(level);
		final Long lastSynced = lastSyncedVersions.get(player.getUUID());
		if (lastSynced != null && lastSynced.longValue() == version) {
			return List.of(new SyncPacket(canTeleport, true, false, false, List.of()));
		}

		lastSyncedVersions.put(player.getUUID(), version);
		return create(canTeleport, level);
	}

	/**
	 * Identifies the contents of both lists at once, so that a change to either of them re-syncs a
	 * client that is holding the other one as well.
	 */
	private static long dataVersion(ServerLevel level) {
		return ((long) StructureUtils.getStructureDataVersion(level) << 32) | (BiomeUtils.getBiomeDataVersion(level) & 0xFFFFFFFFL);
	}

	/** Forgets what was synced to the given player, so that their next use syncs from scratch. */
	public static void forgetPlayer(UUID playerId) {
		lastSyncedVersions.remove(playerId);
	}

	/**
	 * Forgets what was synced to everyone. Called when the server stops, so that nothing about the
	 * world it was running is kept for whichever one is loaded next.
	 */
	public static void forgetAllPlayers() {
		lastSyncedVersions.clear();
		clearReceived();
	}

	/**
	 * Splits the lists into as many packets as it takes for each one to stay below the custom
	 * payload size limit. Always returns at least one packet, so that a client is still updated when
	 * there is nothing to send. The group to member mapping is not sent: it is the inverse of the
	 * member to group mapping and is rebuilt by the client.
	 */
	private static List<SyncPacket> create(boolean canTeleport, ServerLevel level) {
		final List<Entry> allEntries = new ArrayList<Entry>();
		collectEntries(allEntries, SearchTarget.STRUCTURE, StructureUtils.getAllowedStructureKeys(level), StructureUtils.getGeneratingDimensionsForAllowedStructures(level), StructureUtils.getStructureKeysToTypeKeys(level));
		collectEntries(allEntries, SearchTarget.BIOME, BiomeUtils.getAllowedBiomeKeys(level), BiomeUtils.getGeneratingDimensionsForAllowedBiomes(level), BiomeUtils.getBiomeKeysToGroupKeys(level));

		final List<SyncPacket> packets = new ArrayList<SyncPacket>();
		List<Entry> batch = new ArrayList<Entry>();
		int batchBytes = 0;
		for (Entry entry : allEntries) {
			int entryBytes = entry.maxByteSize();
			if (!batch.isEmpty() && (batch.size() >= MAX_BATCH_ENTRIES || batchBytes + entryBytes > MAX_BATCH_BYTES)) {
				packets.add(new SyncPacket(canTeleport, false, packets.isEmpty(), false, batch));
				batch = new ArrayList<Entry>();
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

	private static void collectEntries(List<Entry> entries, SearchTarget searchTarget, List<ResourceLocation> allowedKeys, ListMultimap<ResourceLocation, ResourceLocation> dimensionKeys, Map<ResourceLocation, ResourceLocation> keysToGroupKeys) {
		for (ResourceLocation key : allowedKeys) {
			final ResourceLocation groupKey = keysToGroupKeys.get(key);
			entries.add(new Entry(searchTarget, key, groupKey != null ? groupKey : StructureUtils.NO_TYPE_KEY, new ArrayList<ResourceLocation>(dimensionKeys.get(key))));
		}
	}

	public void handle(Supplier<NetworkEvent.Context> ctx) {
		ctx.get().enqueueWork(this::apply);
		ctx.get().setPacketHandled(true);
	}

	void apply() {
		if (listUnchanged) {
			// The client already holds the current lists; only these can have changed
			ExplorersCompass.canTeleport = canTeleport;
			ExplorersCompass.canPreviewStructures = allowStructurePreview;
			return;
		}

		if (firstBatch) {
			clearReceived();
		}

		for (Entry entry : entries) {
			if (entry.searchTarget == SearchTarget.BIOME) {
				receivedBiomeKeys.add(entry.key);
				receivedBiomeGroupKeys.put(entry.key, entry.groupKey);
				receivedBiomeDimensionKeys.putAll(entry.key, entry.dimensionKeys);
			} else {
				receivedStructureKeys.add(entry.key);
				receivedStructureTypeKeys.put(entry.key, entry.groupKey);
				receivedStructureDimensionKeys.putAll(entry.key, entry.dimensionKeys);
			}
		}

		if (lastBatch) {
			// Publish everything at once, so the GUI never sees half of a list
			ExplorersCompass.canTeleport = canTeleport;
			ExplorersCompass.canPreviewStructures = allowStructurePreview;
			ExplorersCompass.allowedStructureKeys = new ArrayList<ResourceLocation>(receivedStructureKeys);
			ExplorersCompass.dimensionKeysForAllowedStructureKeys = ArrayListMultimap.create(receivedStructureDimensionKeys);
			ExplorersCompass.structureKeysToTypeKeys = new HashMap<ResourceLocation, ResourceLocation>(receivedStructureTypeKeys);
			ExplorersCompass.groupNames = new HashMap<ResourceLocation, String>(groupNames);
			ExplorersCompass.allowedBiomeKeys = new ArrayList<ResourceLocation>(receivedBiomeKeys);
			ExplorersCompass.dimensionKeysForAllowedBiomeKeys = ArrayListMultimap.create(receivedBiomeDimensionKeys);
			ExplorersCompass.biomeKeysToGroupKeys = new HashMap<ResourceLocation, ResourceLocation>(receivedBiomeGroupKeys);
			ExplorersCompass.clientSearchDataRevision++;
			clearReceived();
		}
	}

	private static void clearReceived() {
		receivedStructureKeys.clear();
		receivedStructureTypeKeys.clear();
		receivedStructureDimensionKeys.clear();
		receivedBiomeKeys.clear();
		receivedBiomeGroupKeys.clear();
		receivedBiomeDimensionKeys.clear();
	}

	/** One structure or biome the compass may search for, and what the screens show about it. */
	private static class Entry {

		private final SearchTarget searchTarget;
		private final ResourceLocation key;
		private final ResourceLocation groupKey;
		private final List<ResourceLocation> dimensionKeys;

		private Entry(SearchTarget searchTarget, ResourceLocation key, ResourceLocation groupKey, List<ResourceLocation> dimensionKeys) {
			this.searchTarget = searchTarget;
			this.key = key;
			this.groupKey = groupKey;
			this.dimensionKeys = dimensionKeys;
		}

		/**
		 * An upper bound on the number of bytes this entry occupies on the wire. Resource locations are
		 * ASCII, so their encoded length is their string length plus a prefix of at most three bytes.
		 */
		private int maxByteSize() {
			int size = key.toString().length() + groupKey.toString().length() + 16;
			for (ResourceLocation dimensionKey : dimensionKeys) {
				size += dimensionKey.toString().length() + 3;
			}
			return size;
		}

	}

}
