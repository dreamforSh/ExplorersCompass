package com.chaosthedude.explorerscompass.worker;

import java.util.ArrayList;
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
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureCheckResult;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

public abstract class StructureSearchWorker<T extends StructurePlacement> extends BackgroundSearchWorker {

	/**
	 * How many locations one turn covers. A location that has already been answered for costs a map
	 * lookup, so handing the turn back after each of them spends much of a search on passing the turn
	 * round; one that has not can read a chunk off the disk, so a batch has to stay small enough that
	 * a whole batch of those still fits inside the tick it is taken in.
	 */
	private static final int SAMPLES_PER_TURN = 16;

	protected final T placement;
	protected final List<Structure> structureSet;
	protected final long seed;

	/** Where these structures would generate, asked of world generation rather than of storage. */
	protected final StructurePrediction prediction;

	/**
	 * What a structure search reads off the world and the settings before it starts. Gathered into
	 * one value so that a search can also be put together without either, which is how the tests walk
	 * one over a world of their own making.
	 *
	 * @param maxSamples          how many locations the search may look at
	 * @param seed                what the chunk generator seeds structure placement with
	 * @param prediction          where these structures would generate, or null for a search that is
	 *                            never asked to predict
	 * @param generatesStructures whether the world generates structures at all
	 * @param async               whether the search may run off the server thread
	 */
	record Setup(int maxSamples, long seed, StructurePrediction prediction, boolean generatesStructures, boolean async) {

		static Setup read(SearchContext context, StructurePlacement placement, List<Structure> structureSet) {
			final ServerLevel level = context.getLevel();
			// The seed placement is worked out from is the generator's rather than the level's: they are
			// the same in vanilla, but a mod giving a dimension a seed of its own changes the one only.
			// The world generation settings were split out of the level data into options of their own.
			return new Setup(ConfigHandler.GENERAL.maxSamples.get(), level.getChunkSource().getGeneratorState().getLevelSeed(), new StructurePrediction(level, placement, structureSet),
					level.getServer().getWorldData().worldGenOptions().generateStructures(), ConfigHandler.GENERAL.asyncStructureSearch.get());
		}

	}

	public StructureSearchWorker(SearchContext context, T placement, List<Structure> structureSet) {
		this(context, placement, structureSet, Setup.read(context, placement, structureSet));
	}

	StructureSearchWorker(SearchContext context, T placement, List<Structure> structureSet, Setup setup) {
		super(context, setup.maxSamples());
		this.structureSet = structureSet;
		this.placement = placement;

		seed = setup.seed();
		prediction = setup.prediction();
		finished = !setup.generatesStructures();
	}

	@Override
	int getSamplesPerTurn() {
		return SAMPLES_PER_TURN;
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
		final BlockPos locatePos = getLocatePos(chunkPos);
		if (shouldIgnore(locatePos)) {
			return null;
		}

		return readStructureAt(chunkPos, locatePos, structureSet, null, ExplorersCompassItem.UNKNOWN_Y);
	}

	/**
	 * Asks chunk storage which of the given structures the chunk holds, in the order given, and
	 * answers with the first it holds. A structure storage only says is there, without the start
	 * itself being loaded, is reported at the height it was predicted at when it is the one that was
	 * predicted, and at an unknown height otherwise.
	 */
	private Pair<BlockPos, Structure> readStructureAt(ChunkPos chunkPos, BlockPos locatePos, List<Structure> structures, Structure predicted, int predictedY) {
		ChunkAccess chunkAccess = null;
		SectionPos sectionPos = null;
		for (Structure structure : structures) {
			if (chunkAccess == null) {
				// The placement now has to be handed in rather than being looked up from the structure's
				// own set, which is what this worker was built around anyway
				StructureCheckResult result = level.structureManager().checkStructurePresence(chunkPos, structure, placement, false);
				if (result == StructureCheckResult.START_NOT_PRESENT) {
					continue;
				}
				if (result == StructureCheckResult.START_PRESENT) {
					// The start itself was not loaded on this path, so the height is whatever world generation
					// was predicted to settle on, if anything was
					return Pair.of(new BlockPos(locatePos.getX(), structure == predicted ? predictedY : ExplorersCompassItem.UNKNOWN_Y, locatePos.getZ()), structure);
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
	 * Returns the position and structure world generation would put in the given chunk, or null if
	 * there is none.
	 *
	 * <p>Reads no part of the world, so unlike {@link #getStructureGeneratingAt} this may be asked off
	 * the server thread — which is the whole point of it, since the cost of a structure search is the
	 * cost of answering this. What it cannot do is have the last word on a chunk that was generated
	 * already: see {@link StructurePrediction} and {@link #confirmStructureAt}.
	 */
	protected Pair<BlockPos, Structure> predictStructureGeneratingAt(ChunkPos chunkPos) {
		if (!canPlaceAt(chunkPos)) {
			return null;
		}

		final BlockPos locatePos = getLocatePos(chunkPos);
		if (shouldIgnore(locatePos)) {
			return null;
		}

		final Pair<Structure, BlockPos> generated = prediction.structureAt(chunkPos);
		if (generated == null) {
			return null;
		}

		// Reported where this chunk's structure stands rather than where it was worked out to settle,
		// since that is where an already generated one would be reported, but at the height world
		// generation settled on, which storage only knows once a chunk is loaded
		return Pair.of(new BlockPos(locatePos.getX(), generated.getSecond().getY(), locatePos.getZ()), generated.getFirst());
	}

	/**
	 * Puts a structure a prediction turned up to chunk storage, and answers with where it stands and
	 * what it is, or null when storage holds none of the structures being searched for there. Reads
	 * chunks, so this runs on the server thread.
	 *
	 * <p>Every structure being searched for is asked about, not only the one predicted. A chunk that
	 * was generated under other settings can hold another structure of the set, and when that is one
	 * being searched for as well, it is as good an answer as the one predicted. The one predicted is
	 * asked about first, since that is almost always the one there.
	 */
	protected Pair<BlockPos, Structure> confirmStructureAt(ChunkPos chunkPos, Structure predicted, int predictedY) {
		List<Structure> order = structureSet;
		if (!structureSet.isEmpty() && structureSet.get(0) != predicted) {
			order = new ArrayList<Structure>(structureSet.size() + 1);
			order.add(predicted);
			for (Structure structure : structureSet) {
				if (structure != predicted) {
					order.add(structure);
				}
			}
		}
		return readStructureAt(chunkPos, getLocatePos(chunkPos), order, predicted, predictedY);
	}

	/**
	 * Where a structure in the given chunk is reported, which is what every distance a structure
	 * search compares is measured to.
	 */
	protected BlockPos getLocatePos(ChunkPos chunkPos) {
		return placement.getLocatePos(chunkPos);
	}

	/**
	 * Whether a chunk is inside the configured search radius. Chunks outside it are not sampled: the
	 * search is not meant to reach that far, and a structure found there could not be reported.
	 */
	protected boolean isWithinMaxRadius(ChunkPos chunkPos) {
		return isWithinMaxRadius(chunkPos.getMiddleBlockX(), chunkPos.getMiddleBlockZ());
	}

	/**
	 * Whether a chunk is worth sampling: inside the radius, and nearer than what was located. Measured
	 * to where a structure there would be reported rather than to the middle of the chunk, since that
	 * is what a find is measured to: the two are up to a chunk's width apart, and the difference would
	 * pass over a location only a few blocks nearer than the one already found.
	 */
	protected boolean isWorthSampling(ChunkPos chunkPos) {
		final BlockPos locatePos = getLocatePos(chunkPos);
		return isWorthSampling(locatePos.getX(), locatePos.getZ());
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
