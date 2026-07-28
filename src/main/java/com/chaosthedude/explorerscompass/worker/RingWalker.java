package com.chaosthedude.explorerscompass.worker;

/**
 * Walks the cells of a square grid outwards from the one at its centre, one ring at a time.
 *
 * <p>Only the cells on the ring itself are visited. The ones it encloses were covered by the
 * smaller rings before it, and stepping over them instead of walking them matters: a ring of the
 * given length has about 8 * length cells on it, but encloses roughly 4 * length * length of them.
 */
public class RingWalker {

	private int x;
	private int z;
	private int length;

	/** How many cells east of the centre the current cell lies, negative for west of it. */
	public int getX() {
		return x;
	}

	/** How many cells south of the centre the current cell lies, negative for north of it. */
	public int getZ() {
		return z;
	}

	/**
	 * How many cells out from the centre have been walked all the way around. A cell of the ring
	 * being walked is at least this many cells from the centre, so everything nearer than that has
	 * already been visited.
	 */
	public int getCoveredLength() {
		return Math.max(0, length - 1);
	}

	/**
	 * Moves to the next cell on the ring being walked, growing to the next ring once it is done.
	 *
	 * <p>Both coordinates have to be reset to -length using the length of the ring they are about to
	 * walk, otherwise x never reaches -length and the entire western column of every ring is
	 * skipped.
	 */
	public void advance() {
		if (x == -length || x == length) {
			// Every cell of the westmost and eastmost rows is on the ring
			z++;
		} else {
			// Of the rows in between, only the two ends are
			z = z < length ? length : length + 1;
		}

		if (z > length) {
			x++;
			z = -length;
			if (x > length) {
				length++;
				x = -length;
				z = -length;
			}
		}
	}

}
