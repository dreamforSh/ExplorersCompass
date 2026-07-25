package com.chaosthedude.explorerscompass.worker;

import java.util.List;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.mojang.datafixers.util.Pair;

import net.minecraft.core.BlockPos;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;

public class ConcentricRingsSearchWorker extends StructureSearchWorker<ConcentricRingsStructurePlacement> {

	private List<ChunkPos> potentialChunks;
	private int chunkIndex;
	private double minDistance;
	private Pair<BlockPos, Structure> closest;

	public ConcentricRingsSearchWorker(ServerLevel level, Player player, ItemStack stack, BlockPos startPos, ConcentricRingsStructurePlacement placement, List<Structure> structureSet, String managerId) {
		super(level, player, stack, startPos, placement, structureSet, managerId);

		minDistance = Double.MAX_VALUE;
		chunkIndex = 0;
	}

	@Override
	public boolean hasWork() {
		// Samples for this placement are not necessarily in order of closest to furthest, so disregard radius
		return !finished && samples < ConfigHandler.GENERAL.maxSamples.get() && (potentialChunks == null || chunkIndex < potentialChunks.size());
	}

	@Override
	protected boolean doSample() {
		if (hasWork() && resolvePotentialChunks()) {
			ChunkPos chunkPos = potentialChunks.get(chunkIndex);
			currentPos = new BlockPos(SectionPos.sectionToBlockCoord(chunkPos.x, 8), 0, SectionPos.sectionToBlockCoord(chunkPos.z, 8));
			double distance = startPos.distSqr(currentPos);

			if (closest == null || distance < minDistance) {
				Pair<BlockPos, Structure> pair = getStructureGeneratingAt(chunkPos);
				if (pair != null) {
					minDistance = distance;
					closest = pair;
				}
			}

			samples++;
			chunkIndex++;
		}

		if (hasWork()) {
			return true;
		}

		if (closest != null) {
			succeed(closest.getFirst(), closest.getSecond());
		} else if (!finished) {
			fail();
		}

		return false;
	}

	/**
	 * The chunk generator calculates the positions for this placement asynchronously, and asking for them
	 * blocks the server thread until that calculation has finished. Do it here rather than when the search is
	 * created, so that the wait happens inside this worker's time slice and is skipped entirely for placements
	 * the search never reaches.
	 */
	private boolean resolvePotentialChunks() {
		if (potentialChunks == null) {
			long startTime = System.currentTimeMillis();
			potentialChunks = level.getChunkSource().getGenerator().getRingPositionsFor(placement, level.getChunkSource().randomState());
			if (potentialChunks == null) {
				potentialChunks = List.of();
			}

			long elapsed = System.currentTimeMillis() - startTime;
			if (elapsed > 1000L) {
				ExplorersCompass.LOGGER.warn("SearchWorkerManager " + managerId + ": " + getName() + " waited " + elapsed + "ms for the chunk generator to calculate the positions for this placement");
			}
		}

		return !potentialChunks.isEmpty();
	}

	@Override
	protected String getName() {
		return "ConcentricRingsSearchWorker";
	}

	@Override
	public boolean shouldLogRadius() {
		return false;
	}

	// Non-optimized method to get the closest structure, for testing purposes
	private Pair<BlockPos, Structure> getClosest() {
		List<ChunkPos> list = level.getChunkSource().getGenerator().getRingPositionsFor(placement, level.getChunkSource().randomState());
		if (list == null) {
			return null;
		} else {
			Pair<BlockPos, Structure> closestPair = null;
			double minDistance = Double.MAX_VALUE;
			MutableBlockPos sampleBlockPos = new MutableBlockPos();
			for (ChunkPos chunkPos : list) {
				sampleBlockPos.set(SectionPos.sectionToBlockCoord(chunkPos.x, 8), 32, SectionPos.sectionToBlockCoord(chunkPos.z, 8));
				double distance = sampleBlockPos.distSqr(startPos);
				if (closestPair == null || distance < minDistance) {
					Pair<BlockPos, Structure> pair = getStructureGeneratingAt(chunkPos);
					if (pair != null) {
						closestPair = pair;
						minDistance = distance;
					}
				}
			}

			return closestPair;
		}
	}

}
