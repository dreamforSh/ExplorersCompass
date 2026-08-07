package com.chaosthedude.explorerscompass.preview;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.util.StructureUtils;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Hands out previews of structures, and remembers the ones it has already worked out.
 *
 * <p>Building one costs about what generating a structure costs, which is far too much to repeat
 * every time a player opens the same one, and nothing about a preview changes while a server runs:
 * what a structure looks like follows from the data packs it was loaded from. So each one is built
 * once and kept. The few most recently asked for are held, which is enough for someone looking
 * through a list, and everything is dropped when the server stops so that none of it outlives the
 * world it was derived from.
 *
 * <p>Only ever touched from the server thread.
 */
public final class StructurePreviewService {

	/**
	 * How many previews are held at once. Each is at most a few thousand cells, so this is a small
	 * amount of memory, and it is far more than anyone looks at in one sitting.
	 */
	private static final int MAX_CACHED = 32;

	/**
	 * How long a player has to wait between asking for previews. Building one is the expensive part
	 * and is only done once, but a request for a structure that has not been built yet costs a real
	 * amount of work, so a client cannot be allowed to ask for them as fast as it can send packets.
	 */
	private static final long REQUEST_INTERVAL_MILLIS = 250L;

	/** How long a preview may take to build before it is worth saying so in the log. */
	private static final long SLOW_BUILD_MILLIS = 50L;

	/** Previews already worked out, keyed by the dimension they were built in and the structure. */
	private static final Map<String, StructurePreview> cache = new LinkedHashMap<String, StructurePreview>(16, 0.75F, true) {

		private static final long serialVersionUID = 1L;

		@Override
		protected boolean removeEldestEntry(Map.Entry<String, StructurePreview> eldest) {
			return size() > MAX_CACHED;
		}

	};

	/** Structures that could not be previewed, so that asking again does not try to build one again. */
	private static final Map<String, Boolean> unavailable = new HashMap<String, Boolean>();

	/** When each player last asked for a preview, for rate limiting. */
	private static final Map<UUID, Long> lastRequestTimes = new HashMap<UUID, Long>();

	/** The server everything cached here was derived from. */
	private static MinecraftServer cachedServer;

	private StructurePreviewService() {
	}

	/**
	 * Whether the given player asked for a preview too recently to ask for another.
	 */
	public static boolean isOnCooldown(ServerPlayer player) {
		final Long lastRequest = lastRequestTimes.get(player.getUUID());
		return lastRequest != null && System.currentTimeMillis() - lastRequest.longValue() < REQUEST_INTERVAL_MILLIS;
	}

	public static void recordRequest(ServerPlayer player) {
		lastRequestTimes.put(player.getUUID(), Long.valueOf(System.currentTimeMillis()));
	}

	/**
	 * A preview of the given structure for the given player, or null when there is none to be had.
	 * Built on first asking and answered from what was built after that.
	 */
	public static StructurePreview get(ServerPlayer player, ResourceLocation structureKey) {
		final ServerLevel level = levelFor(player, structureKey);
		if (level == null) {
			return null;
		}

		forgetIfServerChanged(level.getServer());

		final String cacheKey = level.dimension().location() + "|" + structureKey;
		final StructurePreview cached = cache.get(cacheKey);
		if (cached != null) {
			return cached;
		}
		if (unavailable.containsKey(cacheKey)) {
			return null;
		}

		final long startedAt = System.currentTimeMillis();
		final StructurePreview preview = StructurePreviewBuilder.build(level, structureKey);
		final long took = System.currentTimeMillis() - startedAt;
		if (took >= SLOW_BUILD_MILLIS) {
			ExplorersCompass.LOGGER.info("Building a preview of " + structureKey + " took " + took + "ms; it is kept, so this is paid once");
		}

		if (preview == null) {
			unavailable.put(cacheKey, Boolean.TRUE);
			return null;
		}

		cache.put(cacheKey, preview);
		return preview;
	}

	/**
	 * The level a preview of the given structure is built in: the one the player is standing in when
	 * the structure generates there, and one it does generate in otherwise. A structure assembled by
	 * the wrong generator comes out wrong or not at all, which is what would happen to an end city
	 * previewed from the overworld.
	 */
	private static ServerLevel levelFor(ServerPlayer player, ResourceLocation structureKey) {
		final ServerLevel currentLevel = player.getLevel();
		final List<ResourceLocation> dimensionKeys = StructureUtils.getGeneratingDimensionsForAllowedStructures(currentLevel).get(structureKey);
		if (dimensionKeys.isEmpty() || dimensionKeys.contains(currentLevel.dimension().location())) {
			return currentLevel;
		}

		for (ServerLevel level : currentLevel.getServer().getAllLevels()) {
			if (dimensionKeys.contains(level.dimension().location())) {
				return level;
			}
		}
		return currentLevel;
	}

	/**
	 * Drops everything held for a server other than the one now running. What is cached here is
	 * derived from a world's data packs and generator, so none of it means anything for the next
	 * world, and holding on to it would keep that whole server alive for as long as the game runs.
	 */
	private static void forgetIfServerChanged(MinecraftServer server) {
		if (server != cachedServer) {
			invalidateCache();
			cachedServer = server;
		}
	}

	/** Drops every preview held. Called when the server stops. */
	public static void invalidateCache() {
		cache.clear();
		unavailable.clear();
		cachedServer = null;
	}

	/** Drops what is remembered about a player who has left the server. */
	public static void forgetPlayer(UUID playerId) {
		lastRequestTimes.remove(playerId);
	}

	/** Drops what is remembered about every player. */
	public static void forgetAllPlayers() {
		lastRequestTimes.clear();
	}

}
