package com.chaosthedude.explorerscompass.worker;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.items.ExplorersCompassItem;

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.util.Mth;

/**
 * The regions of the biomes being searched for that earlier searches already answered with.
 *
 * <p>A structure stands at a point, so a search for a further instance of one only has to pass over
 * the ground right around what it already found. A biome is not a point but a region, and one of
 * them is routinely thousands of blocks across, so passing over a fixed distance around the location
 * the compass was pointed at leaves the search answering with the next sample beyond it — still
 * inside the very same ocean, plains or taiga it was told to look past. What has to be passed over
 * is the whole region, and the only thing that says where one ends is where the biome does.
 *
 * <p>So each location an earlier search answered with is walked outwards from, one sample of the
 * grid the search itself walks at a time, for as long as the samples keep coming back as one of the
 * biomes being looked for. What that reaches is the region that was already found, and a search for
 * a further instance passes over all of it and answers with the next region along.
 *
 * <p>Asking whether a location is one of those biomes reads no part of the world — which biome
 * generates somewhere follows from the world seed and the generator's noise alone — so this runs on
 * the threads a biome search is given, before they start walking. A search with nothing to pass over
 * builds nothing and pays for nothing.
 *
 * <p>Both layers a biome search is made of share one of these. They walk grids of different
 * fineness, but the coarser one steps by a whole number of the finer one's samples from the same
 * place, so every location either of them looks at falls on the grid this is built on.
 */
final class LocatedBiomePatches {

	/**
	 * How many samples of the grid one of these may cover in total. A region larger than this is
	 * passed over as far as this reaches and no further, which is reported rather than left to be
	 * noticed as a search answering with somewhere oddly close to the last one.
	 *
	 * <p>At the default spacing this is a region some four thousand blocks across, which is larger
	 * than the biomes a world generates; it is here so that a data pack whose ocean covers a
	 * continent cannot have a search spend the whole of itself working out where that ocean ends.
	 */
	private static final int MAX_CELLS = 20000;

	/** How many samples one turn of a search running on the server thread spends on this. */
	static final int CELLS_PER_TURN = 1;

	/** No region has been walked yet, so no height has been settled on. */
	private static final int NO_HEIGHT = Integer.MIN_VALUE;

	/**
	 * Whether a location is one of the biomes being searched for. The one thing walking a region has
	 * to ask about the world, kept apart from the walking itself so that what a region is can be
	 * settled without a world to ask.
	 */
	interface BiomeProbe {

		boolean isTarget(int blockX, int blockZ, int quartY);

	}

	private final String searchId;
	private final BiomeProbe probe;

	// The grid this is built on: where it is anchored, how far apart its samples are, and which
	// heights a region may be walked at
	private final int startX;
	private final int startZ;
	private final int spacing;
	private final int searchQuartY;
	private final int minQuartY;
	private final int maxQuartY;

	/** The locations earlier searches answered with, each still to be walked outwards from. */
	private final List<BlockPos> seeds;
	private int nextSeed;

	// The walk in progress: which samples belong to a region already found, which have been looked
	// at at all, and which are still to be walked out from
	private final LongOpenHashSet patchCells = new LongOpenHashSet();
	private final LongOpenHashSet visited = new LongOpenHashSet();
	private final LongArrayFIFOQueue frontier = new LongArrayFIFOQueue();
	/**
	 * The height the region being walked was found at, or {@link #NO_HEIGHT} before the first one.
	 * Which locations have already been looked at only answers for the height they were looked at, so
	 * the regions are walked a height at a time and what was looked at is dropped between them.
	 */
	private int currentQuartY = NO_HEIGHT;

	private int samples;
	private boolean capped;

	/**
	 * That the regions are known. Published last, so that everything the thread which worked them out
	 * wrote is visible to the ones that go on to read them.
	 */
	private volatile boolean built;

	LocatedBiomePatches(SearchContext context, BiomeProbe probe, int spacing, int searchQuartY, int minQuartY, int maxQuartY) {
		this.searchId = context.getId();
		this.probe = probe;
		this.spacing = spacing;
		this.searchQuartY = searchQuartY;
		this.minQuartY = minQuartY;
		this.maxQuartY = maxQuartY;

		startX = context.getStartPos().getX();
		startZ = context.getStartPos().getZ();

		// Taken as they stand when the search starts: the location this one settles on is added to the
		// compass's list afterwards, and by then there is nothing left to ask
		seeds = new ArrayList<BlockPos>(context.getAlreadyLocated());
		// Walked a height at a time, so that what has already been looked at is dropped once per height
		// rather than once per region
		seeds.sort(Comparator.comparingInt(this::quartYOf));
		built = seeds.isEmpty();
	}

	/** Whether the regions already found are known, so that a search may start passing over them. */
	boolean isBuilt() {
		return built;
	}

	/**
	 * Works out the regions in full. Called by every thread a biome search is given before it starts
	 * walking: the first of them does the work and the rest wait here rather than each doing it again.
	 */
	synchronized void build() {
		advance(Integer.MAX_VALUE);
	}

	/**
	 * Covers up to the given number of samples of the regions, and answers whether they are now known
	 * in full. This is what a search that is not allowed threads of its own spends its turns on until
	 * there is nothing left to work out.
	 */
	synchronized boolean advance(int cellBudget) {
		if (built) {
			return true;
		}

		int spent = 0;
		while (spent < cellBudget) {
			if (patchCells.size() >= MAX_CELLS) {
				capped = true;
				break;
			}
			if (frontier.isEmpty()) {
				if (nextSeed >= seeds.size()) {
					break;
				}
				spent += beginRegion(seeds.get(nextSeed++));
				continue;
			}
			// A sample every one around it has already been looked at costs nothing, but a turn still
			// has to end, so walking out from one counts for something either way
			spent += Math.max(1, expand(frontier.dequeueLong()));
		}

		if (spent < cellBudget) {
			finish();
			return true;
		}
		return false;
	}

	/** Whether the given location lies in a region an earlier search already answered with. */
	boolean contains(int blockX, int blockZ) {
		if (patchCells.isEmpty()) {
			return false;
		}
		return patchCells.contains(cellKey(cellIndex(blockX - startX), cellIndex(blockZ - startZ)));
	}

	/** How many locations of the regions already found this covered. */
	int getCellCount() {
		return patchCells.size();
	}

	/**
	 * Starts walking outwards from one location an earlier search answered with. A location that no
	 * longer samples as one of the biomes being looked for has no region to pass over: the search may
	 * have been started somewhere else, leaving it between two samples of this grid, or what is being
	 * looked for may have changed since. There the distance a search passes over anyway is all there
	 * is, which is what it was before any of this.
	 */
	private int beginRegion(BlockPos located) {
		final int quartY = quartYOf(located);
		if (quartY != currentQuartY) {
			// What has been looked at says nothing about a height it was not looked at from: a location
			// the surface of the world reaches is not where a biome filling the caves under it ends
			visited.clear();
			currentQuartY = quartY;
		}

		final long cell = cellKey(cellIndex(located.getX() - startX), cellIndex(located.getZ() - startZ));
		if (!visited.add(cell)) {
			// Already reached while walking a region found at this same height
			return 1;
		}

		if (samplesToTarget(cell)) {
			patchCells.add(cell);
			frontier.enqueue(cell);
		}
		return 1;
	}

	/** Looks at the samples around one that belongs to a region, and takes in the ones that also do. */
	private int expand(long cell) {
		final int cellX = cellX(cell);
		final int cellZ = cellZ(cell);
		int taken = 0;
		for (int side = 0; side < 4; side++) {
			final long neighbour = cellKey(cellX + (side == 0 ? 1 : side == 1 ? -1 : 0),
					cellZ + (side == 2 ? 1 : side == 3 ? -1 : 0));
			if (!visited.add(neighbour)) {
				continue;
			}

			taken++;
			if (samplesToTarget(neighbour)) {
				patchCells.add(neighbour);
				frontier.enqueue(neighbour);
			}
		}
		return taken;
	}

	private boolean samplesToTarget(long cell) {
		samples++;
		return probe.isTarget(startX + cellX(cell) * spacing, startZ + cellZ(cell) * spacing, currentQuartY);
	}

	/**
	 * The height a region is walked at. A location an earlier search could not tell the height of was
	 * found at the height that search was started at, which is where the surface of the world is.
	 */
	private int quartYOf(BlockPos located) {
		if (located.getY() == ExplorersCompassItem.UNKNOWN_Y) {
			return searchQuartY;
		}
		return Mth.clamp(QuartPos.fromBlock(located.getY()), minQuartY, maxQuartY);
	}

	private void finish() {
		frontier.clear();
		visited.clear();
		// Published after everything it stands for has been written, so a thread that sees this set
		// sees the regions in full
		built = true;
		if (patchCells.isEmpty()) {
			return;
		}

		ExplorersCompass.LOGGER.info("Search " + searchId + ": passing over " + getCellCount()
				+ " locations of the regions already found, worked out from " + samples + " samples"
				+ (capped ? ", having reached the most it may cover before it had walked all of them, so a location further into one of them can still be answered with" : ""));
	}

	/**
	 * Which sample of the grid a location belongs to. Locations a search looks at fall exactly on the
	 * grid; one an earlier search answered with need not, since the search that found it started
	 * somewhere else, so the nearest sample stands for it.
	 */
	private int cellIndex(int distanceFromStart) {
		return Math.floorDiv(distanceFromStart + spacing / 2, spacing);
	}

	private static long cellKey(int cellX, int cellZ) {
		return ((long) cellX << 32) | (cellZ & 0xFFFFFFFFL);
	}

	private static int cellX(long cell) {
		return (int) (cell >> 32);
	}

	private static int cellZ(long cell) {
		return (int) cell;
	}

}
