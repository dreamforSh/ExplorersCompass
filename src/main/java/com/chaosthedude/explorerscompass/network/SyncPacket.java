package com.chaosthedude.explorerscompass.network;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.config.StructureGroupsConfig;
import com.chaosthedude.explorerscompass.util.BiomeUtils;
import com.chaosthedude.explorerscompass.util.SearchTarget;
import com.chaosthedude.explorerscompass.util.StructureUtils;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Brings a client's list of searchable structures and biomes up to date.
 *
 * <p>The lists are sent in batches, which the client collects and applies all at once.
 */
public record SyncPacket(boolean canTeleport, boolean listUnchanged, boolean firstBatch, boolean lastBatch, List<Entry> entries, Map<ResourceLocation, String> groupNames) implements CustomPacketPayload {

	// A custom payload may not exceed 1048576 bytes, and a large modpack has more than enough
	// structures and biomes to push a single packet past that, which disconnects the player as soon
	// as they use the compass. Send the lists in batches that stay well below the limit instead.
	private static final int MAX_BATCH_BYTES = 32768;
	private static final int MAX_BATCH_ENTRIES = 512;

	/** Bounds on what arrives, since a batch larger than the server ever sends is a modified one. */
	private static final int MAX_STREAMED_ENTRIES = 4096;
	private static final int MAX_STREAMED_DIMENSIONS = 64;
	private static final int MAX_STREAMED_GROUP_NAMES = 4096;
	private static final int MAX_STREAMED_GROUP_NAME_LENGTH = 256;

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

	public static final CustomPacketPayload.Type<SyncPacket> TYPE = new CustomPacketPayload.Type<SyncPacket>(ResourceLocation.fromNamespaceAndPath(ExplorersCompass.MODID, "sync"));

	/**
	 * Written out by hand rather than composed, since everything after the first two flags is only
	 * present when the list actually changed, and the group names only ride along on the last batch.
	 */
	public static final StreamCodec<RegistryFriendlyByteBuf, SyncPacket> STREAM_CODEC = StreamCodec.of(
			(buf, packet) -> {
				buf.writeBoolean(packet.canTeleport());
				buf.writeBoolean(packet.listUnchanged());
				if (packet.listUnchanged()) {
					return;
				}

				buf.writeBoolean(packet.firstBatch());
				buf.writeBoolean(packet.lastBatch());

				ByteBufCodecs.VAR_INT.encode(buf, packet.entries().size());
				for (Entry entry : packet.entries()) {
					SearchTarget.STREAM_CODEC.encode(buf, entry.searchTarget());
					ResourceLocation.STREAM_CODEC.encode(buf, entry.key());
					ResourceLocation.STREAM_CODEC.encode(buf, entry.groupKey());
					ByteBufCodecs.VAR_INT.encode(buf, entry.dimensionKeys().size());
					for (ResourceLocation dimensionKey : entry.dimensionKeys()) {
						ResourceLocation.STREAM_CODEC.encode(buf, dimensionKey);
					}
				}

				if (packet.lastBatch()) {
					ByteBufCodecs.VAR_INT.encode(buf, packet.groupNames().size());
					for (Map.Entry<ResourceLocation, String> entry : packet.groupNames().entrySet()) {
						ResourceLocation.STREAM_CODEC.encode(buf, entry.getKey());
						buf.writeUtf(entry.getValue());
					}
				}
			},
			buf -> {
				final boolean canTeleport = buf.readBoolean();
				final boolean listUnchanged = buf.readBoolean();
				if (listUnchanged) {
					return new SyncPacket(canTeleport, true, false, false, List.of(), Map.of());
				}

				final boolean firstBatch = buf.readBoolean();
				final boolean lastBatch = buf.readBoolean();

				final int numEntries = readBounded(ByteBufCodecs.VAR_INT.decode(buf), MAX_STREAMED_ENTRIES, "entries");
				final List<Entry> entries = new ArrayList<Entry>(numEntries);
				for (int i = 0; i < numEntries; i++) {
					final SearchTarget searchTarget = SearchTarget.STREAM_CODEC.decode(buf);
					final ResourceLocation key = ResourceLocation.STREAM_CODEC.decode(buf);
					final ResourceLocation groupKey = ResourceLocation.STREAM_CODEC.decode(buf);
					final int numDimensions = readBounded(ByteBufCodecs.VAR_INT.decode(buf), MAX_STREAMED_DIMENSIONS, "dimensions");
					final List<ResourceLocation> dimensionKeys = new ArrayList<ResourceLocation>(numDimensions);
					for (int j = 0; j < numDimensions; j++) {
						dimensionKeys.add(ResourceLocation.STREAM_CODEC.decode(buf));
					}
					entries.add(new Entry(searchTarget, key, groupKey, dimensionKeys));
				}

				final Map<ResourceLocation, String> groupNames = new HashMap<ResourceLocation, String>();
				if (lastBatch) {
					final int numGroupNames = readBounded(ByteBufCodecs.VAR_INT.decode(buf), MAX_STREAMED_GROUP_NAMES, "group names");
					for (int i = 0; i < numGroupNames; i++) {
						groupNames.put(ResourceLocation.STREAM_CODEC.decode(buf), buf.readUtf(MAX_STREAMED_GROUP_NAME_LENGTH));
					}
				}

				return new SyncPacket(canTeleport, false, firstBatch, lastBatch, entries, groupNames);
			});

	private static int readBounded(int count, int max, String what) {
		if (count < 0 || count > max) {
			throw new io.netty.handler.codec.DecoderException("Sync packet declares " + count + " " + what);
		}
		return count;
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
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
			return List.of(new SyncPacket(canTeleport, true, false, false, List.of(), Map.of()));
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
				packets.add(new SyncPacket(canTeleport, false, packets.isEmpty(), false, batch, Map.of()));
				batch = new ArrayList<Entry>();
				batchBytes = 0;
			}

			batch.add(entry);
			batchBytes += entryBytes;
		}

		packets.add(new SyncPacket(canTeleport, false, packets.isEmpty(), true, batch, StructureGroupsConfig.getGroupNames()));
		return packets;
	}

	private static void collectEntries(List<Entry> entries, SearchTarget searchTarget, List<ResourceLocation> allowedKeys, ListMultimap<ResourceLocation, ResourceLocation> dimensionKeys, Map<ResourceLocation, ResourceLocation> keysToGroupKeys) {
		for (ResourceLocation key : allowedKeys) {
			final ResourceLocation groupKey = keysToGroupKeys.get(key);
			entries.add(new Entry(searchTarget, key, groupKey != null ? groupKey : StructureUtils.NO_TYPE_KEY, new ArrayList<ResourceLocation>(dimensionKeys.get(key))));
		}
	}

	public static void handle(SyncPacket packet, IPayloadContext ctx) {
		packet.apply();
	}

	void apply() {
		if (listUnchanged) {
			// The client already holds the current lists; only this can have changed
			ExplorersCompass.canTeleport = canTeleport;
			return;
		}

		if (firstBatch) {
			clearReceived();
		}

		for (Entry entry : entries) {
			if (entry.searchTarget() == SearchTarget.BIOME) {
				receivedBiomeKeys.add(entry.key());
				receivedBiomeGroupKeys.put(entry.key(), entry.groupKey());
				receivedBiomeDimensionKeys.putAll(entry.key(), entry.dimensionKeys());
			} else {
				receivedStructureKeys.add(entry.key());
				receivedStructureTypeKeys.put(entry.key(), entry.groupKey());
				receivedStructureDimensionKeys.putAll(entry.key(), entry.dimensionKeys());
			}
		}

		if (lastBatch) {
			// Publish everything at once, so the GUI never sees half of a list
			ExplorersCompass.canTeleport = canTeleport;
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
	public record Entry(SearchTarget searchTarget, ResourceLocation key, ResourceLocation groupKey, List<ResourceLocation> dimensionKeys) {

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
