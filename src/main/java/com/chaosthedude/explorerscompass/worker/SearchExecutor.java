package com.chaosthedude.explorerscompass.worker;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.chaosthedude.explorerscompass.ExplorersCompass;

/**
 * The threads searches run on when they are allowed off the server thread.
 *
 * <p>Only a biome search ever gets here. Which biome generates somewhere follows from the world
 * seed and the generator's noise alone, so sampling it reads nothing the server is writing to, and
 * the game does the same from its own worldgen threads. A structure search cannot: answering
 * whether one is present reads chunks from storage and may run structure generation, neither of
 * which is safe anywhere but the server thread.
 *
 * <p>The threads are created as they are needed and dropped again once nothing has used them for a
 * while, so a server on which nobody is searching keeps none of them around.
 */
public class SearchExecutor {

	// Enough for a handful of searches at once without them and the server itself ending up fighting
	// over the machine
	private static final int MAX_THREADS = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 4));
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
