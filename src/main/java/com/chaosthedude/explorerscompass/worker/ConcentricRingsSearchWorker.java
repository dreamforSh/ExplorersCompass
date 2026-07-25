package com.chaosthedude.explorerscompass.worker;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.chaosthedude.explorerscompass.util.StructureUtils;
import com.mojang.datafixers.util.Pair;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;

public class ConcentricRingsSearchWorker extends StructureSearchWorker<ConcentricRingsStructurePlacement> {

	private List<ChunkPos> potentialChunks;
	private int chunkIndex;

	public ConcentricRingsSearchWorker(ServerLevel level, Player player, ItemStack stack, BlockPos startPos, ConcentricRingsStructurePlacement placement, List<Structure> structureSet, List<BlockPos> prevPos, boolean isGroup, boolean ignoreNearStart, String managerId) {
		super(level, player, stack, startPos, placement, structureSet, prevPos, isGroup, ignoreNearStart, managerId);

		chunkIndex = 0;
	}

	@Override
	public boolean hasWork() {
		// Every location is known up front and the list is already limited to the configured radius, so
		// there is nothing for a radius bound to do here
		return !finished && samples < maxSamples && (potentialChunks == null || chunkIndex < potentialChunks.size());
	}

	@Override
	protected boolean doSample() {
		if (hasWork()) {
			if (potentialChunks == null && !tryResolvePotentialChunks()) {
				// The positions are still being computed on a background thread. Yield the tick and try
				// again on the next one, instead of blocking the server thread until they are done.
				return false;
			}

			if (chunkIndex < potentialChunks.size()) {
				ChunkPos chunkPos = potentialChunks.get(chunkIndex);
				currentPos = chunkPos.getMiddleBlockPosition(0);

				Pair<BlockPos, Structure> pair = getStructureGeneratingAt(chunkPos);
				samples++;
				chunkIndex++;
				if (pair != null) {
					// The locations are sorted by distance, so the first one found is the closest one
					succeed(pair.getFirst(), pair.getSecond());
				}
			}
		}

		if (hasWork()) {
			return true;
		}

		if (!finished) {
			fail();
		}

		return false;
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
	 * worker wait by yielding instead. The future is created for every placement of the level when
	 * {@code getPlacementsForStructure} runs during worker creation, so it is already present here.
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
		return StructureUtils.getHorizontalDistanceSqrToLocation(startPos, chunkPos.getMiddleBlockX(), chunkPos.getMiddleBlockZ());
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
