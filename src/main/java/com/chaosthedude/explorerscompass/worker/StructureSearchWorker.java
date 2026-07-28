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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureCheckResult;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

public abstract class StructureSearchWorker<T extends StructurePlacement> extends SearchWorker {

	protected T placement;
	protected List<Structure> structureSet;
	protected long seed;

	public StructureSearchWorker(ServerLevel level, Player player, ItemStack stack, BlockPos startPos, T placement, List<Structure> structureSet, List<BlockPos> prevPos, boolean isGroup, boolean ignoreNearStart, String managerId) {
		super(level, player, stack, startPos, prevPos, isGroup, ignoreNearStart, ConfigHandler.GENERAL.maxSamples.get(), managerId);
		this.structureSet = structureSet;
		this.placement = placement;

		seed = level.getSeed();
		finished = !level.getServer().getWorldData().worldGenSettings().generateStructures();
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

		ChunkAccess chunkAccess = null;
		SectionPos sectionPos = null;
		for (Structure structure : structureSet) {
			if (chunkAccess == null) {
				StructureCheckResult result = level.structureManager().checkStructurePresence(chunkPos, structure, false);
				if (result == StructureCheckResult.START_NOT_PRESENT) {
					continue;
				}
				if (result == StructureCheckResult.START_PRESENT) {
					BlockPos pos = placement.getLocatePos(chunkPos);
					if (!shouldIgnore(pos)) {
						// The start itself was not loaded on this path, so the height is unknown
						return Pair.of(new BlockPos(pos.getX(), ExplorersCompassItem.UNKNOWN_Y, pos.getZ()), structure);
					}
					continue;
				}

				chunkAccess = level.getChunk(chunkPos.x, chunkPos.z, ChunkStatus.STRUCTURE_STARTS);
				sectionPos = SectionPos.bottomOf(chunkAccess);
			}

			StructureStart structureStart = level.structureManager().getStartForStructure(sectionPos, structure, chunkAccess);
			if (structureStart != null && structureStart.isValid()) {
				BlockPos pos = placement.getLocatePos(structureStart.getChunkPos());
				if (!shouldIgnore(pos)) {
					// The loaded start knows where it generates, so record its height as well
					return Pair.of(new BlockPos(pos.getX(), structureStart.getBoundingBox().getCenter().getY(), pos.getZ()), structure);
				}
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

	/**
	 * Whether this placement is allowed to put a structure in the given chunk. This is the same
	 * condition the chunk generator applies when it generates structures, so a chunk it rejects
	 * cannot hold one and the presence check, which reads from disk and may run structure
	 * generation, can be skipped entirely.
	 */
	protected boolean canPlaceAt(ChunkPos chunkPos) {
		final ServerChunkCache chunkSource = level.getChunkSource();
		return placement.isStructureChunk(chunkSource.getGenerator(), chunkSource.randomState(), seed,
				chunkPos.x, chunkPos.z);
	}

	protected void succeed(BlockPos pos, Structure structure) {
		final ResourceLocation structureKey = StructureUtils.getKeyForStructure(level, structure);
		if (structureKey == null) {
			ExplorersCompass.LOGGER.error("SearchWorkerManager " + managerId + ": " + getName() + " located a structure that is not registered in this world");
			fail();
			return;
		}

		succeed(pos, structureKey);
	}

}
