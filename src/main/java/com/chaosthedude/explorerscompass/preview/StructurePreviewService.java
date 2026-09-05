package com.chaosthedude.explorerscompass.preview;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.util.StructureUtils;
import com.chaosthedude.explorerscompass.worker.SearchExecutor;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Hands out previews of structures, and remembers the ones it has already worked out.
 *
 * <p>Building one costs about what generating a structure costs, which is far too much to repeat
 * every time a player opens the same one, and far too much for the server thread to stand still
 * for: a large structure takes long enough to assemble to hold up a whole tick. So a preview is
 * built on the threads searches run on — assembling one asks nothing of the world, only of the
 * seed, the noise and the registries, which is the same ground a search covers there — and the
 * players waiting on it are answered when it lands. The most recently asked for are then kept,
 * which is enough for someone looking through a list, and everything is dropped when the server
 * stops so that none of it outlives the world it was derived from.
 *
 * <p>Every field here is only ever touched from the server thread: a build hands its result back
 * to the server thread rather than writing anything itself.
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

	/**
	 * The builds currently on their way, each carrying the players to answer when it lands. A
	 * structure several players ask about at once is built once, not once per asker.
	 */
	private static final Map<String, List<ServerPlayer>> building = new HashMap<String, List<ServerPlayer>>();

	/** When each player last asked for a preview, for rate limiting. */
	private static final Map<UUID, Long> lastRequestTimes = new HashMap<UUID, Long>();

	/** The server everything cached here was derived from. */
	private static MinecraftServer cachedServer;

	/** How a finished preview is handed back to a player. Always called on the server thread. */
	public interface Delivery {

		void deliver(ServerPlayer player, StructurePreview preview);

	}

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
	 * Answers the given player with a preview of the given structure, or with nothing when there is
	 * none to be had. A preview already worked out is delivered before this returns; one still to be
	 * built is built off the server thread, and everyone who asked for it meanwhile is answered when
	 * it lands. Runs on the server thread, which is also where the delivery is called.
	 */
	public static void request(ServerPlayer player, ResourceLocation structureKey, Delivery delivery) {
		final ServerLevel level = levelFor(player, structureKey);
		if (level == null) {
			delivery.deliver(player, null);
			return;
		}

		forgetIfServerChanged(level.getServer());

		final String cacheKey = level.dimension().location() + "|" + structureKey;
		final StructurePreview cached = cache.get(cacheKey);
		if (cached != null) {
			delivery.deliver(player, cached);
			return;
		}
		if (unavailable.containsKey(cacheKey)) {
			delivery.deliver(player, null);
			return;
		}

		final List<ServerPlayer> waiters = building.get(cacheKey);
		if (waiters != null) {
			// Already being built for someone else; this player is answered when it lands. Not answered
			// with nothing meanwhile: the client would read that as the structure having no preview.
			if (!waiters.contains(player)) {
				waiters.add(player);
			}
			return;
		}

		final List<ServerPlayer> newWaiters = new ArrayList<ServerPlayer>();
		newWaiters.add(player);
		building.put(cacheKey, newWaiters);

		final MinecraftServer server = level.getServer();
		try {
			SearchExecutor.execute(() -> {
				final long startedAt = System.currentTimeMillis();
				StructurePreview built = null;
				Throwable failure = null;
				try {
					built = StructurePreviewBuilder.build(level, structureKey);
				} catch (Throwable t) {
					failure = t;
				}

				// Everything a result touches belongs to the server thread, so the result is handed
				// back rather than written from here
				final StructurePreview preview = built;
				final Throwable error = failure;
				final long took = System.currentTimeMillis() - startedAt;
				server.execute(() -> completeBuild(server, cacheKey, structureKey, preview, error, took, delivery));
			});
		} catch (Throwable t) {
			// Nothing is going to build it, so nobody can be left waiting for it
			building.remove(cacheKey);
			ExplorersCompass.LOGGER.error("Could not start building a preview of " + structureKey, t);
			delivery.deliver(player, null);
		}
	}

	/**
	 * Takes note of what a build came back with, and answers everyone who was waiting on it. Runs on
	 * the server thread.
	 */
	private static void completeBuild(MinecraftServer server, String cacheKey, ResourceLocation structureKey, StructurePreview preview, Throwable failure, long took, Delivery delivery) {
		// A server that has stopped runs handed-in tasks on whatever thread handed them in, and
		// nothing here may be touched from one of those
		if (!server.isSameThread()) {
			return;
		}

		final List<ServerPlayer> waiters = building.remove(cacheKey);

		// The world this was built from is gone, and so are the players who asked
		if (server != cachedServer) {
			return;
		}

		if (failure != null) {
			ExplorersCompass.LOGGER.error("Failed to build a preview of " + structureKey, failure);
		} else if (took >= SLOW_BUILD_MILLIS) {
			ExplorersCompass.LOGGER.info("Building a preview of " + structureKey + " took " + took + "ms off the server thread; it is kept, so this is not paid again while it stays in use");
		}

		if (preview == null) {
			unavailable.put(cacheKey, Boolean.TRUE);
		} else {
			cache.put(cacheKey, preview);
		}

		if (waiters == null) {
			return;
		}
		for (ServerPlayer waiter : waiters) {
			// A player who left while it was being built has nowhere to be answered
			if (!waiter.hasDisconnected()) {
				delivery.deliver(waiter, preview);
			}
		}
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
		// A build still on its way belongs to the world that is going away; completeBuild sees the
		// server no longer matches and drops its result
		building.clear();
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
