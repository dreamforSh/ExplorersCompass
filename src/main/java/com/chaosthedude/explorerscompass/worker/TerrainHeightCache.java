package com.chaosthedude.explorerscompass.worker;

import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.world.level.levelgen.Heightmap;

/**
 * How high the ground reaches at a column, held for as long as one location of a search is being
 * looked at.
 *
 * <p>Working out whether a structure would generate somewhere starts by working out where the ground
 * is, which runs the generator's noise down a column. A set of structures sharing one placement is
 * asked about the same location one structure at a time, and each of them asks about the same few
 * columns of it, so without this a search for every kind of village works the same columns out five
 * times over.
 *
 * <p>Held for one location at a time rather than for the whole search. Which is enough — the
 * locations a search looks at are a placement's grid apart, so no two of them share a column — and
 * it is also what makes this safe: an entry is only ever read by the same run of the same search
 * that wrote it, and never outlives the dimension it was worked out in.
 *
 * <p>Reached from {@code ChunkGeneratorTerrainHeightMixin}, which sits on a method the whole game
 * uses. A thread that is not searching finds nothing here, and one running while nobody searches at
 * all pays a single field read.
 */
public final class TerrainHeightCache {

	/** That nothing has been worked out for a column. Below any height a world can have. */
	public static final int UNKNOWN = Integer.MIN_VALUE;

	/**
	 * How many columns one location's worth of this holds. A structure asks about the column at the
	 * middle of its chunk, or the four corners of the box it would stand in, so a handful of
	 * structures sharing a location come nowhere near this; one that somehow did would simply stop
	 * being held rather than start being wrong.
	 */
	private static final int CAPACITY = 64;

	/** How many searches are holding heights anywhere. Read before anything else is touched. */
	private static final AtomicInteger searching = new AtomicInteger();

	/** What the thread currently looking at a location has worked out, if it is one of ours. */
	private static final ThreadLocal<TerrainHeightCache> columns = new ThreadLocal<TerrainHeightCache>();

	private final long[] keys = new long[CAPACITY];
	private final int[] heights = new int[CAPACITY];
	private int size;

	private TerrainHeightCache() {
	}

	/**
	 * Starts holding heights on the thread that calls this. Must be paired with {@link #stop()} in a
	 * finally: a thread that went on holding them would answer for one dimension out of another.
	 */
	static void start() {
		columns.set(new TerrainHeightCache());
		searching.incrementAndGet();
	}

	static void stop() {
		columns.remove();
		searching.decrementAndGet();
	}

	/** Forgets the last location, now that a search has moved on to the next one. */
	static void nextLocation() {
		final TerrainHeightCache held = columns.get();
		if (held != null) {
			held.size = 0;
		}
	}

	/** How high the ground reaches at a column, or {@link #UNKNOWN} when nothing has worked it out. */
	public static int get(int blockX, int blockZ, Heightmap.Types type) {
		if (searching.get() == 0) {
			return UNKNOWN;
		}

		final TerrainHeightCache held = columns.get();
		if (held == null) {
			return UNKNOWN;
		}

		final long key = key(blockX, blockZ, type);
		for (int i = 0; i < held.size; i++) {
			if (held.keys[i] == key) {
				return held.heights[i];
			}
		}
		return UNKNOWN;
	}

	/** Takes note of how high the ground reaches at a column. */
	public static void put(int blockX, int blockZ, Heightmap.Types type, int height) {
		if (searching.get() == 0) {
			return;
		}

		final TerrainHeightCache held = columns.get();
		if (held == null || held.size >= CAPACITY) {
			return;
		}

		held.keys[held.size] = key(blockX, blockZ, type);
		held.heights[held.size] = height;
		held.size++;
	}

	/**
	 * What tells one column and heightmap apart from another. The coordinates are cut down to the
	 * range a world can reach, which is far inside what a world border allows, so no two columns a
	 * search can look at come out the same.
	 */
	private static long key(int blockX, int blockZ, Heightmap.Types type) {
		return ((long) (blockX & 0x3FFFFFF) << 32) | ((long) (blockZ & 0x3FFFFFF) << 6) | type.ordinal();
	}

}
