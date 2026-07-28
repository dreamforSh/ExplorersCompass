package com.chaosthedude.explorerscompass.worker;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.RandomStringUtils;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.util.StructureUtils;

import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

/**
 * Runs the workers one search is made of, and settles on what the search answers with.
 *
 * <p>A search for several structures at once is split into a worker per placement, since which
 * locations are worth sampling follows from the placement rather than from the structure. Each of
 * them walks outwards from the player, so the first thing any of them finds is the nearest one
 * <em>of its own placement</em> and says nothing about the others. The manager therefore keeps
 * running the remaining workers and answers with the nearest of everything they turn up.
 *
 * <p>What keeps that from costing several full searches is that a location bounds the ones still to
 * come: a worker that has covered as far as the nearest location found so far cannot improve on it,
 * so it is cut down to that radius and stops there. A search whose answer is nearby therefore still
 * finishes almost at once, and only one that finds nothing pays the full radius for every
 * placement — which is what it cost before as well.
 */
public class SearchWorkerManager {

	private final String id = RandomStringUtils.random(8, "0123456789abcdef");

	// The workers still to run, in the order they were created. Each is dropped as it finishes, and
	// the search is over once none are left.
	private final List<SearchWorker> workers = new ArrayList<SearchWorker>();

	// Where the current search started, which the located positions are measured from
	private BlockPos startPos;

	// The nearest location any worker has turned up so far, and the worker that turned it up
	private SearchWorker locatedBy;
	private BlockPos locatedPos;
	private ResourceLocation locatedKey;
	private long locatedDistanceSqr;

	// What the whole search has done, for the report once it is over
	private int samples;
	private int radius;
	private int lastRadiusThreshold;

	// Whether a worker is being started from inside the loop that advances the search. A worker can
	// finish the moment it is started, which comes straight back here; the loop then carries on with
	// the next one, rather than this recursing once for every worker the search is made of.
	private boolean advancing;

	// The worker that finished last, which is the one that reports a search that located nothing
	private SearchWorker lastFinished;

	public String getId() {
		return id;
	}

	public void createStructureWorkers(ServerLevel level, Player player, ItemStack stack, List<Structure> structures, BlockPos startPos, List<BlockPos> prevPos, boolean isGroup, boolean ignoreNearStart) {
		reset(startPos);

		// Linked so that placements stay in the order the structures were requested in
		Map<StructurePlacement, List<Structure>> placementToStructuresMap = new Object2ObjectLinkedOpenHashMap<>();

		for (Structure structure : structures) {
			Holder<Structure> holder = StructureUtils.getHolderForStructure(level, structure);
			if (holder == null) {
				ExplorersCompass.LOGGER.warn("SearchWorkerManager " + id + ": skipping a structure that is not registered in this world");
				continue;
			}

			for (StructurePlacement structureplacement : level.getChunkSource().getGenerator().getPlacementsForStructure(holder, level.getChunkSource().randomState())) {
				placementToStructuresMap.computeIfAbsent(structureplacement, (holderSet) -> {
					return new ObjectArrayList<Structure>();
				}).add(structure);
			}
		}

		for (Map.Entry<StructurePlacement, List<Structure>> entry : placementToStructuresMap.entrySet()) {
			StructurePlacement placement = entry.getKey();
			if (placement instanceof ConcentricRingsStructurePlacement) {
				workers.add(new ConcentricRingsSearchWorker(level, player, stack, startPos, (ConcentricRingsStructurePlacement) placement, entry.getValue(), prevPos, isGroup, ignoreNearStart, this));
			} else if (placement instanceof RandomSpreadStructurePlacement) {
				workers.add(new RandomSpreadSearchWorker(level, player, stack, startPos, (RandomSpreadStructurePlacement) placement, entry.getValue(), prevPos, isGroup, ignoreNearStart, this));
			} else {
				workers.add(new GenericSearchWorker(level, player, stack, startPos, placement, entry.getValue(), prevPos, isGroup, ignoreNearStart, this));
			}
		}
	}

	/**
	 * Creates the single worker a biome search takes. Where structures are split up by the placement
	 * that puts them in the world, every biome of a dimension comes out of the one biome source, so
	 * looking for several of them at once costs no more than looking for one.
	 */
	public void createBiomeWorker(ServerLevel level, Player player, ItemStack stack, List<Holder<Biome>> biomes, BlockPos startPos, List<BlockPos> prevPos, boolean isGroup, boolean ignoreNearStart) {
		reset(startPos);
		workers.add(new BiomeSearchWorker(level, player, stack, startPos, biomes, prevPos, isGroup, ignoreNearStart, this));
	}

	private void reset(BlockPos startPos) {
		workers.clear();
		this.startPos = startPos;
		locatedBy = null;
		locatedPos = null;
		locatedKey = null;
		locatedDistanceSqr = 0L;
		samples = 0;
		radius = 0;
		lastRadiusThreshold = 0;
		advancing = false;
		lastFinished = null;
	}

	/** Starts the search. Returns false when there is nothing for it to run. */
	public boolean start() {
		if (workers.isEmpty()) {
			return false;
		}
		workers.get(0).start();
		return true;
	}

	/** Takes what a worker located and moves on to the next one. */
	void onLocated(SearchWorker worker, BlockPos pos, ResourceLocation key, int workerSamples) {
		final long distanceSqr = StructureUtils.getHorizontalDistanceSqrToLocation(startPos, pos.getX(), pos.getZ());
		if (locatedPos == null || distanceSqr < locatedDistanceSqr) {
			locatedBy = worker;
			locatedPos = pos;
			locatedKey = key;
			locatedDistanceSqr = distanceSqr;
		}
		samples += workerSamples;
		runNext(worker);
	}

	/** Takes note of a worker that has nothing left to search and moves on to the next one. */
	void onExhausted(SearchWorker worker, int workerRadius, int workerSamples) {
		samples += workerSamples;
		radius = Math.max(radius, workerRadius);
		runNext(worker);
	}

	/**
	 * Whether the search has looked further than the last radius it reported, which is what decides
	 * if the compass is told about it. Shared by every worker of a search, so that the readout only
	 * ever grows as the search moves from one placement to the next.
	 */
	boolean tryReportRadius(int threshold) {
		if (threshold <= lastRadiusThreshold) {
			return false;
		}
		lastRadiusThreshold = threshold;
		return true;
	}

	/** Starts the next worker that has something to do, or reports the outcome when none is left. */
	private void runNext(SearchWorker finishedWorker) {
		if (!workers.remove(finishedWorker)) {
			// This worker no longer belongs to a live search: it was stopped, or the search it was part
			// of has already been replaced by another one
			return;
		}

		lastFinished = finishedWorker;
		if (advancing) {
			// Started from the loop below, which carries on with the next worker itself
			return;
		}

		advancing = true;
		try {
			while (!workers.isEmpty()) {
				final SearchWorker next = workers.get(0);
				if (locatedPos != null) {
					// Anything further out than what has already been located cannot be the answer
					next.setRadiusLimit((int) Math.sqrt(locatedDistanceSqr));
				}

				next.start();
				if (!workers.isEmpty() && workers.get(0) == next) {
					// It has taken itself on and is running now, so the rest of the search waits for it
					return;
				}
			}
		} finally {
			advancing = false;
		}

		report(lastFinished);
	}

	/** Hands the outcome of the whole search to the compass. */
	private void report(SearchWorker lastWorker) {
		if (locatedBy != null) {
			locatedBy.reportLocated(locatedPos, locatedKey, samples);
		} else {
			// Any worker can say the search found nothing; they all hold the same compass
			lastWorker.reportNotFound(radius, samples);
		}
	}

	public void stop() {
		for (SearchWorker worker : workers) {
			worker.stop();
		}
	}

	public void clear() {
		workers.clear();
	}

}
