package com.chaosthedude.explorerscompass.worker;

import java.util.ArrayList;
import java.util.List;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.items.ExplorersCompassItem;
import com.chaosthedude.explorerscompass.util.StructureUtils;
import com.mojang.datafixers.util.Pair;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

/**
 * Searches for structures put in the world by a kind of placement this mod knows nothing about,
 * which only a mod adding a placement type of its own can bring about.
 *
 * <p>The chunk generator is asked for the nearest one first, the way {@code /locate} asks it. A mod
 * that places structures its own way has usually taught its generator to answer that, since it is
 * what its own {@code /locate} goes through, and where it has, the answer is both the right one and
 * had at once. It is also the only way to reach some of them: the placement's vanilla-facing check
 * is all this mod could otherwise ask, and a mod is free to place a structure by something that
 * check knows nothing about. Twilight Forest is the case in point — its landmarks are settled by the
 * biome at a grid point, its placement's vanilla-facing check only ever picks among a handful of
 * them at random, and the rest can be found by asking its generator or not at all.
 *
 * <p>Only when the generator knows of nothing are the chunks walked one at a time, which is all that
 * is left: a placement this mod knows nothing about could put a structure in any chunk, so nothing
 * can be stepped over. That covers a thousandth of the ground per location looked at that a
 * placement with a grid of its own does, and runs out of the samples it is allowed long before it
 * reaches the configured radius.
 */
public class GenericSearchWorker extends GridStructureSearchWorker<StructurePlacement> {

	/** Whether the generator has been asked yet, which counts as one location looked at. */
	private boolean generatorAsked;

	/** Whether what the generator answered with is what this worker settled on. */
	private boolean settledByGenerator;

	public GenericSearchWorker(SearchContext context, StructurePlacement placement, List<Structure> structureSet) {
		// Unlike the placements that only put a structure on a grid of their own, a placement this mod
		// knows nothing about could put one in any chunk at all, so the grid walked here is the chunks
		super(context, placement, structureSet, 1);
	}

	@Override
	protected void onBegin() {
		boolean settled;
		try {
			settled = askGenerator();
		} catch (Throwable t) {
			// Asking touches world generation and chunk storage, either of which can fail for a single
			// structure or chunk. The generator's answer is a shortcut, not the search, so that is no
			// reason not to walk; the walk asks the same of them and reports what goes wrong there the way
			// every search does.
			ExplorersCompass.LOGGER.warn("Search " + context.getId() + ": " + getName() + " could not ask the chunk generator for the nearest structure, so the chunks will be walked instead", t);
			settled = false;
		}

		if (settled) {
			// What the generator answers with is the nearest there is, so there is nothing left for the
			// walk to improve on, and it never starts. Marking the worker finished is what says so to
			// everything that asks whether there is more to sample, the same way a world generating no
			// structures does.
			finished = true;
			return;
		}
		super.onBegin();
	}

	/**
	 * Asks the chunk generator for the nearest of these structures, and settles on it when it is one
	 * this search could answer with. Returns false when the generator knows of none, or of none this
	 * search can use, and the chunks have to be walked after all.
	 *
	 * <p>Runs on the server thread, in one go: the generator may read chunk storage to answer, and
	 * how long it takes is its own business. The game's own {@code /locate} pays the same price.
	 */
	private boolean askGenerator() {
		final List<Holder<Structure>> holders = new ArrayList<Holder<Structure>>(structureSet.size());
		for (Structure structure : structureSet) {
			final Holder<Structure> holder = StructureUtils.getHolderForStructure(level, structure);
			if (holder != null) {
				holders.add(holder);
			}
		}
		if (holders.isEmpty()) {
			return false;
		}

		// Handed over in chunks, which is the nearest thing the radius has to a unit here: what a
		// generator makes of it is its own business — the game hands /locate a flat 100 — so what comes
		// back is held to this search's radius rather than trusted to lie inside it
		final int radiusChunks = Math.max(1, SectionPos.blockToSectionCoord(getRadiusLimit()));
		final Pair<BlockPos, Holder<Structure>> nearest = level.getChunkSource().getGenerator().findNearestMapStructure(level, HolderSet.direct(holders), startPos, radiusChunks, false);
		generatorAsked = true;

		if (nearest == null) {
			ExplorersCompass.LOGGER.info("Search " + context.getId() + ": " + getName() + " asked the chunk generator for the nearest structure and it knew of none within " + radiusChunks + " chunks, so the chunks will be walked instead");
			return false;
		}

		final Structure structure = nearest.getSecond().value();
		final ChunkPos chunkPos = new ChunkPos(nearest.getFirst());
		// Reported where the walk would report a structure in this chunk, so that the same place
		// answers the same way however it was found — which is what a search for a further instance
		// goes by when it passes over what earlier searches answered with
		final BlockPos locatePos = placement.getLocatePos(chunkPos);
		if (!isWorthSampling(locatePos.getX(), locatePos.getZ())) {
			ExplorersCompass.LOGGER.info("Search " + context.getId() + ": " + getName() + " was told by the chunk generator that the nearest structure lies at " + locatePos.getX() + ", " + locatePos.getZ() + ", beyond the " + getRadiusLimit() + " this search may reach, so the chunks will be walked instead");
			return false;
		}
		if (shouldIgnore(locatePos)) {
			// The generator can only ever answer with the nearest, which is what an earlier search already
			// answered with, so a further one has to be walked for
			ExplorersCompass.LOGGER.info("Search " + context.getId() + ": " + getName() + " was told by the chunk generator that the nearest structure lies at " + locatePos.getX() + ", " + locatePos.getZ() + ", which an earlier search already answered with, so the chunks will be walked for another");
			return false;
		}

		// Storage has the last word, as it has over every location the walk predicts: the chunk may
		// have been generated under settings that no longer put a structure there. The generator says
		// nothing about how high the structure stands, so unless storage does, that stays unknown.
		final BlockPos confirmed = confirmStructureAt(chunkPos, structure, ExplorersCompassItem.UNKNOWN_Y);
		if (confirmed == null) {
			ExplorersCompass.LOGGER.info("Search " + context.getId() + ": " + getName() + " was told by the chunk generator that the nearest structure lies at " + locatePos.getX() + ", " + locatePos.getZ() + ", where the chunk holds no such structure, so the chunks will be walked instead");
			return false;
		}

		ExplorersCompass.LOGGER.info("Search " + context.getId() + ": " + getName() + " was told by the chunk generator that the nearest structure lies at " + confirmed.getX() + ", " + confirmed.getZ() + ", " + StructureUtils.getHorizontalDistanceToLocation(startPos, confirmed.getX(), confirmed.getZ()) + " blocks away, and storage agrees");
		settledByGenerator = true;
		found(confirmed, structure);
		return true;
	}

	@Override
	int getSamples() {
		return super.getSamples() + (generatorAsked ? 1 : 0);
	}

	/**
	 * A worker settled by the generator has searched as far as what it settled on, and no further:
	 * that is what the generator's answer stands for. Reported as such rather than as the nothing the
	 * walk covered, since it is what the compass shows for how far the search reached.
	 */
	@Override
	protected int getRadius() {
		if (settledByGenerator) {
			final Found located = getBest();
			if (located != null) {
				return Math.min(ceilSqrt(located.distanceSqr()), maxRadius);
			}
		}
		return super.getRadius();
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
