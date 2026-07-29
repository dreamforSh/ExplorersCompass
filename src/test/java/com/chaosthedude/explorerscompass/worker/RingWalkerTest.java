package com.chaosthedude.explorerscompass.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class RingWalkerTest {

	private static final int RINGS = 6;

	@Test
	void everyCellOutToTheRingBeingWalkedIsVisitedExactlyOnce() {
		final RingWalker walker = new RingWalker();
		final Set<String> visited = new HashSet<String>();
		final int cells = (2 * RINGS + 1) * (2 * RINGS + 1);

		for (int i = 0; i < cells; i++) {
			final String cell = walker.getX() + "," + walker.getZ();
			assertTrue(visited.add(cell), "cell " + cell + " walked twice");
			assertTrue(ring(walker) <= RINGS, "cell " + cell + " is past ring " + RINGS);
			walker.advance();
		}

		// A ring is only worth walking once, and skipping the cells it encloses is the point of it, so
		// the cells walked have to be all of them and each of them once
		assertEquals(cells, visited.size());
		for (int x = -RINGS; x <= RINGS; x++) {
			for (int z = -RINGS; z <= RINGS; z++) {
				assertTrue(visited.contains(x + "," + z), "cell " + x + "," + z + " never walked");
			}
		}
	}

	@Test
	void theCoveredLengthOnlyGrowsAndNeverReachesTheRingBeingWalked() {
		final RingWalker walker = new RingWalker();
		int covered = 0;

		for (int i = 0; i < (2 * RINGS + 1) * (2 * RINGS + 1); i++) {
			assertTrue(walker.getCoveredLength() >= covered, "the covered length dropped back");
			covered = walker.getCoveredLength();
			// Everything nearer than the covered length has to have been walked already, which is what
			// lets a search bound itself by it
			assertTrue(ring(walker) >= covered, "cell " + walker.getX() + "," + walker.getZ() + " is nearer than the covered length " + covered);
			walker.advance();
		}

		// The last cell walked is on the outermost ring, which is not covered until it has been walked
		// all the way around
		assertEquals(RINGS - 1, covered);
	}

	private static int ring(RingWalker walker) {
		return Math.max(Math.abs(walker.getX()), Math.abs(walker.getZ()));
	}

}
