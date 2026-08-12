package com.chaosthedude.explorerscompass.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/**
 * Searching for a further instance, end to end over everything a structure search and a biome search
 * share: the locations already found, what a worker is told to pass over, and what the outcome of one
 * search leaves behind for the next.
 */
class SearchForNextTest {

	private static final BlockPos START = new BlockPos(0, 64, 0);
	private static final ResourceLocation KEY = new ResourceLocation("test", "thing");
	private static final int TURN_LIMIT = 10000;

	/** Where this world holds an instance of what is being searched for, nearest first. */
	private static final List<BlockPos> PLACES = List.of(
			new BlockPos(500, 0, 0), new BlockPos(1500, 0, 0), new BlockPos(2500, 0, 0));

	@AfterEach
	void dropSchedulerState() {
		SearchScheduler.shutdown();
	}

	/** A context that records what the search settled on, in place of a compass to write it to. */
	private static class HeadlessContext extends SearchContext {

		private BlockPos locatedPos;

		private HeadlessContext(List<BlockPos> prevPos, boolean ignoreNearStart) {
			super(null, null, null, START, prevPos, false, ignoreNearStart, 10000, false);
		}

		@Override
		public boolean holdsCompass() {
			return true;
		}

		@Override
		void reportRadius(int radius) {
		}

		@Override
		void reportLocated(BlockPos pos, ResourceLocation key, int totalSamples) {
			locatedPos = pos;
		}

		@Override
		void reportNotFound(int radius, int totalSamples) {
		}

	}

	/**
	 * A worker that looks at the places this world holds, nearest first, and answers with the first of
	 * them it has not been told to pass over. This is what every worker of a real search does, whatever
	 * it is that decides where the places are.
	 */
	private static class PlaceWorker extends SearchWorker {

		private int index;

		private PlaceWorker(SearchContext context) {
			super(context, TURN_LIMIT);
		}

		@Override
		protected boolean doSample() {
			final BlockPos place = PLACES.get(index++);
			samples++;
			currentPos = place;
			if (!shouldIgnore(place)) {
				found(place, KEY);
			}
			return hasWork();
		}

		@Override
		protected boolean hasMoreToSample() {
			return index < PLACES.size() && super.hasMoreToSample();
		}

		@Override
		protected boolean isExhausted() {
			return index >= PLACES.size() || super.isExhausted();
		}

		@Override
		protected int getRadius() {
			return (int) Math.sqrt(context.distanceSqrFromStart(currentPos.getX(), currentPos.getZ()));
		}

		@Override
		protected String getName() {
			return "PlaceWorker";
		}

		@Override
		protected boolean shouldLogRadius() {
			return true;
		}

	}

	/**
	 * Runs one search the way the compass does, and answers with the location it settled on. The list
	 * of places already found is the one the compass carries from one search to the next, and the
	 * search adds to it itself.
	 */
	private static BlockPos search(List<BlockPos> alreadyFound, boolean forAFurtherInstance) {
		// The compass carries the locations already found from one search to the next and hands them to
		// the search that follows, which is what asking for a further instance does
		final HeadlessContext context = new HeadlessContext(new ArrayList<BlockPos>(alreadyFound), forAFurtherInstance);
		final SearchWorkerManager manager = new SearchWorkerManager();
		manager.createWorkers(context, List.of(new PlaceWorker(context)));
		assertTrue(manager.start(), "the search had nothing to run");

		for (int turn = 0; turn < TURN_LIMIT && manager.hasWork(); turn++) {
			manager.doWork();
		}

		assertNotNull(context.locatedPos, "the search located nothing");
		alreadyFound.add(context.locatedPos);
		return context.locatedPos;
	}

	@Test
	void eachSearchForAFurtherInstanceAnswersWithSomewhereNew() {
		// The compass carries these from one search to the next, and a fresh search starts with none
		final List<BlockPos> alreadyFound = new ArrayList<BlockPos>();

		assertEquals(PLACES.get(0), search(alreadyFound, false));
		assertEquals(PLACES.get(1), search(alreadyFound, true), "the second search answered with what the first one had already found");
		assertEquals(PLACES.get(2), search(alreadyFound, true), "the third search went back to somewhere already found");
		assertEquals(3, alreadyFound.size());
	}

	@Test
	void searchingAgainFromWhereTheLastOneLandedStillMovesOn() {
		// Having travelled to what was found is the usual way of asking for the next one, and where the
		// player is standing must not be answered with either
		final List<BlockPos> alreadyFound = new ArrayList<BlockPos>();
		assertEquals(PLACES.get(0), search(alreadyFound, false));

		final HeadlessContext context = new HeadlessContext(alreadyFound, true);
		assertTrue(context.isAlreadyLocated(PLACES.get(0), 32));
		assertFalse(context.isAlreadyLocated(PLACES.get(1), 32));
	}

}
