package com.chaosthedude.explorerscompass.worker;

/**
 * Walks the cells of a square grid outwards from the one at its centre, nearest first.
 *
 * <p>The cells are visited in bands of their distance from the centre: every cell between one and
 * two cells out, then every cell between two and three, and so on. Within a band the order is
 * arbitrary, but a cell of the band being walked is never nearer than one of a band already walked.
 *
 * <p>That ordering is what lets a search stop on its first find. Walking square rings instead visits
 * the corners of a ring, which lie a further factor of the square root of two out, before the sides
 * of the ring beyond it; a find on a corner is therefore not the nearest one, and the search has to
 * carry on until it has covered as far as that corner — up to half as much ground again as it had
 * already walked to reach it. It also visits the corners at all: a square of side twice the radius
 * holds about a quarter more cells than the disc it encloses, and every one of them is outside the
 * radius the search was given.
 *
 * <p>A walk can be given one stripe of the rows to walk rather than all of them, which is how a
 * search spreads itself over several threads: as many walks as there are threads, each taking every
 * so many rows. Skipping a row costs one division, where skipping its cells one at a time would cost
 * as much as walking them, and the rows of a band alternate between the threads often enough that
 * none of them ends up with the long ones.
 */
public class DiscWalker {

	// Which rows this walk takes, out of how many the rows are shared between
	private final int stripes;
	private final int stripe;

	/** Which band is being walked, in cells. */
	private int band;

	/** The cell being visited. */
	private int x;
	private int z;

	// The run of cells along the current row that is being walked, and the one after it. A row of a
	// band is one run through the middle of it while the band still encloses the centre, and two
	// either side of the hole once it no longer does.
	private int runEnd;
	private boolean runPending;
	private int pendingRunStart;
	private int pendingRunEnd;

	/** A walk over every cell. The centre is the whole of the first band, so it starts there. */
	public DiscWalker() {
		this(1, 0);
	}

	/**
	 * A walk over one stripe of the rows: those whose distance east of the centre leaves the given
	 * remainder when divided by the number of stripes.
	 */
	public DiscWalker(int stripes, int stripe) {
		this.stripes = stripes;
		this.stripe = stripe;
		// One before the first row, which is where seeking the next one starts from
		x = -1;
		seekRow();
	}

	/** How many cells east of the centre the current cell lies, negative for west of it. */
	public int getX() {
		return x;
	}

	/** How many cells south of the centre the current cell lies, negative for north of it. */
	public int getZ() {
		return z;
	}

	/**
	 * How far out from the centre this walk has covered all the way round, in cells. Every cell of it
	 * nearer than this has been visited, and every cell still to come is at least this far out, so a
	 * search that has covered this far can no longer improve on anything it found within it.
	 *
	 * <p>A walk over one stripe of the rows answers for its own rows alone, so a search made of
	 * several of them has covered as far as the least of what they answer.
	 */
	public int getCoveredLength() {
		return band;
	}

	/** Moves to the next cell, growing to the next band once the current one is done. */
	public void advance() {
		if (z < runEnd) {
			z++;
			return;
		}

		if (runPending) {
			runPending = false;
			z = pendingRunStart;
			runEnd = pendingRunEnd;
			return;
		}

		seekRow();
	}

	/**
	 * Moves to the first cell of the next row this walk takes that holds any, growing to the next band
	 * once the rows run out.
	 *
	 * <p>Rows are stepped over rather than visited both when they belong to another stripe and when
	 * they hold no cell of this band, which the rows nearest the centre no longer do once the band has
	 * grown past them.
	 */
	private void seekRow() {
		while (true) {
			x++;
			if (x > band) {
				band++;
				x = -band;
			}
			if (Math.floorMod(x, stripes) == stripe && beginRow()) {
				return;
			}
		}
	}

	/**
	 * Works out which cells of the row the walk has reached belong to the band being walked, and
	 * moves to the first of them. Returns false when the row holds none, which leaves the walk where
	 * it was for the caller to step past.
	 */
	private boolean beginRow() {
		// A cell belongs to this band when its squared distance is at least the band's and less than
		// the next one's. Working in squared distances keeps this exact: both are whole numbers, so no
		// cell can fall between two bands or into both of them.
		final long innerSqr = (long) band * band;
		final long outerSqr = (long) (band + 1) * (band + 1) - 1L;
		final long rowSqr = (long) x * x;
		if (rowSqr > outerSqr) {
			return false;
		}

		final int outer = squareRootBelow(outerSqr - rowSqr);
		final int inner = rowSqr >= innerSqr ? 0 : squareRootAbove(innerSqr - rowSqr);
		if (inner > outer) {
			return false;
		}

		if (inner == 0) {
			// The band still reaches the middle of this row, so it is one run all the way across
			z = -outer;
			runEnd = outer;
			runPending = false;
		} else {
			// The bands already walked have taken the middle of the row, leaving one run either side
			z = -outer;
			runEnd = -inner;
			runPending = true;
			pendingRunStart = inner;
			pendingRunEnd = outer;
		}
		return true;
	}

	/** The largest whole number whose square is at most the given value. */
	private static int squareRootBelow(long value) {
		// Taken from the square root of a double and then corrected, since that is only exact up to
		// the precision a double has and these are compared against for every row of every band
		int root = (int) Math.sqrt((double) value);
		while (root > 0 && (long) root * root > value) {
			root--;
		}
		while ((long) (root + 1) * (root + 1) <= value) {
			root++;
		}
		return root;
	}

	/** The smallest whole number whose square is at least the given value. */
	private static int squareRootAbove(long value) {
		final int root = squareRootBelow(value);
		return (long) root * root == value ? root : root + 1;
	}

}
