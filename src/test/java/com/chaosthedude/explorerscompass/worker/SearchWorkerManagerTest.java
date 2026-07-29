package com.chaosthedude.explorerscompass.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

class SearchWorkerManagerTest {

	private static final BlockPos START = new BlockPos(0, 64, 0);
	private static final ResourceLocation NEAR_KEY = new ResourceLocation("test", "near");
	private static final ResourceLocation FAR_KEY = new ResourceLocation("test", "far");

	// Far more turns than any of these searches take, so a manager that never finishes fails the test
	// instead of hanging the build
	private static final int TURN_LIMIT = 10000;

	@AfterEach
	void dropSchedulerState() {
		// Starting a search registers its manager with the scheduler, which is shared
		SearchScheduler.shutdown();
	}

	/** A context that records what the search reported, instead of writing it to a compass. */
	private static class RecordingContext extends SearchContext {

		private BlockPos locatedPos;
		private ResourceLocation locatedKey;
		private int locatedSamples;
		private boolean notFound;
		private int notFoundRadius;
		private final List<Integer> reportedRadii = new ArrayList<Integer>();

		private RecordingContext(int maxRadius) {
			super(null, null, null, START, new ArrayList<BlockPos>(), false, false, maxRadius, false);
		}

		@Override
		public boolean holdsCompass() {
			return true;
		}

		@Override
		void reportRadius(int radius) {
			reportedRadii.add(Integer.valueOf(radius));
		}

		@Override
		void reportLocated(BlockPos pos, ResourceLocation key, int totalSamples) {
			locatedPos = pos;
			locatedKey = key;
			locatedSamples = totalSamples;
		}

		@Override
		void reportNotFound(int radius, int totalSamples) {
			notFound = true;
			notFoundRadius = radius;
		}

	}

	/**
	 * A worker that walks straight outwards a fixed step at a time, and finds what it was told to
	 * once it gets there.
	 */
	private static class FakeWorker extends SearchWorker {

		private final String name;
		private final int step;
		private final int findAt;
		private final ResourceLocation key;

		// How many turns this worker turns down before it can sample, standing in for one waiting on
		// positions a placement is still computing
		private int turnsUntilReady;

		private int radius;

		private FakeWorker(SearchContext context, String name, int step, int findAt, ResourceLocation key) {
			super(context, TURN_LIMIT);
			this.name = name;
			this.step = step;
			this.findAt = findAt;
			this.key = key;
		}

		@Override
		boolean isReady() {
			if (turnsUntilReady <= 0) {
				return true;
			}
			turnsUntilReady--;
			return false;
		}

		@Override
		protected boolean doSample() {
			radius += step;
			samples++;
			if (key != null && radius >= findAt) {
				found(new BlockPos(findAt, 0, 0), key);
			}
			return hasWork();
		}

		@Override
		protected int getRadius() {
			return radius;
		}

		@Override
		protected String getName() {
			return name;
		}

		@Override
		protected boolean shouldLogRadius() {
			return true;
		}

	}

	private static SearchWorkerManager searchOf(RecordingContext context, SearchWorker... workers) {
		final SearchWorkerManager manager = new SearchWorkerManager();
		manager.createWorkers(context, List.of(workers));
		assertTrue(manager.start(), "the search had nothing to run");
		return manager;
	}

	private static void runToEnd(SearchWorkerManager manager) {
		for (int turn = 0; turn < TURN_LIMIT; turn++) {
			if (!manager.hasWork()) {
				return;
			}
			manager.doWork();
		}
		throw new AssertionError("the search never finished");
	}

	@Test
	void aWorkerThatCannotSampleYetHandsTheTurnOn() {
		final RecordingContext context = new RecordingContext(10000);
		final FakeWorker waiting = new FakeWorker(context, "waiting", 100, 0, null);
		waiting.turnsUntilReady = 5;
		final FakeWorker ready = new FakeWorker(context, "ready", 100, 500, NEAR_KEY);
		final SearchWorkerManager manager = searchOf(context, waiting, ready);

		manager.doWork();

		// The turn went past the one that could not use it. Holding onto it instead would leave every
		// other placement of the search standing still for as long as that one is not ready.
		assertEquals(0, waiting.getSamples());
		assertEquals(1, ready.getSamples());
	}

	@Test
	void aSearchWhoseWorkersAreAllWaitingGivesTheTickUp() {
		final RecordingContext context = new RecordingContext(10000);
		final FakeWorker waiting = new FakeWorker(context, "waiting", 100, 0, null);
		waiting.turnsUntilReady = 5;
		final FakeWorker alsoWaiting = new FakeWorker(context, "alsoWaiting", 100, 0, null);
		alsoWaiting.turnsUntilReady = 5;
		final SearchWorkerManager manager = searchOf(context, waiting, alsoWaiting);

		// Nothing to spend the turn on, rather than going round the workers for the rest of the tick
		assertEquals(false, manager.doWork());
		assertEquals(0, waiting.getSamples());
		assertEquals(0, alsoWaiting.getSamples());
	}

	@Test
	void theSearchAnswersWithTheNearestOfEveryWorkerAndCutsTheRestDownToIt() {
		final RecordingContext context = new RecordingContext(10000);
		// First in the queue, and holds nothing until far past what the second one finds
		final FakeWorker far = new FakeWorker(context, "far", 100, 3000, FAR_KEY);
		final FakeWorker near = new FakeWorker(context, "near", 100, 800, NEAR_KEY);
		final SearchWorkerManager manager = searchOf(context, far, near);

		runToEnd(manager);

		assertEquals(NEAR_KEY, context.locatedKey);
		assertEquals(800, context.locatedPos.getX());
		// Cut down to what the other worker located: it covered the first band and then stopped, rather
		// than walking the whole radius out to its own find at 3000
		assertEquals(10, far.getSamples());
		assertEquals(far.getSamples() + near.getSamples(), context.locatedSamples);
	}

	@Test
	void aSearchThatLocatesNothingReportsHowFarItLooked() {
		final RecordingContext context = new RecordingContext(1000);
		final FakeWorker first = new FakeWorker(context, "first", 100, 0, null);
		final FakeWorker second = new FakeWorker(context, "second", 100, 0, null);
		final SearchWorkerManager manager = searchOf(context, first, second);

		runToEnd(manager);

		assertTrue(context.notFound);
		assertNull(context.locatedPos);
		assertEquals(1000, context.notFoundRadius);
	}

	@Test
	void theRadiusReportedToTheCompassOnlyGrows() {
		final RecordingContext context = new RecordingContext(4000);
		final FakeWorker first = new FakeWorker(context, "first", 100, 0, null);
		final FakeWorker second = new FakeWorker(context, "second", 100, 0, null);
		final SearchWorkerManager manager = searchOf(context, first, second);

		runToEnd(manager);

		// Each worker starts over from where the player is standing, so the turn passing from one to
		// the next must not drag the readout back to what that one has covered
		assertTrue(context.reportedRadii.size() > 1, "the radius was never reported");
		int previous = -1;
		for (int reported : context.reportedRadii) {
			assertTrue(reported > previous, "the reported radius dropped back from " + previous + " to " + reported);
			assertEquals(0, reported % 250, "the reported radius was not rounded");
			previous = reported;
		}
	}

}
