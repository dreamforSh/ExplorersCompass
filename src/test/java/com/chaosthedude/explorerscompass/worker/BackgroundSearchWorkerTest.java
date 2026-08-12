package com.chaosthedude.explorerscompass.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;

class BackgroundSearchWorkerTest {

	private static final BlockPos START = new BlockPos(0, 64, 0);

	/** Far longer than any of these take, so a search that never ends fails instead of hanging. */
	private static final long TIMEOUT_SECONDS = 20L;

	@AfterEach
	void dropSearchThreads() {
		SearchExecutor.shutdown();
	}

	/** A search whose pieces all wait to be let go, so that a test decides when each of them ends. */
	private static class WaitingWorker extends BackgroundSearchWorker {

		private final int pieces;
		private final boolean fail;
		private final CountDownLatch release = new CountDownLatch(1);
		private final AtomicInteger ran = new AtomicInteger();

		private WaitingWorker(int pieces, boolean fail) {
			super(new SearchContext(null, null, null, START, new ArrayList<BlockPos>(), false, false, 10000, false), 1000);
			this.pieces = pieces;
			this.fail = fail;
		}

		@Override
		protected boolean isBackgroundAllowed() {
			return true;
		}

		@Override
		protected List<Runnable> createBackgroundTasks() {
			final List<Runnable> tasks = new ArrayList<Runnable>();
			for (int piece = 0; piece < pieces; piece++) {
				tasks.add(() -> {
					try {
						release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
					ran.incrementAndGet();
					if (fail) {
						throw new IllegalStateException("a piece of the search gave up");
					}
				});
			}
			return tasks;
		}

		@Override
		protected boolean doSample() {
			return false;
		}

		@Override
		protected String getName() {
			return "WaitingWorker";
		}

		@Override
		protected boolean shouldLogRadius() {
			return false;
		}

	}

	private static void awaitReady(BackgroundSearchWorker worker) throws InterruptedException {
		final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
		while (!worker.isReady()) {
			if (System.nanoTime() - deadline > 0L) {
				throw new AssertionError("the search never published that it had finished");
			}
			Thread.sleep(5L);
		}
	}

	@Test
	void aSearchIsOnlyOverOnceEveryPieceOfItIs() throws InterruptedException {
		final WaitingWorker worker = new WaitingWorker(3, false);

		worker.begin();

		// There is nothing for the server thread to do while they run, so the turn goes past this
		// worker to the rest of the search — but it keeps its place in the search until it has
		// something to report, whatever the pieces have actually got up to
		assertFalse(worker.isReady());
		assertTrue(worker.hasWork());

		worker.release.countDown();
		awaitReady(worker);

		assertEquals(3, worker.ran.get());
		// Ready to be asked, and still part of the search: the server thread has yet to act on it
		assertTrue(worker.hasWork());
		assertFalse(worker.isExhausted());
		assertNull(worker.getBackgroundError());
	}

	@Test
	void aPieceThatGivesUpStillEndsTheSearch() throws InterruptedException {
		final WaitingWorker worker = new WaitingWorker(2, true);

		worker.begin();
		worker.release.countDown();
		awaitReady(worker);

		// A piece that died must still count itself out, or the compass waits on a result that is never
		// coming, and what went wrong has to survive for the server thread to report
		assertEquals(2, worker.ran.get());
		assertNotNull(worker.getBackgroundError());
	}

	@Test
	void aSearchThatIsNotAllowedItsOwnThreadsRunsOnTheServerThread() {
		final WaitingWorker worker = new WaitingWorker(1, false) {
			@Override
			protected boolean isBackgroundAllowed() {
				return false;
			}
		};

		worker.begin();

		// Nothing was started, so the worker takes its turns like any other
		assertTrue(worker.isReady());
		assertEquals(0, worker.ran.get());
	}

}
