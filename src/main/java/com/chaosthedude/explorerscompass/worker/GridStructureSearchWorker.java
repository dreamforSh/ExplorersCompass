package com.chaosthedude.explorerscompass.worker;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.mojang.datafixers.util.Pair;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Vec3i;
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
 * says hold something. The walk only goes as far as the nearest of them, and the server thread then
 * puts them to storage one at a time, nearest first. Storage can turn one down, for a chunk that was
 * generated under other settings, and the next one along is then only worth asking about once
 * nothing nearer can be left: when the walk has not covered that far yet, it is set going again from
 * where it stopped. Only that last step reads a chunk, so a search that used to spend a slice of
 * every tick for as long as it ran now spends a handful of tick slices at the end of it.
 */
public abstract class GridStructureSearchWorker<T extends StructurePlacement> extends StructureSearchWorker<T> {

	/** How many chunks apart the cells of the grid being walked are. */
	private final int strideChunks;

	private final int startChunkX;
	private final int startChunkZ;

	/** How wide a cell of the grid is, in blocks. */
	private final int cellBlocks;

	/**
	 * How far, in blocks, the location a cell stands for can lie from where the walk puts the cell.
	 * See {@link #cellSpread}.
	 */
	private final int cellSpread;

	/** Whether the walk may be split between threads. Decided once, before either side starts. */
	private final boolean async;

	/** The shares the walk is split into, one per thread it may be given. */
	private final List<Shard> shards;

	/** What the walk turned up, in whatever order the shares happened to turn it up in. */
	private final Queue<Prediction> predictions = new ConcurrentLinkedQueue<Prediction>();

	/**
	 * How far out, squared, the nearest location the walk has turned up and storage has not turned
	 * down lies, which is as far as the walk needs to cover for now: nothing it reaches beyond that
	 * can be nearer. The walking threads narrow it as they turn locations up. Only the server thread
	 * widens it, between runs, once storage has turned the nearest one down.
	 *
	 * <p>Kept apart from the radius limit, which this worker can only ever narrow: a location the
	 * walk turned up is not a find until storage agrees, and narrowing the limit to one that storage
	 * then turned down would leave everything beyond it unsearched for good.
	 */
	private final AtomicLong walkBoundSqr = new AtomicLong(Long.MAX_VALUE);

	/**
	 * What the walk turned up that storage has yet to be asked about, nearest first. Only ever touched
	 * on the server thread, and only while the walking threads are stopped.
	 */
	private final PriorityQueue<Prediction> pending = new PriorityQueue<Prediction>(Comparator.comparingLong(Prediction::distanceSqr));

	/** A location the walk believes holds a structure, until chunk storage has agreed. */
	private record Prediction(ChunkPos chunkPos, Structure structure, BlockPos pos, long distanceSqr) {
	}

	/**
	 * A location the walk reached but did not predict for, since something nearer had already been
	 * turned up. It is predicted for after all if storage turns that down.
	 */
	private record Deferred(ChunkPos chunkPos, long distanceSqr) {
	}

	public GridStructureSearchWorker(SearchContext context, T placement, List<Structure> structureSet, int strideChunks) {
		this(context, placement, structureSet, strideChunks, placement.getLocatePos(new ChunkPos(0, 0)), Setup.read(context, placement, structureSet));
	}

	/**
	 * Takes how far the placement reports a structure from the corner of its chunk, and everything
	 * else it would read off the world, ready made.
	 */
	GridStructureSearchWorker(SearchContext context, T placement, List<Structure> structureSet, int strideChunks, Vec3i locateOffset, Setup setup) {
		super(context, placement, structureSet, setup);

		this.strideChunks = strideChunks;
		startChunkX = SectionPos.blockToSectionCoord(startPos.getX());
		startChunkZ = SectionPos.blockToSectionCoord(startPos.getZ());
		cellBlocks = SectionPos.sectionToBlockCoord(strideChunks);
		cellSpread = cellSpread(startPos, strideChunks, locateOffset);
		async = setup.async();

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

	/**
	 * How far, in blocks, the location a cell stands for can lie from where the walk puts that cell,
	 * for a walk starting at the given position.
	 *
	 * <p>The walk puts its cells a whole cell apart around the start, but the location a cell stands
	 * for is somewhere inside the cell of the placement's grid it falls in, and where the start is
	 * inside its own cell is just as arbitrary. Cells the walk has yet to reach are each at least as
	 * far out as the band it is on, so the locations they stand for are at least that far out less
	 * this. Worked out from the corner of the start's cell nearest to and furthest from it, which is
	 * as close as it can be put without knowing where in its cell each location falls.
	 */
	static int cellSpread(BlockPos startPos, int strideChunks, Vec3i locateOffset) {
		final long spreadX = axisSpread(startPos.getX(), strideChunks, locateOffset.getX());
		final long spreadZ = axisSpread(startPos.getZ(), strideChunks, locateOffset.getZ());
		return ceilSqrt(spreadX * spreadX + spreadZ * spreadZ);
	}

	/** The furthest a location can lie from where the walk puts its cell, along one axis. */
	private static long axisSpread(int startBlock, int strideChunks, int locateOffset) {
		// Where the cell the start falls in begins, which is never after the start. Every cell's
		// location is reported from the corner of one of its chunks, so the nearest one can lie at that
		// corner and the furthest at the corner of its last chunk.
		final int cellStart = SectionPos.sectionToBlockCoord(Math.floorDiv(SectionPos.blockToSectionCoord(startBlock), strideChunks) * strideChunks) - startBlock;
		final long nearest = (long) cellStart + locateOffset;
		final long furthest = (long) cellStart + SectionPos.sectionToBlockCoord(strideChunks - 1) + locateOffset;
		return Math.max(Math.abs(nearest), Math.abs(furthest));
	}

	/**
	 * The radius a walk has finished searching once it has covered the given number of cells all the
	 * way round: every location it has not reached yet lies at least this far out. Never more than the
	 * cells covered, since those are measured from where the walk puts each cell rather than from
	 * the location the cell stands for.
	 */
	static int coveredRadius(int coveredCells, int cellBlocks, int cellSpread) {
		return Math.max(0, coveredCells * cellBlocks - cellSpread);
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
	 * storage agrees about. Runs on the server thread, which is the only one that may ask it, and only
	 * while the walk is stopped.
	 *
	 * <p>A location is only put to storage once nothing nearer can be left: the walk has covered as
	 * far as it everywhere, and nothing the walk reached and put off is nearer either. Until then, the
	 * walk is set going again as far as that location, which is what keeps a location storage turns
	 * down from taking everything beyond it out of the search.
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

		for (Prediction prediction = predictions.poll(); prediction != null; prediction = predictions.poll()) {
			pending.add(prediction);
		}

		// What another worker of the search located since can have put these out of reach, and they are
		// nearest first, so once the nearest is, every one of them is
		final long limitSqr = (long) getRadiusLimit() * getRadiusLimit();
		if (!pending.isEmpty() && pending.peek().distanceSqr() > limitSqr) {
			pending.clear();
		}

		// How near the nearest location the walk has not looked at yet can be. A share that cannot walk
		// any further has nothing more to tell, so it does not hold up the ones that can.
		long unwalkedSqr = Long.MAX_VALUE;
		final List<Runnable> resumable = new ArrayList<Runnable>(shards.size());
		for (Shard shard : shards) {
			if (shard.canProgress()) {
				unwalkedSqr = Math.min(unwalkedSqr, shard.unwalkedSqr());
				resumable.add(shard::walk);
			}
		}

		final Prediction nearest = pending.peek();
		if (nearest != null && nearest.distanceSqr() <= unwalkedSqr) {
			pending.poll();
			final Pair<BlockPos, Structure> confirmed = confirmStructureAt(nearest.chunkPos(), nearest.structure(), nearest.pos().getY());
			if (confirmed == null) {
				// The chunk there was generated under settings that no longer put a structure in it. Carry on
				// with the next nearest location rather than ending the search on one that is not there.
				ExplorersCompass.LOGGER.info("Search " + context.getId() + ": " + getName() + " passed over " + nearest.pos().getX() + ", " + nearest.pos().getZ() + ", where the chunk holds no such structure");
				return true;
			}

			found(confirmed.getFirst(), confirmed.getSecond());
			markApplied();
			return false;
		}

		if (!resumable.isEmpty()) {
			// The walk stopped short of anything nearer than what is left, or of the radius when nothing
			// is left. It carries on from where each share stopped, as far as the nearest of what is left.
			walkBoundSqr.set(nearest != null ? nearest.distanceSqr() : Long.MAX_VALUE);
			ExplorersCompass.LOGGER.info("Search " + context.getId() + ": " + getName() + " walks on from " + getRadius() + " radius, " + (nearest != null ? "since something nearer than " + nearest.pos().getX() + ", " + nearest.pos().getZ() + " could still be there" : "with nothing left that storage agrees about"));
			relaunch(resumable);
			return false;
		}

		// Nothing is left to put to storage, and nowhere left to walk
		markApplied();
		return false;
	}

	/**
	 * How far the walking threads walk before they stop: as far as the nearest location turned up so
	 * far, or as far as this worker may search when nothing has been.
	 */
	private int walkLimit() {
		final long boundSqr = walkBoundSqr.get();
		final int limit = getRadiusLimit();
		return boundSqr == Long.MAX_VALUE ? limit : Math.min(limit, ceilSqrt(boundSqr));
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
	 * <p>A share is only ever walked by one thread at a time, and the server thread only reads what it
	 * counts and what it put off while no thread is walking it. How far it has covered is read while it
	 * walks, for the readout on the compass, so that is published — but only when it changes, since
	 * paying for a write every other thread has to see at every single location is most of what a
	 * location costs to look at.
	 */
	private final class Shard {

		private final DiscWalker walker;
		private final int maxSamples;

		private int samples;
		private volatile int coveredLength;

		/**
		 * The locations this share reached beyond the nearest one turned up at the time. They only need
		 * predicting for if storage turns down everything nearer, and the walk has moved past them, so
		 * they are kept rather than walked again.
		 */
		private final List<Deferred> deferred = new ArrayList<Deferred>();

		private Shard(DiscWalker walker, int maxSamples) {
			this.walker = walker;
			this.maxSamples = maxSamples;
		}

		/**
		 * Walks until it has covered as far as the nearest location turned up, or there is nothing left
		 * to look at. Picks up where the last run stopped, starting on what that one put off.
		 */
		private void walk() {
			// Held only for as long as this thread is walking: what it holds is worked out for the world
			// this search is in, and a thread that went on holding it after the walk would carry it into
			// whatever it did next
			TerrainHeightCache.start();
			try {
				predictDeferred();
				while (!Thread.currentThread().isInterrupted() && canWalk()) {
					predictNext();
				}
			} finally {
				TerrainHeightCache.stop();
			}
		}

		private boolean canWalk() {
			return !finished && samples < maxSamples && getRadius() < walkLimit();
		}

		/**
		 * Predicts for what the last run put off and is now nearer than anything left, nearest first,
		 * and drops what lies beyond how far the search may reach, which can never be the answer.
		 */
		private void predictDeferred() {
			if (deferred.isEmpty()) {
				return;
			}

			deferred.sort(Comparator.comparingLong(Deferred::distanceSqr));
			final long limitSqr = (long) getRadiusLimit() * getRadiusLimit();
			int next = 0;
			while (next < deferred.size() && !Thread.currentThread().isInterrupted() && !finished && samples < maxSamples) {
				final Deferred cell = deferred.get(next);
				// Nearest first, so once one is past either limit, every one after it is as well. The bound
				// can only narrow while this runs, as what is predicted here turns something up.
				if (cell.distanceSqr() > limitSqr || cell.distanceSqr() >= walkBoundSqr.get()) {
					break;
				}
				next++;
				predictAt(cell.chunkPos(), cell.distanceSqr());
			}
			deferred.subList(0, next).clear();
			deferred.removeIf((cell) -> cell.distanceSqr() > limitSqr);
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
			final BlockPos locatePos = getLocatePos(chunkPos);
			if (isWorthSampling(locatePos.getX(), locatePos.getZ())) {
				final long distanceSqr = context.distanceSqrFromStart(locatePos.getX(), locatePos.getZ());
				if (distanceSqr < walkBoundSqr.get()) {
					predictAt(chunkPos, distanceSqr);
				} else {
					// Something nearer has been turned up already, so this can only matter if storage turns
					// that down, and is not worth what predicting for it costs until then
					deferred.add(new Deferred(chunkPos, distanceSqr));
				}
			}

			advance();
		}

		private void predictAt(ChunkPos chunkPos, long distanceSqr) {
			samples++;
			// The structures sharing this placement are about to be asked about this one location, and
			// each of them works out how high the ground is at the same few columns of it
			TerrainHeightCache.nextLocation();

			final Pair<BlockPos, Structure> pair = predictStructureGeneratingAt(chunkPos);
			if (pair != null) {
				predictions.add(new Prediction(chunkPos, pair.getSecond(), pair.getFirst(), distanceSqr));
				// Nothing further out than this is worth walking to for now, whichever share turned it up.
				// It is not recorded as a find yet: chunk storage has the last word on that, and only the
				// server thread may ask it.
				walkBoundSqr.accumulateAndGet(distanceSqr, Math::min);
			}
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
			return Math.min(coveredRadius(coveredLength, cellBlocks, cellSpread), maxRadius);
		}

		private boolean hasMoreToSample(int limit) {
			return !finished && getRadius() < limit && samples < maxSamples;
		}

		/**
		 * Whether walking this share again could turn up anything: it has samples left, and either
		 * ground left inside how far the search may reach or locations it put off inside it.
		 */
		private boolean canProgress() {
			if (finished || samples >= maxSamples) {
				return false;
			}

			final int limit = getRadiusLimit();
			if (getRadius() < limit) {
				return true;
			}

			final long limitSqr = (long) limit * limit;
			for (Deferred cell : deferred) {
				if (cell.distanceSqr() <= limitSqr) {
					return true;
				}
			}
			return false;
		}

		/**
		 * How near, squared, the nearest location this share has not predicted for can be: one it has
		 * not reached yet is at least as far out as the radius it has covered, and one it put off is
		 * exactly as far out as it was found to be.
		 */
		private long unwalkedSqr() {
			final long radius = getRadius();
			long nearest = radius * radius;
			for (Deferred cell : deferred) {
				nearest = Math.min(nearest, cell.distanceSqr());
			}
			return nearest;
		}

	}

}
