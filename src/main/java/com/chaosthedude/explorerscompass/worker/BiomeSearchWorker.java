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
 * Searches one layer of the world for a biome, by asking the dimension's biome source what it puts
 * at a location and walking outwards from where the search started.
 *
 * <p>Unlike a structure search this never reads a chunk: which biome generates somewhere follows
 * from the world seed and the generator's noise alone, so a location can be sampled without any of
 * it having been generated. A single sample is therefore far cheaper than a structure one, but a
 * search takes many more of them, since a biome can lie anywhere rather than only where a placement
 * allows.
 *
 * <p>A search is made of two of these, which {@link #createLayers} builds. One samples the height
 * the search was started at, at every location of its grid, since that is where the biome being
 * looked for usually is. The other samples the rest of the dimension, which is what makes the biomes
 * filling the caves findable from the surface, on a grid coarser by the depth interval along each
 * axis — a biome that fills the caves covers far more ground than the spacing between two locations,
 * so it is still found without paying for the whole height of the world at every location.
 *
 * <p>Keeping them apart rather than sampling the depths at every so many locations of one walk is
 * what lets each bound the other: a search that turns up its biome at the surface five hundred
 * blocks away stops looking underground past five hundred blocks, and the other way round. Each is
 * then a plain grid walked nearest first, rather than a fine grid with a coarse one hidden inside
 * it, and how much of the search each of them is allowed follows from how much ground each covers.
 *
 * <p>A layer can be split between several threads, which is what {@code asyncBiomeSearch} turns on.
 * Each of them walks its own share of the rows and keeps its own count of what it has sampled and
 * covered; what the layer has covered is the least of what they have, since a row nobody has reached
 * is ground nobody has searched.
 */
public class BiomeSearchWorker extends BackgroundSearchWorker {

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

	private final String name;
	private final BiomeSource biomeSource;
	private final Climate.Sampler sampler;
	// The biomes being searched for that this dimension can actually produce, against their keys
	private final Map<Biome, ResourceLocation> targets;
	private final int spacing;
	/** The heights this layer samples, as quart positions, in the order they are worth trying. */
	private final int[] quartYLevels;
	/** Whether those are the height the search was started at rather than the rest of the world. */
	private final boolean atSearchHeight;

	/** Whether this layer may be split between threads. Decided once, before either side starts. */
	private final boolean async;

	/** The shares this layer is split into, one per thread it may be given. */
	private final Shard[] shards;

	/**
	 * The workers a biome search is made of. See the note on this class for what each of them covers
	 * and why they are kept apart.
	 */
	static List<SearchWorker> createLayers(SearchContext context, List<Holder<Biome>> biomes) {
		final ServerLevel level = context.getLevel();
		final Map<Biome, ResourceLocation> targets = resolveTargets(level.getChunkSource().getGenerator().getBiomeSource(), biomes);

		final int spacing = ConfigHandler.GENERAL.biomeSampleSpacing.get();
		final int depthInterval = ConfigHandler.GENERAL.biomeDepthSampleInterval.get();
		final int surfaceQuartY = QuartPos.fromBlock(Mth.clamp(context.getStartPos().getY(), level.getMinBuildHeight(), level.getMaxBuildHeight() - 1));
		final int[] depthQuartYLevels = computeDepthQuartYLevels(level, surfaceQuartY);

		// The two layers share out what one biome search is allowed the way they spend it. Over the
		// same ground the height the search started at takes one sample per location of its grid, and
		// the rest of the dimension takes one per height of a grid coarser by the depth interval along
		// each axis, so between them they still take what a single walk sampling both used to.
		final int maxSamples = ConfigHandler.GENERAL.maxBiomeSamples.get();
		final long surfaceWeight = (long) depthInterval * depthInterval;
		final long depthWeight = depthQuartYLevels.length;
		final int surfaceSamples = (int) (maxSamples * surfaceWeight / (surfaceWeight + depthWeight));

		final List<SearchWorker> layers = new ArrayList<SearchWorker>();
		layers.add(new BiomeSearchWorker(context, "SurfaceBiomeSearchWorker", targets, spacing, new int[] { surfaceQuartY }, true, surfaceSamples));
		if (depthQuartYLevels.length > 0) {
			layers.add(new BiomeSearchWorker(context, "DepthBiomeSearchWorker", targets, spacing * depthInterval, depthQuartYLevels, false, maxSamples - surfaceSamples));
		}
		return layers;
	}

	private BiomeSearchWorker(SearchContext context, String name, Map<Biome, ResourceLocation> targets, int spacing, int[] quartYLevels, boolean atSearchHeight, int maxSamples) {
		super(context, maxSamples);

		this.name = name;
		this.targets = targets;
		this.spacing = spacing;
		this.quartYLevels = quartYLevels;
		this.atSearchHeight = atSearchHeight;

		biomeSource = level.getChunkSource().getGenerator().getBiomeSource();
		sampler = level.getChunkSource().randomState().sampler();
		async = ConfigHandler.GENERAL.asyncBiomeSearch.get();

		// As many shares as there are threads to walk them: fewer would leave threads idle, and more
		// would only queue up behind each other. On the server thread there is one of everything.
		final int shardCount = async ? Math.max(1, SearchExecutor.getThreadCount()) : 1;
		final int shardSamples = (maxSamples + shardCount - 1) / shardCount;
		shards = new Shard[shardCount];
		for (int i = 0; i < shardCount; i++) {
			shards[i] = new Shard(new DiscWalker(shardCount, i), shardSamples);
		}

		finished = targets.isEmpty();
	}

	@Override
	protected boolean isBackgroundAllowed() {
		return async;
	}

	@Override
	protected List<Runnable> createBackgroundTasks() {
		final List<Runnable> tasks = new ArrayList<Runnable>(shards.length);
		for (Shard shard : shards) {
			tasks.add(shard::walk);
		}
		return tasks;
	}

	@Override
	int getSamplesPerTurn() {
		return SAMPLES_PER_TURN;
	}

	@Override
	protected boolean doSample() {
		if (isBackground()) {
			return applyBackgroundResult();
		}

		// On the server thread there is only ever the one share to walk
		shards[0].sampleNext();
		return hasWork();
	}

	/**
	 * Notes that the searching threads have finished, so that the manager takes this worker off the
	 * search and reports what it turned up. Runs on the server thread, which is where everything a
	 * result touches belongs.
	 */
	private boolean applyBackgroundResult() {
		if (!isBackgroundDone() || isApplied()) {
			return false;
		}

		markApplied();
		if (getBackgroundError() != null && !finished) {
			abort(getBackgroundError());
		}
		return false;
	}

	@Override
	int getSamples() {
		int total = 0;
		for (Shard shard : shards) {
			total += shard.samples;
		}
		return total;
	}

	@Override
	protected boolean hasMoreToSample() {
		for (Shard shard : shards) {
			if (shard.hasMoreToSample(getEffectiveRadiusLimit())) {
				return true;
			}
		}
		return false;
	}

	@Override
	protected boolean isExhausted() {
		if (isBackground()) {
			return isApplied();
		}

		// The band a single turn is held to says nothing about whether there is anything left to search
		for (Shard shard : shards) {
			if (shard.hasMoreToSample(getRadiusLimit())) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Returns the position and key of the first biome searched for that this layer finds at the given
	 * location, or null when it finds none.
	 */
	private Pair<BlockPos, ResourceLocation> getTargetBiomeAt(int x, int z, Shard shard) {
		final int quartX = QuartPos.fromBlock(x);
		final int quartZ = QuartPos.fromBlock(z);

		for (int quartY : quartYLevels) {
			final Holder<Biome> biome = biomeSource.getNoiseBiome(quartX, quartY, quartZ, sampler);
			shard.samples++;
			final ResourceLocation key = targets.get(biome.value());
			if (key != null) {
				// The height the search started at is reached by whatever fills the surface as much as by
				// everything else, so a match there says nothing beyond which way to walk. A match at any
				// other height is a biome filling the caves, and how far down it lies is most of what
				// there is to know about it.
				return Pair.of(new BlockPos(x, atSearchHeight ? ExplorersCompassItem.UNKNOWN_Y : QuartPos.toBlock(quartY), z), key);
			}
		}

		return null;
	}

	/**
	 * The biomes being searched for that this dimension can actually produce.
	 *
	 * <p>A biome source names the biomes it produces outright, so a target it does not name cannot be
	 * here and would otherwise be searched for until the configured limits ran out. What is kept is
	 * the biome itself rather than the holder wrapping it: the holder a source hands back while
	 * sampling need not be the one it listed, and comparing holders would then take every sample for
	 * a miss and quietly find nothing at all.
	 */
	private static Map<Biome, ResourceLocation> resolveTargets(BiomeSource biomeSource, List<Holder<Biome>> biomes) {
		final Set<ResourceLocation> targetKeys = new HashSet<ResourceLocation>();
		for (Holder<Biome> biome : biomes) {
			biome.unwrapKey().ifPresent((biomeKey) -> targetKeys.add(biomeKey.location()));
		}

		final Map<Biome, ResourceLocation> targets = new IdentityHashMap<Biome, ResourceLocation>();
		for (Holder<Biome> possibleBiome : biomeSource.possibleBiomes()) {
			possibleBiome.unwrapKey().ifPresent((biomeKey) -> {
				if (targetKeys.contains(biomeKey.location())) {
					targets.put(possibleBiome.value(), biomeKey.location());
				}
			});
		}
		return targets;
	}

	/**
	 * The heights away from the one the search was started at, as quart positions and nearest that
	 * height first, so that a match just below the surface wins over one at the bottom of the world.
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
	 * The radius this layer has finished searching: the least of what its shares have covered, since
	 * a row none of them has reached yet is ground none of them has searched.
	 *
	 * <p>Like the search for a randomly spread structure this is the band that has been walked all
	 * the way round rather than the location sampled last, since a location of the band being walked
	 * lies anywhere on it. While the search runs on threads of its own, the server thread reads this
	 * for the readout on the compass and may see a band they have already left behind. That is all it
	 * is used for there: what is left to search is answered by {@link #hasWork()} without reading any
	 * of this.
	 */
	@Override
	protected int getRadius() {
		int covered = Integer.MAX_VALUE;
		for (Shard shard : shards) {
			covered = Math.min(covered, shard.getRadius());
		}
		return covered;
	}

	@Override
	protected String getName() {
		return name;
	}

	@Override
	public boolean shouldLogRadius() {
		return true;
	}

	/**
	 * One thread's share of a layer: the rows of the grid that fall to it, and how far along them it
	 * has got.
	 *
	 * <p>A share is only ever walked by one thread, so what it counts is its own and is read once the
	 * threads are done. How far it has covered is read while it walks, for the readout on the compass,
	 * so that is published — but only when it changes, since paying for a write every other thread has
	 * to see at every single location is most of what a location costs to sample.
	 */
	private final class Shard {

		private final DiscWalker walker;
		private final int maxSamples;

		private int samples;
		private volatile int coveredLength;

		private Shard(DiscWalker walker, int maxSamples) {
			this.walker = walker;
			this.maxSamples = maxSamples;
		}

		/** Samples until there is nothing left to look at. */
		private void walk() {
			while (!Thread.currentThread().isInterrupted() && hasMoreToSample(getEffectiveRadiusLimit())) {
				sampleNext();
			}
		}

		/**
		 * Samples the location this share has reached and moves on to the next one, taking note of
		 * anything it found there. Touches nothing outside its own layer, which is what lets it run off
		 * the server thread.
		 */
		private void sampleNext() {
			final int sampleX = startPos.getX() + spacing * walker.getX();
			final int sampleZ = startPos.getZ() + spacing * walker.getZ();

			// The walk covers a disc a band at a time, so part of the band being walked lies beyond the
			// configured radius, and beyond anything already located
			if (isWorthSampling(sampleX, sampleZ)) {
				final Pair<BlockPos, ResourceLocation> pair = getTargetBiomeAt(sampleX, sampleZ, this);
				if (pair != null && !shouldIgnore(pair.getFirst())) {
					found(pair.getFirst(), pair.getSecond());
				}
			}

			walker.advance();
			final int covered = walker.getCoveredLength();
			if (covered != coveredLength) {
				coveredLength = covered;
			}
		}

		private int getRadius() {
			return Math.min(coveredLength * spacing, maxRadius);
		}

		private boolean hasMoreToSample(int limit) {
			return !finished && getRadius() < limit && samples < maxSamples;
		}

	}

}
