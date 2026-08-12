package com.chaosthedude.explorerscompass.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class DiscWalkerTest {

	private static final int CELLS = 8;

	/** Far more cells than the bands walked here hold, so a walk that never grows fails the test. */
	private static final int CELL_LIMIT = 10000;

	@Test
	void theWalkStartsWhereTheSearchDid() {
		final DiscWalker walker = new DiscWalker();

		// A biome search samples where it was started like any other location, so standing in what is
		// being looked for has to answer with the place being stood in
		assertEquals(0, walker.getX());
		assertEquals(0, walker.getZ());
	}

	@Test
	void everyCellOutToTheBandBeingWalkedIsVisitedExactlyOnce() {
		final DiscWalker walker = new DiscWalker();
		final Set<String> visited = new HashSet<String>();

		for (int i = 0; i < CELL_LIMIT && walker.getCoveredLength() <= CELLS; i++) {
			final String cell = walker.getX() + "," + walker.getZ();
			assertTrue(visited.add(cell), "cell " + cell + " walked twice");
			walker.advance();
		}

		// A cell walked twice is a location sampled twice, and one never walked is somewhere a search
		// would report nothing for however long it ran
		assertTrue(walker.getCoveredLength() > CELLS, "the walk never covered " + CELLS + " cells");
		for (int x = -CELLS; x <= CELLS; x++) {
			for (int z = -CELLS; z <= CELLS; z++) {
				if (x * x + z * z <= CELLS * CELLS) {
					assertTrue(visited.contains(x + "," + z), "cell " + x + "," + z + " never walked");
				}
			}
		}
	}

	@Test
	void nothingIsWalkedBeforeSomethingNearerThanIt() {
		final DiscWalker walker = new DiscWalker();

		for (int i = 0; i < CELL_LIMIT && walker.getCoveredLength() <= CELLS; i++) {
			final int band = walker.getCoveredLength();
			final long distanceSqr = distanceSqr(walker);
			// The whole point of walking in bands: a cell of the band being walked is at least as far
			// out as every cell already walked, so the first find is the nearest one there is and the
			// search can stop on it
			assertTrue(distanceSqr >= (long) band * band, "cell " + cell(walker) + " is nearer than the covered length " + band);
			assertTrue(distanceSqr < (long) (band + 1) * (band + 1), "cell " + cell(walker) + " is further out than the band being walked");
			walker.advance();
		}
	}

	@Test
	void theCoveredLengthOnlyGrows() {
		final DiscWalker walker = new DiscWalker();
		int covered = 0;

		for (int i = 0; i < CELL_LIMIT && walker.getCoveredLength() <= CELLS; i++) {
			// A search bounds itself by this and reports it to the compass, so a value that dropped back
			// would both search ground it had already covered and read as the search having restarted
			assertTrue(walker.getCoveredLength() >= covered, "the covered length dropped back from " + covered);
			covered = walker.getCoveredLength();
			walker.advance();
		}
	}

	@Test
	void theStripesOfAWalkShareOutEveryCellBetweenThem() {
		final int stripes = 4;
		final Set<String> visited = new HashSet<String>();

		for (int stripe = 0; stripe < stripes; stripe++) {
			final DiscWalker walker = new DiscWalker(stripes, stripe);
			for (int i = 0; i < CELL_LIMIT && walker.getCoveredLength() <= CELLS; i++) {
				final String cell = cell(walker);
				// Each thread of a search walks one of these, so a cell two of them walk is a location
				// sampled twice over, and one none of them walks is a location never sampled at all
				assertTrue(visited.add(cell), "cell " + cell + " walked by more than one stripe");
				walker.advance();
			}
		}

		for (int x = -CELLS; x <= CELLS; x++) {
			for (int z = -CELLS; z <= CELLS; z++) {
				if (x * x + z * z <= CELLS * CELLS) {
					assertTrue(visited.contains(x + "," + z), "cell " + x + "," + z + " walked by no stripe");
				}
			}
		}
	}

	@Test
	void aStripeOnlyWalksItsOwnRows() {
		final int stripes = 3;
		final int stripe = 2;
		final DiscWalker walker = new DiscWalker(stripes, stripe);

		for (int i = 0; i < CELL_LIMIT && walker.getCoveredLength() <= CELLS; i++) {
			assertEquals(stripe, Math.floorMod(walker.getX(), stripes), "cell " + cell(walker) + " is not on a row of this stripe");
			walker.advance();
		}
	}

	private static long distanceSqr(DiscWalker walker) {
		return (long) walker.getX() * walker.getX() + (long) walker.getZ() * walker.getZ();
	}

	private static String cell(DiscWalker walker) {
		return walker.getX() + "," + walker.getZ();
	}

}
