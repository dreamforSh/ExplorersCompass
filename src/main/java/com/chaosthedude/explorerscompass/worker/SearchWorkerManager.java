package com.chaosthedude.explorerscompass.worker;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.util.StructureUtils;

import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

/**
 * Runs the workers one search is made of, and settles on what the search answers with.
 *
 * <p>One of these is kept per player and reused, so it is the slot a player's search runs in rather
 * than the search itself; what identifies a search is its {@link SearchContext}.
 *
 * <p>A search for several structures at once is split into a worker per placement, since which
 * locations are worth sampling follows from the placement rather than from the structure. Each of
 * them walks outwards from the player and answers with the nearest location <em>of its own
 * placement</em>, which says nothing about the others. The manager therefore keeps running the
 * remaining workers and answers with the nearest of everything they turn up.
 *
 * <p>What keeps that from costing several full searches is that a location bounds the ones still to
 * come: a worker that has covered as far as the nearest location found so far cannot improve on it,
 * so it is cut down to that radius and stops there. A search whose answer is nearby therefore still
 * finishes almost at once, and only one that finds nothing pays the full radius for every
 * placement — which is what it cost before as well.
 *
 * <p>So that a bound turns up as early as there is one to be had, the workers take turns rather
 * than running one after another: each searches out to a band that widens once they have all
 * reached it. Because a worker picks up where it left off, taking turns costs no more samples than
 * running them in order does — it only changes which of them spends them first. Without it, a
 * search whose first placement holds nothing at all would pay the whole radius for it before the
 * one with the answer had sampled anything.
 *
 * <p>A worker searching on a thread of its own has no need of any of that: it is not waiting for a
 * turn to make progress, and the ones beside it are not waiting for it. Those hand the turn back
 * until their thread is done, so the turn goes round them until each has been started, and then
 * round them again as each finishes and has something to report.
 *
 * <p>The manager is what the server thread schedules, not the workers: it is registered with
 * {@link SearchScheduler} as one search, and hands the turn on internally. A worker therefore only
 * ever runs while its search holds the turn, and one belonging to a search that has been stopped or
 * replaced can no longer reach anything.
 */
public class SearchWorkerManager implements SearchScheduler.SearchSlice {

	/**
	 * How far the first turn round the workers searches. It doubles from here, so this only decides
	 * how fine grained the early turns are, not how far the search reaches.
	 */
	private static final int INITIAL_BAND_RADIUS = 1000;

	/** Granularity in blocks of the search radius reported to the compass while a search is running. */
	private static final int RADIUS_REPORT_INTERVAL = 250;

	/**
	 * The workers still to run, in the order they were created. The one at the front holds the turn,
	 * each is dropped as it finishes, and the search is over once none are left.
	 */
	private final List<SearchWorker> workers = new ArrayList<SearchWorker>();

	/** What the search currently running is fixed by, and what identifies it. */
	private SearchContext context;

	// The nearest location any worker has turned up so far
	private BlockPos locatedPos;
	private ResourceLocation locatedKey;
	private long locatedDistanceSqr;

	// How far the turn the workers are currently taking searches, and the ceiling it grows towards
	private int bandRadius;
	private int maxRadius;

	// What the whole search has done, for the report once it is over
	private int samples;
	private int radius;
	private int lastRadiusThreshold;

	/**
	 * Whether the worker at the front has already been given the limits for the turn it is holding.
	 * They are set once, when it takes the turn: setting them again after it has covered the band
	 * would widen that band and hand it the next turn as well, when the whole point of reaching the
	 * band is that the turn passes to someone else.
	 */
	private boolean turnGranted;

	public String getId() {
		return context != null ? context.getId() : "none";
	}

	/** Sets up a search out of the workers it is made of. */
	void createWorkers(SearchContext context, List<SearchWorker> created) {
		reset(context);
		workers.addAll(created);
	}

	public void createStructureWorkers(SearchContext context, List<Structure> structures) {
		final List<SearchWorker> created = new ArrayList<SearchWorker>();

		// Linked so that placements stay in the order the structures were requested in
		final Map<StructurePlacement, List<Structure>> placementToStructuresMap = new Object2ObjectLinkedOpenHashMap<>();

		for (Structure structure : structures) {
			final Holder<Structure> holder = StructureUtils.getHolderForStructure(context.getLevel(), structure);
			if (holder == null) {
				ExplorersCompass.LOGGER.warn("Search " + context.getId() + ": skipping a structure that is not registered in this world");
				continue;
			}

			for (StructurePlacement structureplacement : context.getLevel().getChunkSource().getGenerator().getPlacementsForStructure(holder, context.getLevel().getChunkSource().randomState())) {
				placementToStructuresMap.computeIfAbsent(structureplacement, (holderSet) -> {
					return new ObjectArrayList<Structure>();
				}).add(structure);
			}
		}

		for (Map.Entry<StructurePlacement, List<Structure>> entry : placementToStructuresMap.entrySet()) {
			final StructurePlacement placement = entry.getKey();
			if (placement instanceof ConcentricRingsStructurePlacement) {
				created.add(new ConcentricRingsSearchWorker(context, (ConcentricRingsStructurePlacement) placement, entry.getValue()));
			} else if (placement instanceof RandomSpreadStructurePlacement) {
				created.add(new RandomSpreadSearchWorker(context, (RandomSpreadStructurePlacement) placement, entry.getValue()));
			} else {
				created.add(new GenericSearchWorker(context, placement, entry.getValue()));
			}
		}

		createWorkers(context, created);
	}

	/**
	 * Creates the workers a biome search is made of. Where structures are split up by the placement
	 * that puts them in the world, every biome of a dimension comes out of the one biome source, so
	 * looking for several of them at once costs no more than looking for one; what a biome search is
	 * split by instead is the height being looked at. See {@link BiomeSearchWorker}.
	 */
	public void createBiomeWorkers(SearchContext context, List<Holder<Biome>> biomes) {
		createWorkers(context, BiomeSearchWorker.createLayers(context, biomes));
	}

	private void reset(SearchContext context) {
		workers.clear();
		this.context = context;
		locatedPos = null;
		locatedKey = null;
		locatedDistanceSqr = 0L;
		samples = 0;
		radius = 0;
		lastRadiusThreshold = 0;
		bandRadius = 0;
		maxRadius = 0;
		turnGranted = false;
	}

	/** Starts the search. Returns false when there is nothing for it to run. */
	public boolean start() {
		if (workers.isEmpty()) {
			return false;
		}

		maxRadius = context.getMaxRadius();
		// Taking turns only means anything when there is someone to take turns with; a search made of
		// one worker simply runs it to the end
		bandRadius = workers.size() > 1 ? Math.min(INITIAL_BAND_RADIUS, maxRadius) : maxRadius;

		SearchScheduler.add(this);
		return true;
	}

	@Override
	public boolean hasWork() {
		return !workers.isEmpty();
	}

	@Override
	public boolean doWork() {
		if (!context.holdsCompass()) {
			// There is nowhere left to report to, so there is nothing this search could still be for
			ExplorersCompass.LOGGER.error("Search " + context.getId() + ": the compass it was started on is gone");
			stop();
			clear();
			return false;
		}

		// Anything further out than what has already been located cannot be the answer, whichever
		// worker turned it up. Handed to all of them rather than only to whoever takes the turn next,
		// since a worker searching on a thread of its own is not waiting for one.
		applyLocatedLimit();

		// Every pass either hands the turn to a worker, drops one that has finished, or moves one that
		// cannot use the turn to the back of the queue. Moving them all round widens the band, which
		// gives the worker that comes back to the front something to do, so this always ends; the
		// count is a backstop rather than the reason it does.
		int notReady = 0;
		for (int pass = workers.size() + 1; pass > 0 && !workers.isEmpty(); pass--) {
			final SearchWorker worker = workers.get(0);
			// Both of these come before it is asked whether it can sample, since a worker searching on a
			// thread of its own answers no for as long as it runs: starting that thread is what putting it
			// to work does, and how far it has got is the only sign the compass has that anything is
			// happening at all
			worker.begin();
			reportRadius(worker.getRadius());

			if (!worker.isReady()) {
				// Waiting for something of its own, such as the positions a placement is still computing.
				// Another worker of this search can have the turn in the meantime, instead of everything
				// else it is looking for standing still until this one is ready.
				if (++notReady >= workers.size()) {
					// Every worker of the search is waiting, so there is nothing to spend the turn on
					return false;
				}
				moveToBack();
				continue;
			}

			notReady = 0;
			if (!turnGranted) {
				applyLimits(worker);
				turnGranted = true;
			}
			if (!worker.hasWork()) {
				endTurn(worker);
				continue;
			}

			return sampleTurn(worker);
		}

		if (workers.isEmpty()) {
			report();
		}
		return false;
	}

	/**
	 * Runs the worker holding the turn for as many locations as one of its turns covers, and takes
	 * note of whatever it turned up. Returns whether it could have carried straight on.
	 *
	 * <p>Sampling several locations per turn is what keeps what it costs to hand the turn out from
	 * being most of what a cheap location costs. See {@link SearchWorker#getSamplesPerTurn}.
	 */
	private boolean sampleTurn(SearchWorker worker) {
		boolean again = worker.doWork();
		for (int sample = worker.getSamplesPerTurn(); again && sample > 1; sample--) {
			again = worker.doWork();
		}

		onCandidate(worker);
		return again;
	}

	/** Cuts every worker of the search down to the nearest location any of them has located. */
	private void applyLocatedLimit() {
		if (locatedPos == null) {
			return;
		}

		final int limit = SearchWorker.ceilSqrt(locatedDistanceSqr);
		for (SearchWorker worker : workers) {
			worker.setRadiusLimit(limit);
		}
	}

	/**
	 * Takes the worker holding the turn off it: to the back of the queue when it has only reached the
	 * end of the band the search has covered, and out of the search for good when it is finished.
	 */
	private void endTurn(SearchWorker worker) {
		// Whatever it turned up bounds the workers that have not run, whether it is done or not
		onCandidate(worker);

		if (!worker.isExhausted()) {
			moveToBack();
			return;
		}

		workers.remove(0);
		turnGranted = false;
		worker.finish();
		samples += worker.getSamples();
		radius = Math.max(radius, roundRadius(worker.getRadius()));
	}

	private void moveToBack() {
		workers.add(workers.remove(0));
		turnGranted = false;
	}

	/**
	 * Takes note of the nearest location a worker has turned up so far. The worker carries on: what
	 * is left of the ring it found something on may hold a nearer one. Knowing about it already is
	 * what lets the workers that have not run be cut down to it.
	 */
	private void onCandidate(SearchWorker worker) {
		// Read once: a worker searching on several threads has them replacing this while the server
		// thread reads it, and reading the parts one at a time could pair a location with another
		// location's key
		final SearchWorker.Found found = worker.getBest();
		if (found == null) {
			return;
		}

		if (locatedPos == null || found.distanceSqr() < locatedDistanceSqr) {
			locatedPos = found.pos();
			locatedKey = found.key();
			locatedDistanceSqr = found.distanceSqr();
		}
	}

	/**
	 * Sets how far the worker about to take its turn may search: no further than the band the search
	 * has reached. Reaching the front of the queue having already covered the current band means every
	 * worker has, so the band widens.
	 */
	private void applyLimits(SearchWorker next) {
		while (bandRadius < maxRadius && next.getRadius() >= bandRadius) {
			bandRadius = Math.min(bandRadius * 2, maxRadius);
		}
		next.setBandLimit(bandRadius);
	}

	/**
	 * Tells the compass how far the search has looked, when it has passed the last radius reported.
	 * The threshold is the search's rather than each worker's, so that the readout only ever grows:
	 * the workers of one search each start over from where the player is standing, and a radius
	 * dropping back to nothing partway through would read as the search having restarted.
	 */
	private void reportRadius(int workerRadius) {
		final int threshold = workerRadius / RADIUS_REPORT_INTERVAL;
		if (threshold <= lastRadiusThreshold) {
			return;
		}

		lastRadiusThreshold = threshold;
		context.reportRadius(threshold * RADIUS_REPORT_INTERVAL);
	}

	/** Hands the outcome of the whole search to the compass. */
	private void report() {
		if (locatedPos != null) {
			context.reportLocated(locatedPos, locatedKey, samples);
		} else {
			context.reportNotFound(radius, samples);
		}
	}

	private static int roundRadius(int radius) {
		return (radius / RADIUS_REPORT_INTERVAL) * RADIUS_REPORT_INTERVAL;
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
