package com.chaosthedude.explorerscompass.worker;

import java.util.List;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

public class GenericSearchWorker extends GridStructureSearchWorker<StructurePlacement> {

	public GenericSearchWorker(SearchContext context, StructurePlacement placement, List<Structure> structureSet) {
		// Unlike the placements that only put a structure on a grid of their own, a placement this mod
		// knows nothing about could put one in any chunk at all, so the grid walked here is the chunks
		super(context, placement, structureSet, 1);
	}

	@Override
	protected ChunkPos candidateChunk(int chunkX, int chunkZ) {
		return new ChunkPos(chunkX, chunkZ);
	}

	@Override
	protected String getName() {
		return "GenericSearchWorker";
	}

}
