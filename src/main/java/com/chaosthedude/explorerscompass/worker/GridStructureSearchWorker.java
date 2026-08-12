package com.chaosthedude.explorerscompass.worker;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.mojang.datafixers.util.Pair;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

/**
 * A structure search that walks a grid of candidate locations outwards from where the search
 * started, nearest first, until it has found something or run out of what it was allowed.
 *
 * <p>Which grid that is follows from the placement: one that spreads its structures at random puts
 * at most one in each cell of a grid of its own, while one this mod knows nothing about could put a
 * structure in any chunk at all. Either way the walk is the same, so it lives here and the workers
 * for each kind of placement only say how far apart the cells are and which location a cell stands
 * for.
 *
 * <p>The walk can run off the server thread, which is what {@code asyncStructureSearch} turns on,
 * shared out over as many threads as searching is allowed with each taking its own share of the
 * rows. It cannot ask chunk storage from there — see {@link StructurePrediction} for what it asks
 * instead and what that costs — so what it turns up is a list of locations that world generation
 * says hold something. The server thread then puts those to storage one at a time, nearest first,
 * and the search settles on the first one storage agrees about. Only that last step reads a chunk,
 * so a search that used to spend a slice of every tick for as long as it ran now spends a handful of
 * tick slices at the end of it.
 */
public abstract class GridStructureSearchWorker<T extends StructurePlacement> extends StructureSearchWorker<T> {

	/** How many chunks apart the cells of the grid being walked are. */
	private final int strideChunks;

	private final int startChunkX;
	private final int startChunkZ;

	/** Whether the walk may be split between threads. Decided once, before either side starts. */
	private final boolean async;

	/** The shares the walk is split into, one per thread it may be given. */
	private final List<Shard> shards;

	/** What the walk turned up, in whatever order the shares happened to turn it up in. */
	private final Queue<Prediction> predictions = new ConcurrentLinkedQueue<Prediction>();

	// The same, nearest first, and how far through them the server thread has got. Only ever touched
	// on the server thread, and only once the walking threads are done.
	private List<Prediction> ordered;
	private int orderedIndex;

	/** A location the walk believes holds a structure, until chunk storage has agreed. */
	private record Prediction(ChunkPos chunkPos, Structure structure, BlockPos pos, long distanceSqr) {
	}

	public GridStructureSearchWorker(SearchContext context, T placement, List<Structure> structureSet, int strideChunks) {
		super(context, placement, structureSet);

		this.strideChunks = strideChunks;
		startChunkX = SectionPos.blockToSectionCoord(startPos.getX());
		startChunkZ = SectionPos.blockToSectionCoord(startPos.getZ());
		async = ConfigHandler.GENERAL.asyncStructureSearch.get();

		// As many shares as there are threads to walk them. A search looking through several placements
		// has a worker each and so asks for this many again, which only queues them up behind one
		// another: the threads stay busy either way, and each placement still has all of them while it
		// is the one being walked.
		final int shardCount = async ? Math.max(1, SearchExecutor.getThreadCount()) : 1;
		final int shardSamples = (maxSamples + shardCount - 1) / shardCount;
		shards = new ArrayList<Shard>(shardCount);
		for (int i = 0; i < shardCount; i++) {
			shards.add(new Shard(new DiscWalker(shardCount, i), shardSamples));
		}
	}

	@Override
	protected boolean isBackgroundAllowed() {
		return async;
	}

	@Override
	protected List<Runnable> createBackgroundTasks() {
		final List<Runnable> tasks = new ArrayList<Runnable>(shards.size());
		for (Shard shard : shards) {
			tasks.add(shard::walk);
		}
		return tasks;
	}

	@Override
	int getSamplesPerTurn() {
		// Putting a location to storage can load a chunk, which is already as much as a turn is meant
		// to hold
		return isBackground() ? 1 : super.getSamplesPerTurn();
	}

	@Override
	protected boolean doSample() {
		if (isBackground()) {
			return confirmNext();
		}

		// On the server thread there is only ever the one share to walk
		shards.get(0).sampleNext();
		return hasWork();
	}

	/**
	 * Puts what the walk turned up to chunk storage, nearest first, and settles on the first location
	 * storage agrees about. Runs on the server thread, which is the only one that may ask it.
	 */
	private boolean confirmNext() {
		if (!isBackgroundDone() || isApplied()) {
			return false;
		}

		if (getBackgroundError() != null) {
			markApplied();
			abort(getBackgroundError());
			return false;
		}

		if (ordered == null) {
			// Each share only narrows what is left to walk as it finds something, so what it turned up is
			// in order within itself but says nothing about the other shares. Putting them in order once
			// here is what makes the first location storage agrees about the nearest one there is.
			ordered = new ArrayList<Prediction>(predictions);
			ordered.sort(Comparator.comparingLong(Prediction::distanceSqr));
		}

		if (orderedIndex >= ordered.size()) {
			// Everything the walk turned up has been passed over, so there is nothing left to answer with
			markApplied();
			return false;
		}

		final Prediction prediction = ordered.get(orderedIndex++);
		final BlockPos confirmed = confirmStructureAt(prediction.chunkPos(), prediction.structure(), prediction.pos().getY());
		if (confirmed == null) {
			// The chunk there was generated under settings that no longer put a structure in it. Carry on
			// with the next nearest location rather than ending the search on one that is not there.
			ExplorersCompass.LOGGER.info("Search " + context.getId() + ": " + getName() + " passed over " + prediction.pos().getX() + ", " + prediction.pos().getZ() + ", where the chunk holds no such structure");
			return true;
		}

		found(confirmed, prediction.structure());
		markApplied();
		return false;
	}

	@Override
	int getSamples() {
		int total = 0;
		for (Shard shard : shards) {
			total += shard.samples;
		}
		return total;
	}

	@Override
	protected boolean hasMoreToSample() {
		for (Shard shard : shards) {
			if (shard.hasMoreToSample(getEffectiveRadiusLimit())) {
				return true;
			}
		}
		return false;
	}

	@Override
	protected boolean isExhausted() {
		if (isBackground()) {
			return isApplied();
		}

		// The band a single turn is held to says nothing about whether there is anything left to search
		for (Shard shard : shards) {
			if (shard.hasMoreToSample(getRadiusLimit())) {
				return false;
			}
		}
		return true;
	}

	/** The location the cell of the grid at the given chunk coordinates stands for. */
	protected abstract ChunkPos candidateChunk(int chunkX, int chunkZ);

	/**
	 * The radius this worker has finished searching, which is what bounds the search and what the
	 * compass reports: the least of what its shares have covered, since a row none of them has reached
	 * yet is ground none of them has searched.
	 *
	 * <p>Deliberately not the distance to the location sampled last. The walk covers a disc a band at
	 * a time, and a cell of the band being walked lies anywhere on it, so bounding the search by the
	 * location sampled last would end it as soon as the walk reached the far side of a band and leave
	 * the near side of it unsearched.
	 *
	 * <p>While the walk runs off the server thread, the server thread reads this for the readout on
	 * the compass and may see a band it has already left behind. That is all it is used for there:
	 * what is left to search is answered by {@link #hasWork()} without reading any of this.
	 */
	@Override
	protected int getRadius() {
		int covered = Integer.MAX_VALUE;
		for (Shard shard : shards) {
			covered = Math.min(covered, shard.getRadius());
		}
		return covered;
	}

	@Override
	public boolean shouldLogRadius() {
		return true;
	}

	/**
	 * One thread's share of the walk: the rows of the grid that fall to it, and how far along them it
	 * has got.
	 *
	 * <p>A share is only ever walked by one thread, so what it counts is its own and is read once the
	 * threads are done. How far it has covered is read while it walks, for the readout on the compass,
	 * so that is published — but only when it changes, since paying for a write every other thread has
	 * to see at every single location is most of what a location costs to look at.
	 */
	private final class Shard {

		private final DiscWalker walker;
		private final int maxSamples;

		private int samples;
		private volatile int coveredLength;

		private Shard(DiscWalker walker, int maxSamples) {
			this.walker = walker;
			this.maxSamples = maxSamples;
		}

		/** Walks until there is nothing left to look at. */
		private void walk() {
			// Held only for as long as this thread is walking: what it holds is worked out for the world
			// this search is in, and a thread that went on holding it after the walk would carry it into
			// whatever it did next
			TerrainHeightCache.start();
			try {
				while (!Thread.currentThread().isInterrupted() && hasMoreToSample(getEffectiveRadiusLimit())) {
					predictNext();
				}
			} finally {
				TerrainHeightCache.stop();
			}
		}

		/**
		 * Asks chunk storage about the location this share has reached and moves on to the next one.
		 * This is what a search does when it is not allowed a thread of its own.
		 */
		private void sampleNext() {
			final ChunkPos chunkPos = candidateChunk(currentChunkX(), currentChunkZ());
			// A cell of the grid can stand for a location past the edge of the disc being walked, so part
			// of the outer cells lies beyond the configured radius, and beyond anything already located
			if (isWorthSampling(chunkPos)) {
				final Pair<BlockPos, Structure> pair = getStructureGeneratingAt(chunkPos);
				samples++;
				if (pair != null) {
					found(pair.getFirst(), pair.getSecond());
				}
			}

			advance();
		}

		/**
		 * Works out whether world generation would put a structure at the location this share has
		 * reached, and moves on to the next one. Touches nothing outside its own worker, which is what
		 * lets it run off the server thread.
		 */
		private void predictNext() {
			final ChunkPos chunkPos = candidateChunk(currentChunkX(), currentChunkZ());
			if (isWorthSampling(chunkPos)) {
				samples++;
				// The structures sharing this placement are about to be asked about this one location, and
				// each of them works out how high the ground is at the same few columns of it
				TerrainHeightCache.nextLocation();

				final Pair<BlockPos, Structure> pair = predictStructureGeneratingAt(chunkPos);
				if (pair != null) {
					final BlockPos pos = pair.getFirst();
					final long distanceSqr = context.distanceSqrFromStart(pos.getX(), pos.getZ());
					predictions.add(new Prediction(chunkPos, pair.getSecond(), pos, distanceSqr));
					// Nothing further out than this can be what this worker answers with, whichever share
					// turned it up, so the radius they were allowed beyond that is no longer worth walking.
					// It is not recorded as a find yet: chunk storage has the last word on that, and only
					// the server thread may ask it.
					setRadiusLimit(ceilSqrt(distanceSqr));
				}
			}

			advance();
		}

		private void advance() {
			walker.advance();
			final int covered = walker.getCoveredLength();
			if (covered != coveredLength) {
				coveredLength = covered;
			}
		}

		private int currentChunkX() {
			return startChunkX + strideChunks * walker.getX();
		}

		private int currentChunkZ() {
			return startChunkZ + strideChunks * walker.getZ();
		}

		private int getRadius() {
			return Math.min(SectionPos.sectionToBlockCoord(coveredLength * strideChunks), maxRadius);
		}

		private boolean hasMoreToSample(int limit) {
			return !finished && getRadius() < limit && samples < maxSamples;
		}

	}

}
