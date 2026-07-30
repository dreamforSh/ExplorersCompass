package com.chaosthedude.explorerscompass.worker;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.chaosthedude.explorerscompass.items.ExplorersCompassItem;
import com.mojang.datafixers.util.Pair;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

/**
 * Searches for a biome by asking the dimension's biome source what it puts at a location, walking
 * outwards from where the search started.
 *
 * <p>Unlike a structure search this never reads a chunk: which biome generates somewhere follows
 * from the world seed and the generator's noise alone, so a location can be sampled without any of
 * it having been generated. A single sample is therefore far cheaper than a structure one, but a
 * search takes many more of them, since a biome can lie anywhere rather than only where a placement
 * allows.
 */
public class BiomeSearchWorker extends SearchWorker {

	/**
	 * How far a location has to be from an already located one to count as a different patch of the
	 * same biome. A biome fills a region rather than sitting at a point, so the two chunks that tell
	 * two structures apart would have a search for a further instance answering with the next sample
	 * along, still inside the biome it was told to look past.
	 */
	private static final int SAME_BIOME_DISTANCE = 512;

	/**
	 * How many locations a turn covers when the sampling runs on the server thread. A single sample
	 * costs a fraction of a microsecond, so handing the turn back after each of them would spend more
	 * of the tick on passing it round than on the search; a batch this size is still a small part of
	 * the time one turn is given.
	 */
	private static final int SAMPLES_PER_TURN = 64;

	private final BiomeSource biomeSource;
	private final Climate.Sampler sampler;
	// The biomes being searched for that this dimension can actually produce, against their keys
	private final Map<Biome, ResourceLocation> targets;
	private final int spacing;
	/** The height the search was started at, sampled at every location. */
	private final int surfaceQuartY;
	// The rest of the heights of the dimension, sampled on a coarser grid and nearest the height the
	// search started at first
	private final int[] depthQuartYLevels;
	private final int depthInterval;
	private final DiscWalker walker = new DiscWalker();

	/** Whether the sampling is running on a thread of its own. Set before either side starts. */
	private volatile boolean background;

	/**
	 * How far the search has covered. Written by whichever thread is sampling and read by the server
	 * thread for the readout on the compass, so it is published rather than derived from the walker.
	 */
	private volatile int coveredRadius;

	// That the search thread is done. Publishing this last is what makes everything the search
	// thread wrote before it visible to the server thread that sees it set.
	private volatile boolean backgroundDone;
	private Throwable backgroundError;

	/** Whether the server thread has already acted on that. Only ever touched on the server thread. */
	private boolean applied;

	public BiomeSearchWorker(SearchContext context, List<Holder<Biome>> biomes) {
		super(context, ConfigHandler.GENERAL.maxBiomeSamples.get());

		biomeSource = level.getChunkSource().getGenerator().getBiomeSource();
		sampler = level.getChunkSource().randomState().sampler();
		spacing = ConfigHandler.GENERAL.biomeSampleSpacing.get();
		depthInterval = ConfigHandler.GENERAL.biomeDepthSampleInterval.get();
		surfaceQuartY = QuartPos.fromBlock(Mth.clamp(startPos.getY(), level.getMinBuildHeight(), level.getMaxBuildHeight() - 1));
		depthQuartYLevels = computeDepthQuartYLevels(level, surfaceQuartY);

		final Set<ResourceLocation> targetKeys = new HashSet<ResourceLocation>();
		for (Holder<Biome> biome : biomes) {
			biome.unwrapKey().ifPresent((biomeKey) -> targetKeys.add(biomeKey.location()));
		}

		// A biome source names the biomes it produces outright, so a target it does not name cannot be
		// here and would otherwise be searched for until the configured limits ran out. What is kept is
		// the biome itself rather than the holder wrapping it: the holder a source hands back while
		// sampling need not be the one it listed, and comparing holders would then take every sample
		// for a miss and quietly find nothing at all.
		targets = new IdentityHashMap<Biome, ResourceLocation>();
		for (Holder<Biome> possibleBiome : biomeSource.possibleBiomes()) {
			possibleBiome.unwrapKey().ifPresent((biomeKey) -> {
				if (targetKeys.contains(biomeKey.location())) {
					targets.put(possibleBiome.value(), biomeKey.location());
				}
			});
		}
		finished = targets.isEmpty();
	}

	@Override
	protected void onBegin() {
		if (!ConfigHandler.GENERAL.asyncBiomeSearch.get()) {
			return;
		}

		background = true;
		try {
			SearchExecutor.execute(this::searchInBackground);
		} catch (Throwable t) {
			// Nothing is going to run it, so answer as though it had run and failed rather than leaving
			// the server thread watching for a result that will never arrive
			backgroundError = t;
			backgroundDone = true;
		}
	}

	/** Samples until there is nothing left to look at, off the server thread. */
	private void searchInBackground() {
		try {
			while (!Thread.currentThread().isInterrupted() && hasMoreToSample()) {
				sampleNext();
			}
		} catch (Throwable t) {
			backgroundError = t;
		} finally {
			backgroundDone = true;
		}
	}

	@Override
	boolean hasWork() {
		// While the search runs on a thread of its own, this stays true until the server thread has
		// acted on the outcome, however far the search has got: what is left to sample is the search
		// thread's business, and reading it from here would be a race
		return background ? !applied : super.hasWork();
	}

	@Override
	int getSamplesPerTurn() {
		return SAMPLES_PER_TURN;
	}

	@Override
	protected boolean doSample() {
		if (background) {
			return applyBackgroundResult();
		}

		sampleNext();
		return hasWork();
	}

	/**
	 * Notes that the search thread has finished, so that the manager takes this worker off the search
	 * and reports what it turned up. Runs on the server thread, which is where everything a result
	 * touches belongs.
	 */
	private boolean applyBackgroundResult() {
		if (!backgroundDone || applied) {
			return false;
		}

		applied = true;
		if (backgroundError != null && !finished) {
			abort(backgroundError);
		}
		return false;
	}

	/**
	 * Samples the location the search has reached and moves on to the next one, taking note of
	 * anything it found there. Touches nothing outside this worker, which is what lets it run off the
	 * server thread.
	 */
	private void sampleNext() {
		final int sampleX = startPos.getX() + spacing * walker.getX();
		final int sampleZ = startPos.getZ() + spacing * walker.getZ();

		// The walk covers a disc a band at a time, so part of the band being walked lies beyond the
		// configured radius, and beyond anything already located
		if (isWorthSampling(sampleX, sampleZ)) {
			final Pair<BlockPos, ResourceLocation> pair = getTargetBiomeAt(sampleX, sampleZ, isDepthSampleLocation());
			if (pair != null && !shouldIgnore(pair.getFirst())) {
				found(pair.getFirst(), pair.getSecond());
			}
		}

		walker.advance();
		coveredRadius = Math.min(walker.getCoveredLength() * spacing, maxRadius);
	}

	/**
	 * A biome search is a single worker, so there is never another waiting to take a turn and this
	 * one simply runs to the end of what it is allowed.
	 */
	@Override
	protected boolean isExhausted() {
		return true;
	}

	/**
	 * Returns the position and key of the first biome searched for that fills part of the column at
	 * the given location, or null when none of them does.
	 *
	 * <p>The height the search started at is always sampled. The rest of the dimension is only
	 * sampled where the caller says so, which is what keeps the whole height of the world in reach
	 * without paying for it at every location. See {@link #isDepthSampleLocation}.
	 */
	private Pair<BlockPos, ResourceLocation> getTargetBiomeAt(int x, int z, boolean sampleDepths) {
		final int quartX = QuartPos.fromBlock(x);
		final int quartZ = QuartPos.fromBlock(z);

		final ResourceLocation surfaceKey = targetBiomeKeyAt(quartX, surfaceQuartY, quartZ);
		if (surfaceKey != null) {
			// The height the search started at is reached by whatever fills the surface as much as by
			// everything else, so a match there says nothing beyond which way to walk
			return Pair.of(new BlockPos(x, ExplorersCompassItem.UNKNOWN_Y, z), surfaceKey);
		}

		if (sampleDepths) {
			for (int quartY : depthQuartYLevels) {
				final ResourceLocation depthKey = targetBiomeKeyAt(quartX, quartY, quartZ);
				if (depthKey != null) {
					// A match at any other height is a biome filling the caves, and how far down it lies
					// is most of what there is to know about it
					return Pair.of(new BlockPos(x, QuartPos.toBlock(quartY), z), depthKey);
				}
			}
		}

		return null;
	}

	/** The key of the biome at a sampled position, or null when it is not one being searched for. */
	private ResourceLocation targetBiomeKeyAt(int quartX, int quartY, int quartZ) {
		final Holder<Biome> biome = biomeSource.getNoiseBiome(quartX, quartY, quartZ, sampler);
		samples++;
		return targets.get(biome.value());
	}

	/**
	 * Whether the heights away from the one the search started at are sampled at the location being
	 * visited.
	 *
	 * <p>Sampling every height everywhere costs a search as many samples per location as the
	 * dimension is tall, and almost all of them are spent below a surface biome that was going to
	 * answer the search anyway. A biome that fills the caves covers far more ground than the spacing
	 * between two locations, so it is still found while only every so many locations look down at
	 * all. The location the search starts from is one of them, so standing in a cave still answers
	 * with the cave.
	 */
	private boolean isDepthSampleLocation() {
		return depthQuartYLevels.length > 0 && Math.floorMod(walker.getX(), depthInterval) == 0 && Math.floorMod(walker.getZ(), depthInterval) == 0;
	}

	/**
	 * The heights sampled other than the one the search was started at, as quart positions and
	 * nearest that height first, so that a match just below the surface wins over one at the bottom
	 * of the world.
	 */
	private static int[] computeDepthQuartYLevels(ServerLevel level, int surfaceQuartY) {
		final int verticalSpacing = ConfigHandler.GENERAL.biomeVerticalSampleSpacing.get();
		if (verticalSpacing <= 0) {
			return new int[0];
		}

		final List<Integer> quartYLevels = new ArrayList<Integer>();
		for (int y = level.getMinBuildHeight(); y < level.getMaxBuildHeight(); y += verticalSpacing) {
			final int quartY = QuartPos.fromBlock(y);
			if (quartY != surfaceQuartY && !quartYLevels.contains(quartY)) {
				quartYLevels.add(quartY);
			}
		}
		quartYLevels.sort(Comparator.comparingInt((Integer quartY) -> Math.abs(quartY - surfaceQuartY)));

		final int[] levels = new int[quartYLevels.size()];
		for (int i = 0; i < levels.length; i++) {
			levels[i] = quartYLevels.get(i).intValue();
		}
		return levels;
	}

	@Override
	protected int getSameLocationDistance() {
		return SAME_BIOME_DISTANCE;
	}

	/**
	 * The radius this worker has finished searching. Like the search for a randomly spread structure
	 * this is the band that has been walked all the way round rather than the location sampled last,
	 * since a location of the band being walked lies anywhere on it.
	 *
	 * <p>While the search runs on a thread of its own, the server thread reads this for the readout
	 * on the compass and may see a band it has already left behind. That is all it is used for
	 * there: what is left to search is answered by {@link #hasWork()} without reading any of this,
	 * and the value the search ends on is read only once it has published that it finished.
	 */
	@Override
	protected int getRadius() {
		return coveredRadius;
	}

	@Override
	protected String getName() {
		return "BiomeSearchWorker";
	}

	@Override
	public boolean shouldLogRadius() {
		return true;
	}

}
