package com.chaosthedude.explorerscompass.worker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.chaosthedude.explorerscompass.config.ConfigHandler;

import net.minecraft.world.entity.player.Player;

/**
 * The searches each player has running, and how often they are allowed to start one.
 *
 * <p>A manager per player, so that one player starting, finishing, or cancelling a search cannot
 * stop another's. Only ever touched on the server thread.
 */
public class SearchService {

	private static final Map<UUID, SearchWorkerManager> workerManagers = new HashMap<UUID, SearchWorkerManager>();

	// When each player last started a search, for rate limiting
	private static final Map<UUID, Long> lastSearchStartTimes = new HashMap<UUID, Long>();

	private SearchService() {
	}

	/** The worker manager running the given player's searches. */
	public static SearchWorkerManager getWorkerManager(Player player) {
		return workerManagers.computeIfAbsent(player.getUUID(), (uuid) -> new SearchWorkerManager());
	}

	/**
	 * Whether the given player has started a search too recently to start another. Search packets
	 * cost a client nothing to send, so without this a modified client could restart expensive
	 * searches as fast as it can spam them.
	 */
	public static boolean isOnCooldown(Player player) {
		final int cooldown = ConfigHandler.GENERAL.searchRequestCooldownMillis.get();
		if (cooldown <= 0) {
			return false;
		}

		final Long lastStart = lastSearchStartTimes.get(player.getUUID());
		return lastStart != null && System.currentTimeMillis() - lastStart.longValue() < cooldown;
	}

	/**
	 * Records that a search is being started now, which is what the cooldown is measured from. Only
	 * called once a request has turned out to be one that will actually search, so that a request
	 * nothing came of does not hold up the next real one.
	 */
	public static void recordSearchStart(Player player) {
		lastSearchStartTimes.put(player.getUUID(), Long.valueOf(System.currentTimeMillis()));
	}

	/** Stops whatever the given player has running, and forgets the workers it was made of. */
	public static void stopSearch(Player player) {
		final SearchWorkerManager workerManager = getWorkerManager(player);
		workerManager.stop();
		workerManager.clear();
	}

	/** Stops every search on this server, and forgets who they belonged to. */
	public static void forgetAllPlayers() {
		for (UUID playerId : new ArrayList<UUID>(workerManagers.keySet())) {
			forgetPlayer(playerId);
		}
	}

	/**
	 * Drops everything remembered about a player who has left the server, so that none of it is kept
	 * for the rest of the server's life.
	 */
	public static void forgetPlayer(UUID playerId) {
		final SearchWorkerManager workerManager = workerManagers.remove(playerId);
		if (workerManager != null) {
			// Stop before dropping the manager: its workers are registered with the scheduler, and would
			// otherwise keep sampling for a player who is no longer here, with nothing left that could
			// stop them
			workerManager.stop();
			workerManager.clear();
		}
		lastSearchStartTimes.remove(playerId);
	}

}
