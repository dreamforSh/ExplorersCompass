package com.chaosthedude.explorerscompass.worker;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.chaosthedude.explorerscompass.ExplorersCompass;

/**
 * The threads searches run on when they are allowed off the server thread.
 *
 * <p>What runs here is the part of a search that follows from the world seed and the generator's
 * noise alone: which biome generates somewhere, and where world generation would put a structure.
 * Neither reads any part of the world the server is writing to, and the game works both of them out
 * from its own worldgen threads while generating chunks. What never runs here is asking chunk
 * storage what a chunk already holds, which only the server thread may do — so a structure search
 * still comes back to it for the location it settles on.
 *
 * <p>The threads are created as they are needed and dropped again once nothing has used them for a
 * while, so a server on which nobody is searching keeps none of them around.
 */
public class SearchExecutor {

	// Enough for a handful of searches at once without them and the server itself ending up fighting
	// over the machine. Never fewer than two: a search for several kinds of structure at once is split
	// into a walk per placement, and one player's search waiting on another's would read as a compass
	// that has stopped doing anything.
	private static final int MAX_THREADS = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
	private static final long KEEP_ALIVE_SECONDS = 60L;

	private static final AtomicInteger threadCount = new AtomicInteger();

	private static ThreadPoolExecutor executor;

	private SearchExecutor() {
	}

	/** Runs a search on one of these threads, waiting for a free one when they are all busy. */
	static synchronized void execute(Runnable search) {
		if (executor == null) {
			executor = new ThreadPoolExecutor(MAX_THREADS, MAX_THREADS, KEEP_ALIVE_SECONDS, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(), SearchExecutor::createThread);
			executor.allowCoreThreadTimeOut(true);
		}
		executor.execute(search);
	}

	private static Thread createThread(Runnable search) {
		final Thread thread = new Thread(search, ExplorersCompass.MODID + "-search-" + threadCount.incrementAndGet());
		// A search that is still running must never be what keeps the game from shutting down
		thread.setDaemon(true);
		thread.setUncaughtExceptionHandler((errorThread, error) -> ExplorersCompass.LOGGER.error("Uncaught error on " + errorThread.getName(), error));
		return thread;
	}

	/**
	 * Drops the threads. Called when the server stops, so that a search still running does not go on
	 * sampling a world that is no longer there.
	 */
	public static synchronized void shutdown() {
		if (executor != null) {
			executor.shutdownNow();
			executor = null;
		}
	}

}
