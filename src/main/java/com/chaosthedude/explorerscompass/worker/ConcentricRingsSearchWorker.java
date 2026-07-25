package com.chaosthedude.explorerscompass.worker;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.chaosthedude.explorerscompass.util.StructureUtils;
import com.mojang.datafixers.util.Pair;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
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
		return !finished && samples < ConfigHandler.GENERAL.maxSamples.get() && (potentialChunks == null || chunkIndex < potentialChunks.size());
	}

	@Override
	protected boolean doSample() {
		if (hasWork() && resolvePotentialChunks()) {
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

		if (hasWork()) {
			return true;
		}

		if (!finished) {
			fail();
		}

		return false;
	}

	/**
	 * Looks up the locations this placement can generate at, keeping the ones inside the configured
	 * radius and sorting them by distance from the start position.
	 *
	 * <p>The chunk generator calculates these asynchronously, and asking for them blocks the server
	 * thread until that calculation has finished. Doing it here rather than when the search is
	 * created keeps the wait inside this worker's time slice, and skips it entirely for placements
	 * the search never reaches. Sorting lets the search stop at the first location that has a
	 * structure, instead of having to check every location that could still be closer than the best
	 * one found so far.
	 */
	private boolean resolvePotentialChunks() {
		if (potentialChunks == null) {
			final ServerChunkCache chunkSource = level.getChunkSource();
			final long startTime = System.currentTimeMillis();
			List<ChunkPos> ringPositions = chunkSource.getGenerator().getRingPositionsFor(placement, chunkSource.randomState());
			final long elapsed = System.currentTimeMillis() - startTime;
			if (elapsed > 1000L) {
				ExplorersCompass.LOGGER.warn("SearchWorkerManager " + managerId + ": " + getName() + " waited " + elapsed + "ms for the chunk generator to calculate the positions for this placement");
			}

			potentialChunks = new ArrayList<ChunkPos>();
			for (ChunkPos chunkPos : ringPositions == null ? List.<ChunkPos>of() : ringPositions) {
				if (isWithinMaxRadius(chunkPos)) {
					potentialChunks.add(chunkPos);
				}
			}
			potentialChunks.sort(Comparator.comparingLong(this::horizontalDistanceSqr));
		}

		return !potentialChunks.isEmpty();
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
