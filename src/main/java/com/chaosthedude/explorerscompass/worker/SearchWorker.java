package com.chaosthedude.explorerscompass.worker;

import java.util.List;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.chaosthedude.explorerscompass.items.ExplorersCompassItem;
import com.chaosthedude.explorerscompass.util.StructureUtils;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.WorldWorkerManager;

/**
 * Everything a search does apart from deciding where to look and what counts as a find: it walks
 * outwards from where it was started, a slice of a tick at a time, until it locates something, runs
 * out of the radius or the samples it was allowed, or is stopped.
 */
public abstract class SearchWorker implements WorldWorkerManager.IWorker {

	// Granularity in blocks of the search radius reported to the compass while a search is running
	private static final int RADIUS_REPORT_INTERVAL = 250;

	// How close a location has to be to an already located one, or to where a search for a further
	// instance started, to count as the same find. Two chunks, so that walking around inside a
	// structure and searching again does not just point at the one being stood in.
	private static final int SAME_LOCATION_DISTANCE = 32;

	protected String managerId;
	protected ServerLevel level;
	protected Player player;
	protected ItemStack stack;
	protected BlockPos startPos;
	protected BlockPos currentPos;
	protected List<BlockPos> prevPos;
	protected boolean isGroup;
	protected boolean ignoreNearStart;
	protected int samples;
	protected boolean finished;
	protected int lastRadiusThreshold;

	// Snapshots of the limits this search runs under. The sampling loop reads them for every
	// location, and a config lookup walks the config tree on every call, which is far too slow for
	// that; a search also ought to finish under the limits it was started with.
	protected final int maxRadius;
	protected final int maxSamples;
	private final int maxSearchTimePerTick;

	// When this worker started working during the current tick, or -1 if it is not currently working
	private long sliceStartTime;

	public SearchWorker(ServerLevel level, Player player, ItemStack stack, BlockPos startPos, List<BlockPos> prevPos, boolean isGroup, boolean ignoreNearStart, int maxSamples, String managerId) {
		this.level = level;
		this.player = player;
		this.stack = stack;
		this.startPos = startPos;
		this.prevPos = prevPos;
		this.isGroup = isGroup;
		this.ignoreNearStart = ignoreNearStart;
		this.maxSamples = maxSamples;
		this.managerId = managerId;

		currentPos = startPos;
		samples = 0;
		sliceStartTime = -1L;

		maxRadius = ConfigHandler.GENERAL.maxRadius.get();
		maxSearchTimePerTick = ConfigHandler.GENERAL.maxSearchTimePerTick.get();
	}

	public void start() {
		if (!stack.isEmpty() && stack.getItem() == ExplorersCompass.explorersCompass) {
			// A worker with nothing to do is dropped by Forge without ever being called, so a search
			// that cannot sample anything at all has to report that here rather than leaving the
			// compass searching for a result that will never arrive
			if (maxRadius > 0 && hasWork()) {
				ExplorersCompass.LOGGER.info("SearchWorkerManager " + managerId + ": " + getName() + " starting with " + (shouldLogRadius() ? maxRadius + " max radius, " : "") + maxSamples + " max samples");
				WorldWorkerManager.addWorker(this);
			} else {
				fail();
			}
		}
	}

	@Override
	public boolean hasWork() {
		return !finished && getRadius() < maxRadius && samples < maxSamples;
	}

	@Override
	public final boolean doWork() {
		final long now = System.currentTimeMillis();
		if (sliceStartTime < 0L || now - sliceStartTime >= maxSearchTimePerTick) {
			// Either this is the first call of a new tick, or the previous slice ran over because a single
			// sample took longer than the whole budget. Start a fresh slice either way.
			sliceStartTime = now;
		}

		boolean callAgain;
		try {
			updateSearchRadius();
			callAgain = doSample();
		} catch (Throwable t) {
			// Sampling touches world generation and chunk storage, both of which can fail for a single
			// structure or chunk. Report the search as failed instead of letting the exception escape into
			// the server tick, where it would take down the server.
			abort(t);
			return false;
		}

		// Forge hands a worker the remainder of the tick and only checks the clock between calls, so a
		// search that samples expensive locations can stall the server for as long as it likes. Give up
		// the rest of the tick once this worker has used its slice.
		if (!callAgain || System.currentTimeMillis() - sliceStartTime >= maxSearchTimePerTick) {
			sliceStartTime = -1L;
			return false;
		}

		return true;
	}

	/**
	 * Samples a single location. Returns true if this worker should be called again, false if it is
	 * done.
	 */
	protected abstract boolean doSample();

	/**
	 * Whether a location has already been located by an earlier search, and should be passed over so
	 * that searching again finds a different instance. Locations right where this search started
	 * count as well, so that a search for a further instance does not answer with what is being
	 * stood in.
	 */
	protected boolean shouldIgnore(BlockPos pos) {
		if (ignoreNearStart && isSameLocation(pos, startPos)) {
			return true;
		}

		for (BlockPos prev : prevPos) {
			if (isSameLocation(pos, prev)) {
				return true;
			}
		}

		return false;
	}

	private boolean isSameLocation(BlockPos pos, BlockPos other) {
		final int distance = getSameLocationDistance();
		return StructureUtils.getHorizontalDistanceSqrToLocation(other, pos.getX(), pos.getZ()) <= (long) distance * distance;
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
		final long distanceSqr = StructureUtils.getHorizontalDistanceSqrToLocation(startPos, x, z);
		return distanceSqr <= (long) maxRadius * maxRadius;
	}

	protected void succeed(BlockPos pos, ResourceLocation key) {
		ExplorersCompass.LOGGER.info("SearchWorkerManager " + managerId + ": " + getName() + " succeeded with " + (shouldLogRadius() ? getRadius() + " radius, " : "") + samples + " samples, " + prevPos.size() + " previously located");
		finished = true;
		// Remember this location, so that searching again looks for a different instance
		prevPos.add(pos);
		if (!stack.isEmpty() && stack.getItem() == ExplorersCompass.explorersCompass) {
			((ExplorersCompassItem) stack.getItem()).succeed(player, stack, key, isGroup, pos.getX(), pos.getZ(), pos.getY(), level.dimension().location(), prevPos, samples, ConfigHandler.GENERAL.displayCoordinates.get());
		} else {
			ExplorersCompass.LOGGER.error("SearchWorkerManager " + managerId + ": " + getName() + " found invalid compass after successful search");
		}
	}

	protected void fail() {
		ExplorersCompass.LOGGER.info("SearchWorkerManager " + managerId + ": " + getName() + " failed with " + (shouldLogRadius() ? getRadius() + " radius, " : "") + samples + " samples");
		// Mark this worker as finished before handing off to the compass: notifying it starts the next
		// worker for this search, and this one must not be considered live anymore if anything there
		// goes wrong.
		finished = true;
		if (!stack.isEmpty() && stack.getItem() == ExplorersCompass.explorersCompass) {
			((ExplorersCompassItem) stack.getItem()).fail(player, stack, roundRadius(getRadius(), RADIUS_REPORT_INTERVAL), samples);
		} else {
			ExplorersCompass.LOGGER.error("SearchWorkerManager " + managerId + ": " + getName() + " found invalid compass after failed search");
		}
	}

	public void stop() {
		ExplorersCompass.LOGGER.info("SearchWorkerManager " + managerId + ": " + getName() + " stopped with " + (shouldLogRadius() ? getRadius() + " radius, " : "") + samples + " samples");
		finished = true;
	}

	protected void abort(Throwable cause) {
		ExplorersCompass.LOGGER.error("SearchWorkerManager " + managerId + ": " + getName() + " encountered an error while searching", cause);
		sliceStartTime = -1L;
		if (!finished) {
			fail();
		}
	}

	protected void updateSearchRadius() {
		int radius = getRadius();
		if (radius > RADIUS_REPORT_INTERVAL && radius / RADIUS_REPORT_INTERVAL > lastRadiusThreshold) {
			if (!stack.isEmpty() && stack.getItem() == ExplorersCompass.explorersCompass) {
				((ExplorersCompassItem) stack.getItem()).setSearchRadius(stack, roundRadius(radius, RADIUS_REPORT_INTERVAL), player);
			}
			lastRadiusThreshold = radius / RADIUS_REPORT_INTERVAL;
		}
	}

	protected int getRadius() {
		return StructureUtils.getHorizontalDistanceToLocation(startPos, currentPos.getX(), currentPos.getZ());
	}

	protected int roundRadius(int radius, int roundTo) {
		return ((int) radius / roundTo) * roundTo;
	}

	protected abstract String getName();

	protected abstract boolean shouldLogRadius();

}
