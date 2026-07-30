package com.chaosthedude.explorerscompass.worker;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.util.StructureUtils;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

/**
 * Everything a search does apart from deciding where to look and what counts as a find: it walks
 * outwards from where the search started, a location at a time, until it has covered as far as the
 * nearest location it turned up, runs out of the radius or the samples it was allowed, or is
 * stopped.
 *
 * <p>A worker only ever samples. It is given its turns by {@link SearchWorkerManager}, which is
 * what the server thread schedules, and everything the outcome of a search touches — the compass,
 * the locations already found — belongs to {@link SearchContext} and is written to from there. A
 * worker therefore holds nothing of its own that another thread could not read.
 */
public abstract class SearchWorker {

	/**
	 * How close a location has to be to an already located one, or to where a search for a further
	 * instance started, to count as the same find. Two chunks, so that walking around inside a
	 * structure and searching again does not just point at the one being stood in.
	 */
	private static final int SAME_LOCATION_DISTANCE = 32;

	protected final SearchContext context;

	// Read straight off the context for the sampling loop, which reaches for them constantly
	protected final ServerLevel level;
	protected final BlockPos startPos;
	protected final int maxRadius;
	protected final int maxSamples;

	protected BlockPos currentPos;
	protected int samples;
	/** Set by the server thread to stop a search, and read by whichever thread is sampling. */
	protected volatile boolean finished;

	// The nearest location this worker has turned up so far. A worker carries on sampling after a
	// find until it has covered as far as that location: the cells of a ring are walked row by row,
	// so the first cell of a ring to hold something is not necessarily the nearest one on it.
	private volatile BlockPos bestPos;
	private volatile ResourceLocation bestKey;
	private volatile long bestDistanceSqr;

	/**
	 * How far this worker may ever search. It starts out as the configured maximum and is narrowed
	 * down once something has been located, by this worker or by another one of the same search,
	 * since a worker that has covered that far can no longer improve on it. Reaching this is the end
	 * of the worker.
	 */
	private volatile int radiusLimit;

	/**
	 * How far the turn this worker is currently taking searches. The manager widens this once every
	 * worker of the search has reached it; reaching it only ends the turn, not the worker.
	 */
	private volatile int bandLimit;

	/** Whether this worker has been given its first turn yet. */
	private boolean begun;

	public SearchWorker(SearchContext context, int maxSamples) {
		this.context = context;
		this.maxSamples = maxSamples;

		level = context.getLevel();
		startPos = context.getStartPos();
		maxRadius = context.getMaxRadius();

		currentPos = startPos;
		samples = 0;

		radiusLimit = maxRadius;
		bandLimit = maxRadius;
	}

	/**
	 * Puts this worker to work, the first time it is given a turn. Does nothing on the turns after
	 * that, so the manager can call it whenever the turn comes round.
	 */
	final void begin() {
		if (begun) {
			return;
		}

		begun = true;
		ExplorersCompass.LOGGER.info("Search " + context.getId() + ": " + getName() + " starting with " + (shouldLogRadius() ? getEffectiveRadiusLimit() + " max radius, " : "") + maxSamples + " max samples");
		onBegin();
	}

	/** Whatever this kind of worker has to set going before it can sample. */
	protected void onBegin() {
	}

	/**
	 * Whether this worker can sample right now. One that is waiting for something of its own, such
	 * as the positions a placement is still computing, cannot, and the manager gives the turn to
	 * another worker of the search rather than letting it hold everything else up.
	 */
	boolean isReady() {
		return true;
	}

	/**
	 * How many locations this worker samples before the turn passes on.
	 *
	 * <p>Handing the turn back costs something of its own: the clock the budget is measured against is
	 * read twice, the settings are consulted, and every search on the server is looked over. Paid for
	 * every single location, that is a large part of what a cheap sample costs at all. It is only
	 * worth batching so far, though — nothing else on the server thread runs until the batch is done,
	 * so a worker answers with a count its most expensive sample still fits a batch of inside a tick.
	 */
	int getSamplesPerTurn() {
		return 1;
	}

	/** Whether there is anything left for this worker to sample under the limits it currently has. */
	boolean hasWork() {
		return hasMoreToSample();
	}

	protected boolean hasMoreToSample() {
		return !finished && getRadius() < getEffectiveRadiusLimit() && samples < maxSamples;
	}

	/**
	 * Whether this worker is finished for good, rather than having only reached the end of the turn
	 * it was taking.
	 */
	protected boolean isExhausted() {
		return finished || getRadius() >= radiusLimit || samples >= maxSamples;
	}

	final boolean doWork() {
		try {
			return doSample();
		} catch (Throwable t) {
			// Sampling touches world generation and chunk storage, both of which can fail for a single
			// structure or chunk. Report the search as over instead of letting the exception escape into
			// the server tick, where it would take down the server.
			abort(t);
			return false;
		}
	}

	/**
	 * Samples a single location. Only called when this worker is ready and has work, and returns
	 * true if it could carry straight on to the next one.
	 */
	protected abstract boolean doSample();

	/**
	 * Cuts this worker down to the given radius for good. Called when something has been located,
	 * since anything this one turns up beyond that distance cannot be the answer.
	 */
	void setRadiusLimit(int limit) {
		radiusLimit = Math.min(radiusLimit, limit);
	}

	/** Sets how far the turn this worker is about to take searches. */
	void setBandLimit(int limit) {
		bandLimit = limit;
	}

	/** How far this worker may search before it has to hand back, for whichever reason. */
	protected int getEffectiveRadiusLimit() {
		return Math.min(radiusLimit, bandLimit);
	}

	/**
	 * Whether a location has already been located by an earlier search, and should be passed over so
	 * that searching again finds a different instance.
	 */
	protected boolean shouldIgnore(BlockPos pos) {
		return context.isAlreadyLocated(pos, getSameLocationDistance());
	}

	/**
	 * How near a location has to be to an already located one to count as the same find. What is
	 * being searched for decides this: a structure is a single place, while a biome is a region a
	 * search can sample thousands of times over.
	 */
	protected int getSameLocationDistance() {
		return SAME_LOCATION_DISTANCE;
	}

	/**
	 * Whether a location is inside the configured search radius. Locations outside it are not
	 * sampled: the search is not meant to reach that far, and something found there could not be
	 * reported.
	 */
	protected boolean isWithinMaxRadius(int x, int z) {
		return context.distanceSqrFromStart(x, z) <= (long) maxRadius * maxRadius;
	}

	/**
	 * Whether a location is worth sampling at all: no further out than this worker may search, and
	 * nearer than whatever it has already turned up. The radius limit carries in what the rest of the
	 * search has located as well, so a location another worker has already beaten is not sampled
	 * either. Walking on past a find costs almost nothing this way, while what is left of the ring
	 * the find was made on is still covered.
	 */
	protected boolean isWorthSampling(int x, int z) {
		final long distanceSqr = context.distanceSqrFromStart(x, z);
		if (distanceSqr > (long) radiusLimit * radiusLimit) {
			return false;
		}
		return bestPos == null || distanceSqr < bestDistanceSqr;
	}

	/**
	 * Takes note of a location this worker has turned up. It is not the answer yet: what is left of
	 * the ring it was found on may hold a nearer one, and so may a worker searching a different
	 * placement.
	 */
	protected void found(BlockPos pos, ResourceLocation key) {
		final long distanceSqr = context.distanceSqrFromStart(pos.getX(), pos.getZ());
		if (bestPos != null && distanceSqr >= bestDistanceSqr) {
			return;
		}

		ExplorersCompass.LOGGER.info("Search " + context.getId() + ": " + getName() + " located " + key + " with " + (shouldLogRadius() ? getRadius() + " radius, " : "") + samples + " samples");
		bestPos = pos;
		bestKey = key;
		bestDistanceSqr = distanceSqr;
		// Nothing further out than this can be what this worker answers with, so the radius it was
		// allowed beyond that is no longer worth covering
		setRadiusLimit(ceilSqrt(distanceSqr));
	}

	BlockPos getBestPos() {
		return bestPos;
	}

	ResourceLocation getBestKey() {
		return bestKey;
	}

	long getBestDistanceSqr() {
		return bestDistanceSqr;
	}

	int getSamples() {
		return samples;
	}

	/** Notes that this worker has nothing left to search. The manager reports the outcome. */
	void finish() {
		final BlockPos located = bestPos;
		ExplorersCompass.LOGGER.info("Search " + context.getId() + ": " + getName() + (located == null ? " located nothing" : " finished on " + bestKey) + " with " + (shouldLogRadius() ? getRadius() + " radius, " : "") + samples + " samples");
		finished = true;
	}

	void stop() {
		ExplorersCompass.LOGGER.info("Search " + context.getId() + ": " + getName() + " stopped with " + (shouldLogRadius() ? getRadius() + " radius, " : "") + samples + " samples");
		finished = true;
	}

	protected void abort(Throwable cause) {
		ExplorersCompass.LOGGER.error("Search " + context.getId() + ": " + getName() + " encountered an error while searching", cause);
		finished = true;
	}

	protected int getRadius() {
		return StructureUtils.getHorizontalDistanceToLocation(startPos, currentPos.getX(), currentPos.getZ());
	}

	/**
	 * The distance a squared distance stands for, rounded up. Rounding down would cut a worker off
	 * just short of a location that is nearer than the one the limit came from.
	 */
	static int ceilSqrt(long distanceSqr) {
		return (int) Math.ceil(Math.sqrt((double) distanceSqr));
	}

	protected abstract String getName();

	protected abstract boolean shouldLogRadius();

}
