package com.chaosthedude.explorerscompass.worker;

import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

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
 * <p>The walk can run on a thread of its own, which is what {@code asyncStructureSearch} turns on.
 * It cannot ask chunk storage from there — see {@link StructurePrediction} for what it asks instead
 * and what that costs — so what it turns up is a list of locations that world generation says hold
 * something, nearest last. The server thread then puts those to storage one at a time, nearest
 * first, and the search settles on the first one storage agrees about. Only that last step reads a
 * chunk, so a search that used to spend a slice of every tick for as long as it ran now spends a
 * handful of tick slices at the end of it.
 */
public abstract class GridStructureSearchWorker<T extends StructurePlacement> extends StructureSearchWorker<T> {

	private final DiscWalker walker = new DiscWalker();

	/** How many chunks apart the cells of the grid being walked are. */
	private final int strideChunks;

	private final int startChunkX;
	private final int startChunkZ;

	/** Whether the walk is running on a thread of its own. Set before either side starts. */
	private volatile boolean background;

	/**
	 * How far the walk has covered. Written by whichever thread is walking and read by the server
	 * thread for the readout on the compass, so it is published rather than derived from the walker.
	 */
	private volatile int coveredRadius;

	/**
	 * What the walk turned up, nearest last. Each of these is nearer than the one before it, since the
	 * walk narrows what is still worth looking at every time it finds something.
	 */
	private final Deque<Prediction> predictions = new ConcurrentLinkedDeque<Prediction>();

	// That the walking thread is done. Publishing this last is what makes everything it wrote before
	// it visible to the server thread that sees it set.
	private volatile boolean backgroundDone;
	private Throwable backgroundError;

	/** Whether the server thread has finished acting on that. Only ever touched on the server thread. */
	private boolean applied;

	/** A location the walk believes holds a structure, until chunk storage has agreed. */
	private record Prediction(ChunkPos chunkPos, Structure structure, BlockPos pos) {
	}

	public GridStructureSearchWorker(SearchContext context, T placement, List<Structure> structureSet, int strideChunks) {
		super(context, placement, structureSet);

		this.strideChunks = strideChunks;
		startChunkX = SectionPos.blockToSectionCoord(startPos.getX());
		startChunkZ = SectionPos.blockToSectionCoord(startPos.getZ());
	}

	@Override
	protected void onBegin() {
		if (!ConfigHandler.GENERAL.asyncStructureSearch.get()) {
			return;
		}

		background = true;
		try {
			SearchExecutor.execute(this::walkInBackground);
		} catch (Throwable t) {
			// Nothing is going to run it, so answer as though it had run and failed rather than leaving
			// the server thread watching for a result that will never arrive
			backgroundError = t;
			backgroundDone = true;
		}
	}

	/** Walks until there is nothing left to look at, off the server thread. */
	private void walkInBackground() {
		try {
			while (!Thread.currentThread().isInterrupted() && hasMoreToSample()) {
				predictNext();
			}
		} catch (Throwable t) {
			backgroundError = t;
		} finally {
			backgroundDone = true;
		}
	}

	@Override
	boolean isReady() {
		// While the walk runs on a thread of its own there is nothing for the server thread to do until
		// it has something to put to storage. Answering false hands the turn to another worker of the
		// search rather than holding up everything else it is looking for.
		return !background || backgroundDone;
	}

	@Override
	boolean hasWork() {
		// While the walk runs on a thread of its own, this stays true until the server thread has acted
		// on what it turned up, however far it has got: what is left to walk is that thread's business,
		// and reading it from here would be a race
		return background ? !applied : super.hasWork();
	}

	@Override
	protected boolean isExhausted() {
		return background ? applied : super.isExhausted();
	}

	@Override
	int getSamplesPerTurn() {
		// Putting a location to storage can load a chunk, which is already as much as a turn is meant
		// to hold
		return background ? 1 : super.getSamplesPerTurn();
	}

	@Override
	protected boolean doSample() {
		if (background) {
			return confirmNext();
		}

		sampleNext();
		return hasWork();
	}

	/**
	 * Asks chunk storage about the location the walk has reached and moves on to the next one. This is
	 * what a search does when it is not allowed a thread of its own.
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
	 * Works out whether world generation would put a structure at the location the walk has reached,
	 * and moves on to the next one. Touches nothing outside this worker, which is what lets it run off
	 * the server thread.
	 */
	private void predictNext() {
		final ChunkPos chunkPos = candidateChunk(currentChunkX(), currentChunkZ());
		if (isWorthSampling(chunkPos)) {
			samples++;

			final Pair<BlockPos, Structure> pair = predictStructureGeneratingAt(chunkPos);
			if (pair != null) {
				final BlockPos pos = pair.getFirst();
				predictions.addLast(new Prediction(chunkPos, pair.getSecond(), pos));
				// Nothing further out than this can be what this worker answers with, so the radius it was
				// allowed beyond that is no longer worth walking. It is not recorded as a find yet: chunk
				// storage has the last word on that, and only the server thread may ask it.
				setRadiusLimit(ceilSqrt(context.distanceSqrFromStart(pos.getX(), pos.getZ())));
			}
		}

		advance();
	}

	/**
	 * Puts what the walk turned up to chunk storage, nearest first, and settles on the first location
	 * storage agrees about. Runs on the server thread, which is the only one that may ask it.
	 */
	private boolean confirmNext() {
		if (!backgroundDone || applied) {
			return false;
		}

		if (backgroundError != null) {
			applied = true;
			abort(backgroundError);
			return false;
		}

		final Prediction prediction = predictions.pollLast();
		if (prediction == null) {
			// Everything the walk turned up has been passed over, so there is nothing left to answer with
			applied = true;
			return false;
		}

		final BlockPos confirmed = confirmStructureAt(prediction.chunkPos(), prediction.structure(), prediction.pos().getY());
		if (confirmed == null) {
			// The chunk there was generated under settings that no longer put a structure in it. Carry on
			// with the next nearest location rather than ending the search on one that is not there.
			ExplorersCompass.LOGGER.info("Search " + context.getId() + ": " + getName() + " passed over " + prediction.pos().getX() + ", " + prediction.pos().getZ() + ", where the chunk holds no such structure");
			return true;
		}

		found(confirmed, prediction.structure());
		applied = true;
		return false;
	}

	private void advance() {
		walker.advance();
		coveredRadius = Math.min(SectionPos.sectionToBlockCoord(walker.getCoveredLength() * strideChunks), maxRadius);
	}

	private int currentChunkX() {
		return startChunkX + strideChunks * walker.getX();
	}

	private int currentChunkZ() {
		return startChunkZ + strideChunks * walker.getZ();
	}

	/** The location the cell of the grid at the given chunk coordinates stands for. */
	protected abstract ChunkPos candidateChunk(int chunkX, int chunkZ);

	/**
	 * The radius this worker has finished searching, which is what bounds the search and what the
	 * compass reports.
	 *
	 * <p>Deliberately not the distance to the location sampled last. The walk covers a disc a band at
	 * a time, and a cell of the band being walked lies anywhere on it, so bounding the search by the
	 * location sampled last would end it as soon as the walk reached the far side of a band and leave
	 * the near side of it unsearched.
	 *
	 * <p>While the walk runs on a thread of its own, the server thread reads this for the readout on
	 * the compass and may see a band it has already left behind. That is all it is used for there:
	 * what is left to search is answered by {@link #hasWork()} without reading any of this.
	 */
	@Override
	protected int getRadius() {
		return coveredRadius;
	}

	@Override
	public boolean shouldLogRadius() {
		return true;
	}

}
