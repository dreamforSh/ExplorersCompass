package com.chaosthedude.explorerscompass.worker;

import java.util.List;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.chaosthedude.explorerscompass.items.ExplorersCompassItem;
import com.chaosthedude.explorerscompass.util.StructureUtils;
import com.mojang.datafixers.util.Pair;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureCheckResult;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

public abstract class StructureSearchWorker<T extends StructurePlacement> extends SearchWorker {

	protected final T placement;
	protected final List<Structure> structureSet;
	protected final long seed;

	public StructureSearchWorker(SearchContext context, T placement, List<Structure> structureSet) {
		super(context, ConfigHandler.GENERAL.maxSamples.get());
		this.structureSet = structureSet;
		this.placement = placement;

		seed = level.getSeed();
		// The world generation settings were split out of the level data into options of their own
		finished = !level.getServer().getWorldData().worldGenOptions().generateStructures();
	}

	/**
	 * Returns the position and structure generating in the given chunk, or null if there is none.
	 *
	 * <p>Asking whether a structure is present reads the chunk from storage, and answering it may
	 * run structure generation, so the chunk is loaded at most once here: its structure starts are
	 * the authoritative answer for every remaining structure, and reading them is a lookup. This is
	 * what makes searching for a whole group cost about as much as searching for one of its
	 * structures.
	 */
	protected Pair<BlockPos, Structure> getStructureGeneratingAt(ChunkPos chunkPos) {
		if (!canPlaceAt(chunkPos)) {
			return null;
		}

		// Every structure in a chunk is reported at the position the placement gives for that chunk,
		// and a start always belongs to the chunk it was read from, so where this chunk would answer
		// with is known before anything has been read for it. A location an earlier search already
		// found is therefore passed over without asking whether a structure is present, which on that
		// path can read the chunk from storage and run structure generation to answer.
		final BlockPos locatePos = placement.getLocatePos(chunkPos);
		if (shouldIgnore(locatePos)) {
			return null;
		}

		ChunkAccess chunkAccess = null;
		SectionPos sectionPos = null;
		for (Structure structure : structureSet) {
			if (chunkAccess == null) {
				// The placement now has to be handed in rather than being looked up from the structure's
				// own set, which is what this worker was built around anyway
				StructureCheckResult result = level.structureManager().checkStructurePresence(chunkPos, structure, placement, false);
				if (result == StructureCheckResult.START_NOT_PRESENT) {
					continue;
				}
				if (result == StructureCheckResult.START_PRESENT) {
					// The start itself was not loaded on this path, so the height is unknown
					return Pair.of(new BlockPos(locatePos.getX(), ExplorersCompassItem.UNKNOWN_Y, locatePos.getZ()), structure);
				}

				chunkAccess = level.getChunk(chunkPos.x, chunkPos.z, ChunkStatus.STRUCTURE_STARTS);
				sectionPos = SectionPos.bottomOf(chunkAccess);
			}

			StructureStart structureStart = level.structureManager().getStartForStructure(sectionPos, structure, chunkAccess);
			if (structureStart != null && structureStart.isValid()) {
				// The loaded start knows where it generates, so record its height as well
				return Pair.of(new BlockPos(locatePos.getX(), structureStart.getBoundingBox().getCenter().getY(), locatePos.getZ()), structure);
			}
		}

		return null;
	}

	/**
	 * Whether a chunk is inside the configured search radius. Chunks outside it are not sampled: the
	 * search is not meant to reach that far, and a structure found there could not be reported.
	 */
	protected boolean isWithinMaxRadius(ChunkPos chunkPos) {
		return isWithinMaxRadius(chunkPos.getMiddleBlockX(), chunkPos.getMiddleBlockZ());
	}

	/** Whether a chunk is worth sampling: inside the radius, and nearer than what was located. */
	protected boolean isWorthSampling(ChunkPos chunkPos) {
		return isWorthSampling(chunkPos.getMiddleBlockX(), chunkPos.getMiddleBlockZ());
	}

	/**
	 * Whether this placement is allowed to put a structure in the given chunk. This is the same
	 * condition the chunk generator applies when it generates structures, so a chunk it rejects
	 * cannot hold one and the presence check, which reads from disk and may run structure
	 * generation, can be skipped entirely.
	 */
	protected boolean canPlaceAt(ChunkPos chunkPos) {
		// The generator, the random state and the seed this used to be handed one by one are all
		// carried by the structure state now, so it is the only thing the placement still asks for
		return placement.isStructureChunk(level.getChunkSource().getGeneratorState(), chunkPos.x, chunkPos.z);
	}

	protected void found(BlockPos pos, Structure structure) {
		final ResourceLocation structureKey = StructureUtils.getKeyForStructure(level, structure);
		if (structureKey == null) {
			// Nothing could be reported for it, so carry on searching rather than ending here
			ExplorersCompass.LOGGER.error("Search " + context.getId() + ": " + getName() + " located a structure that is not registered in this world");
			return;
		}

		found(pos, structureKey);
	}

}
