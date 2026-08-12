package com.chaosthedude.explorerscompass.worker;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;

class SearchContextTest {

	private static final BlockPos START = new BlockPos(0, 64, 0);
	private static final int SAME_LOCATION_DISTANCE = 32;

	/** Wide enough that the locations below land in cells of their own. */
	private static final int CELL_SIZE = 512;

	private static SearchContext contextOf(boolean ignoreNearStart, BlockPos... located) {
		return new SearchContext(null, null, null, START, new ArrayList<BlockPos>(List.of(located)), false, ignoreNearStart, 10000, false);
	}

	@Test
	void aLocationNearOneAlreadyFoundIsTheSameOne() {
		final SearchContext context = contextOf(false, new BlockPos(4000, 0, 4000));

		assertTrue(context.isAlreadyLocated(new BlockPos(4000, 0, 4000), SAME_LOCATION_DISTANCE));
		assertTrue(context.isAlreadyLocated(new BlockPos(4020, 0, 4000), SAME_LOCATION_DISTANCE));
	}

	@Test
	void aLocationPastTheDistanceIsADifferentOne() {
		final SearchContext context = contextOf(false, new BlockPos(4000, 0, 4000));

		assertFalse(context.isAlreadyLocated(new BlockPos(4033, 0, 4000), SAME_LOCATION_DISTANCE));
	}

	@Test
	void aLocationIsStillFoundAcrossTheEdgeOfACell() {
		// The two lie either side of the boundary the locations are bucketed on, which a search that
		// only looked in the bucket the location asked about falls into would answer wrongly for
		final SearchContext context = contextOf(false, new BlockPos(CELL_SIZE - 1, 0, CELL_SIZE - 1));

		assertTrue(context.isAlreadyLocated(new BlockPos(CELL_SIZE + 1, 0, CELL_SIZE + 1), SAME_LOCATION_DISTANCE));
	}

	@Test
	void aLocationIsStillFoundAcrossTheOrigin() {
		// Bucketing rounds towards negative infinity, so this is the same case again on the side where
		// getting it wrong would be easy
		final SearchContext context = contextOf(false, new BlockPos(-2, 0, -2));

		assertTrue(context.isAlreadyLocated(new BlockPos(2, 0, 2), SAME_LOCATION_DISTANCE));
	}

	@Test
	void everyLocationAlreadyFoundIsStillConsidered() {
		final List<BlockPos> located = new ArrayList<BlockPos>();
		for (int i = 1; i <= 64; i++) {
			located.add(new BlockPos(i * CELL_SIZE * 3, 0, 0));
		}
		final SearchContext context = contextOf(false, located.toArray(new BlockPos[0]));

		// Bucketing must not lose the ones that are not near what is being asked about
		for (BlockPos pos : located) {
			assertTrue(context.isAlreadyLocated(pos, SAME_LOCATION_DISTANCE), "forgot " + pos);
		}
		assertFalse(context.isAlreadyLocated(new BlockPos(CELL_SIZE * 3 / 2, 0, 0), SAME_LOCATION_DISTANCE));
	}

	@Test
	void whereTheSearchStartedCountsOnlyWhenItIsMeantTo() {
		assertTrue(contextOf(true).isAlreadyLocated(START, SAME_LOCATION_DISTANCE));
		// A fresh search answers with the nearest, which may well be the one being stood in
		assertFalse(contextOf(false).isAlreadyLocated(START, SAME_LOCATION_DISTANCE));
	}

}
