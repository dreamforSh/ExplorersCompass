package com.chaosthedude.explorerscompass.worker;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.mojang.datafixers.util.Pair;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;

public class ConcentricRingsSearchWorker extends StructureSearchWorker<ConcentricRingsStructurePlacement> {

	private List<ChunkPos> potentialChunks;
	private int chunkIndex;

	public ConcentricRingsSearchWorker(SearchContext context, ConcentricRingsStructurePlacement placement, List<Structure> structureSet) {
		super(context, placement, structureSet);

		chunkIndex = 0;
	}

	/**
	 * Whether the locations this placement can generate at are known yet. The generator computes them
	 * on a background thread, which on a fresh world takes seconds, and until they are here there is
	 * nothing for this worker to sample. Answering false hands the turn to another worker of the
	 * search rather than holding up everything else it is looking for.
	 */
	@Override
	boolean isReady() {
		return potentialChunks != null || tryResolvePotentialChunks();
	}

	@Override
	protected boolean hasMoreToSample() {
		// Every location is known up front and the list is already limited to the configured radius, so
		// the only radius left to respect is the one the manager sets. The locations are sorted by
		// distance, so the one sampled last is how far this worker has covered.
		return !finished && samples < maxSamples && getRadius() < getEffectiveRadiusLimit() && (potentialChunks == null || chunkIndex < potentialChunks.size());
	}

	@Override
	protected boolean isExhausted() {
		// Once the last of the locations this placement can generate at has been sampled there is
		// nothing a further turn could add
		return super.isExhausted() || (potentialChunks != null && chunkIndex >= potentialChunks.size());
	}

	@Override
	protected boolean doSample() {
		final ChunkPos chunkPos = potentialChunks.get(chunkIndex);
		currentPos = chunkPos.getMiddleBlockPosition(0);

		final Pair<BlockPos, Structure> pair = getStructureGeneratingAt(chunkPos);
		samples++;
		chunkIndex++;
		if (pair != null) {
			// The locations are sorted by distance, so the first one found is the closest one and the
			// worker ends on it
			found(pair.getFirst(), pair.getSecond());
		}

		return hasWork();
	}

	/**
	 * Fetches the locations this placement can generate at once the chunk generator has finished
	 * computing them, keeping the ones inside the configured radius and sorting them by distance
	 * from the start position. Sorting lets the search stop at the first location that has a
	 * structure, instead of having to check every location that could still be closer than the best
	 * one found so far.
	 *
	 * <p>The generator computes the positions asynchronously, and its only public accessor joins
	 * that computation, blocking the server thread for however long it still needs — seconds, for a
	 * fresh world. Reading the future directly (opened up by the access transformer) lets this
	 * worker wait by handing the turn on instead. The future is created for every placement of the
	 * level when {@code getPlacementsForStructure} runs during worker creation, so it is already
	 * present here.
	 */
	private boolean tryResolvePotentialChunks() {
		final CompletableFuture<List<ChunkPos>> future = level.getChunkSource().getGenerator().ringPositions.get(placement);
		if (future != null && !future.isDone()) {
			return false;
		}

		final List<ChunkPos> ringPositions = future == null ? null : future.join();
		potentialChunks = new ArrayList<ChunkPos>();
		for (ChunkPos chunkPos : ringPositions == null ? List.<ChunkPos>of() : ringPositions) {
			if (isWithinMaxRadius(chunkPos)) {
				potentialChunks.add(chunkPos);
			}
		}
		potentialChunks.sort(Comparator.comparingLong(this::horizontalDistanceSqr));
		return true;
	}

	@Override
	protected boolean canPlaceAt(ChunkPos chunkPos) {
		// These locations come from the placement itself, which verifies them by scanning its own list
		return true;
	}

	private long horizontalDistanceSqr(ChunkPos chunkPos) {
		return context.distanceSqrFromStart(chunkPos.getMiddleBlockX(), chunkPos.getMiddleBlockZ());
	}

	@Override
	protected String getName() {
		return "ConcentricRingsSearchWorker";
	}

	@Override
	public boolean shouldLogRadius() {
		return false;
	}

}
