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
import net.minecraftforge.common.WorldWorkerManager;

public abstract class StructureSearchWorker<T extends StructurePlacement> implements WorldWorkerManager.IWorker {

	// Granularity in blocks of the search radius reported to the compass while a search is running
	private static final int RADIUS_REPORT_INTERVAL = 250;

	protected String managerId;
	protected ServerLevel level;
	protected Player player;
	protected ItemStack stack;
	protected BlockPos startPos;
	protected BlockPos currentPos;
	protected T placement;
	protected List<Structure> structureSet;
	protected long seed;
	protected int samples;
	protected boolean finished;
	protected int lastRadiusThreshold;

	// When this worker started working during the current tick, or -1 if it is not currently working
	private long sliceStartTime;

	public StructureSearchWorker(ServerLevel level, Player player, ItemStack stack, BlockPos startPos, T placement, List<Structure> structureSet, String managerId) {
		this.level = level;
		this.player = player;
		this.stack = stack;
		this.startPos = startPos;
		this.structureSet = structureSet;
		this.placement = placement;
		this.managerId = managerId;

		seed = level.getSeed();
		currentPos = startPos;
		samples = 0;
		sliceStartTime = -1L;

		finished = !level.getServer().getWorldData().worldGenSettings().generateStructures();
	}

	public void start() {
		if (!stack.isEmpty() && stack.getItem() == ExplorersCompass.explorersCompass) {
			if (ConfigHandler.GENERAL.maxRadius.get() > 0) {
				ExplorersCompass.LOGGER.info("SearchWorkerManager " + managerId + ": " + getName() + " starting with " + (shouldLogRadius() ? ConfigHandler.GENERAL.maxRadius.get() + " max radius, " : "") + ConfigHandler.GENERAL.maxSamples.get() + " max samples");
				WorldWorkerManager.addWorker(this);
			} else {
				fail();
			}
		}
	}

	@Override
	public boolean hasWork() {
		return !finished && getRadius() < ConfigHandler.GENERAL.maxRadius.get() && samples < ConfigHandler.GENERAL.maxSamples.get();
	}

	@Override
	public final boolean doWork() {
		final long now = System.currentTimeMillis();
		if (sliceStartTime < 0L || now - sliceStartTime >= ConfigHandler.GENERAL.maxSearchTimePerTick.get()) {
			// Either this is the first call of a new tick, or the previous slice ran over because a single
			// sample took longer than the whole budget. Start a fresh slice either way.
			sliceStartTime = now;
		}

		boolean callAgain;
		try {
			updateSearchRadius();
			callAgain = doSample();
		} catch (Throwable t) {
			// Sampling touches world generation and chunk storage, both of which can fail for a single
			// structure or chunk. Report the search as failed instead of letting the exception escape into
			// the server tick, where it would take down the server.
			abort(t);
			return false;
		}

		// Forge hands a worker the remainder of the tick and only checks the clock between calls, so a
		// search that samples expensive locations can stall the server for as long as it likes. Give up
		// the rest of the tick once this worker has used its slice.
		if (!callAgain || System.currentTimeMillis() - sliceStartTime >= ConfigHandler.GENERAL.maxSearchTimePerTick.get()) {
			sliceStartTime = -1L;
			return false;
		}

		return true;
	}

	/**
	 * Samples a single location. Returns true if this worker should be called again, false if it is
	 * done.
	 */
	protected abstract boolean doSample();

	/**
	 * Returns the position and structure generating in the given chunk, or null if there is none.
	 */
	protected Pair<BlockPos, Structure> getStructureGeneratingAt(ChunkPos chunkPos) {
		if (!canPlaceAt(chunkPos)) {
			return null;
		}

		for (Structure structure : structureSet) {
			StructureCheckResult result = level.structureManager().checkStructurePresence(chunkPos, structure, false);
			if (result != StructureCheckResult.START_NOT_PRESENT) {
				if (result == StructureCheckResult.START_PRESENT) {
					return Pair.of(placement.getLocatePos(chunkPos), structure);
				}

				ChunkAccess chunkAccess = level.getChunk(chunkPos.x, chunkPos.z, ChunkStatus.STRUCTURE_STARTS);
				StructureStart structureStart = level.structureManager().getStartForStructure(SectionPos.bottomOf(chunkAccess), structure, chunkAccess);
				if (structureStart != null && structureStart.isValid()) {
					return Pair.of(placement.getLocatePos(structureStart.getChunkPos()), structure);
				}
			}
		}

		return null;
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

		ExplorersCompass.LOGGER.info("SearchWorkerManager " + managerId + ": " + getName() + " succeeded with " + (shouldLogRadius() ? getRadius() + " radius, " : "") + samples + " samples");
		finished = true;
		if (!stack.isEmpty() && stack.getItem() == ExplorersCompass.explorersCompass) {
			((ExplorersCompassItem) stack.getItem()).succeed(stack, structureKey, pos.getX(), pos.getZ(), samples, ConfigHandler.GENERAL.displayCoordinates.get());
		} else {
			ExplorersCompass.LOGGER.error("SearchWorkerManager " + managerId + ": " + getName() + " found invalid compass after successful search");
		}
	}

	protected void fail() {
		ExplorersCompass.LOGGER.info("SearchWorkerManager " + managerId + ": " + getName() + " failed with " + (shouldLogRadius() ? getRadius() + " radius, " : "") + samples + " samples");
		// Mark this worker as finished before handing off to the compass: notifying it starts the next
		// worker for this search, and this one must not be considered live anymore if anything there
		// goes wrong.
		finished = true;
		if (!stack.isEmpty() && stack.getItem() == ExplorersCompass.explorersCompass) {
			((ExplorersCompassItem) stack.getItem()).fail(stack, roundRadius(getRadius(), RADIUS_REPORT_INTERVAL), samples);
		} else {
			ExplorersCompass.LOGGER.error("SearchWorkerManager " + managerId + ": " + getName() + " found invalid compass after failed search");
		}
	}

	public void stop() {
		ExplorersCompass.LOGGER.info("SearchWorkerManager " + managerId + ": " + getName() + " stopped with " + (shouldLogRadius() ? getRadius() + " radius, " : "") + samples + " samples");
		finished = true;
	}

	protected void abort(Throwable cause) {
		ExplorersCompass.LOGGER.error("SearchWorkerManager " + managerId + ": " + getName() + " encountered an error while searching", cause);
		sliceStartTime = -1L;
		if (!finished) {
			fail();
		}
	}

	protected void updateSearchRadius() {
		int radius = getRadius();
		if (radius > RADIUS_REPORT_INTERVAL && radius / RADIUS_REPORT_INTERVAL > lastRadiusThreshold) {
			if (!stack.isEmpty() && stack.getItem() == ExplorersCompass.explorersCompass) {
				((ExplorersCompassItem) stack.getItem()).setSearchRadius(stack, roundRadius(radius, RADIUS_REPORT_INTERVAL), player);
			}
			lastRadiusThreshold = radius / RADIUS_REPORT_INTERVAL;
		}
	}

	protected int getRadius() {
		return StructureUtils.getHorizontalDistanceToLocation(startPos, currentPos.getX(), currentPos.getZ());
	}

	protected int roundRadius(int radius, int roundTo) {
		return ((int) radius / roundTo) * roundTo;
	}

	protected abstract String getName();

	protected abstract boolean shouldLogRadius();

}
