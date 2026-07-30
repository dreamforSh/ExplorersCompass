package com.chaosthedude.explorerscompass.worker;

import java.util.List;
import java.util.Optional;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

public class RandomSpreadSearchWorker extends GridStructureSearchWorker<RandomSpreadStructurePlacement> {

	public RandomSpreadSearchWorker(SearchContext context, RandomSpreadStructurePlacement placement, List<Structure> structureSet) {
		// This placement puts at most one structure in each cell of a grid of its own, so walking the
		// chunks would visit every cell as many times as it is wide
		super(context, placement, structureSet, placement.spacing());
	}

	/**
	 * The one location in this cell of the placement's grid that it would use. Which one that is
	 * follows from the world seed alone, so it is the same location however the cell is reached.
	 */
	@Override
	protected ChunkPos candidateChunk(int chunkX, int chunkZ) {
		return placement.getPotentialStructureChunk(seed, chunkX, chunkZ);
	}

	/**
	 * Whether this placement is allowed to put a structure in the given chunk.
	 *
	 * <p>Deliberately not the placement's own check in full. That one starts by working out which
	 * location of the cell a chunk belongs to the placement would use and comparing the two — but this
	 * worker walks the cells and asks the placement for that location itself, so the two are the same
	 * one by construction and the answer is already known. Arriving at it again is not free: it needs a
	 * random source of its own, seeded afresh, for every location the search looks at.
	 *
	 * <p>What is left are the two conditions that can still turn a location down: how often this
	 * placement is allowed to use one at all, and whether another kind of structure has claimed the
	 * ground around it.
	 */
	@Override
	protected boolean canPlaceAt(ChunkPos chunkPos) {
		if (placement.frequency() < 1.0F && !placement.frequencyReductionMethod().shouldGenerate(seed, placement.salt(), chunkPos.x, chunkPos.z, placement.frequency())) {
			return false;
		}

		final Optional<StructurePlacement.ExclusionZone> exclusionZone = placement.exclusionZone();
		if (exclusionZone.isEmpty()) {
			return true;
		}

		final ServerChunkCache chunkSource = level.getChunkSource();
		return !exclusionZone.get().isPlacementForbidden(chunkSource.getGenerator(), chunkSource.randomState(), seed, chunkPos.x, chunkPos.z);
	}

	@Override
	protected String getName() {
		return "RandomSpreadSearchWorker";
	}

}
