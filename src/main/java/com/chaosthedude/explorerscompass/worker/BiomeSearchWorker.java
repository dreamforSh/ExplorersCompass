package com.chaosthedude.explorerscompass.worker;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.chaosthedude.explorerscompass.items.ExplorersCompassItem;
import com.mojang.datafixers.util.Pair;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
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

	// How far a location has to be from an already located one to count as a different patch of the
	// same biome. A biome fills a region rather than sitting at a point, so the two chunks that tell
	// two structures apart would have a search for a further instance answering with the next sample
	// along, still inside the biome it was told to look past.
	private static final int SAME_BIOME_DISTANCE = 512;

	private final BiomeSource biomeSource;
	private final Climate.Sampler sampler;
	private final Set<Holder<Biome>> targets;
	private final int spacing;
	// The heights sampled in every column, the one the search started at first
	private final int[] quartYLevels;
	private final RingWalker ring = new RingWalker();

	public BiomeSearchWorker(ServerLevel level, Player player, ItemStack stack, BlockPos startPos, List<Holder<Biome>> biomes, List<BlockPos> prevPos, boolean isGroup, boolean ignoreNearStart, String managerId) {
		super(level, player, stack, startPos, prevPos, isGroup, ignoreNearStart, ConfigHandler.GENERAL.maxBiomeSamples.get(), managerId);

		biomeSource = level.getChunkSource().getGenerator().getBiomeSource();
		sampler = level.getChunkSource().randomState().sampler();
		spacing = ConfigHandler.GENERAL.biomeSampleSpacing.get();
		quartYLevels = computeQuartYLevels(level, startPos.getY());

		// A biome source names the biomes it produces outright, so a target it does not name cannot be
		// here and would otherwise be searched for until the configured limits ran out. The holders
		// kept are the source's own rather than the ones that were asked for, so that the sampling
		// loop can compare what it is handed against them directly.
		final Set<ResourceLocation> targetKeys = new HashSet<ResourceLocation>();
		for (Holder<Biome> biome : biomes) {
			biome.unwrapKey().ifPresent((biomeKey) -> targetKeys.add(biomeKey.location()));
		}
		targets = new HashSet<Holder<Biome>>();
		for (Holder<Biome> possibleBiome : biomeSource.possibleBiomes()) {
			final Optional<ResourceKey<Biome>> biomeKey = possibleBiome.unwrapKey();
			if (biomeKey.isPresent() && targetKeys.contains(biomeKey.get().location())) {
				targets.add(possibleBiome);
			}
		}
		finished = targets.isEmpty();
	}

	@Override
	protected boolean doSample() {
		if (hasWork()) {
			final int sampleX = startPos.getX() + spacing * ring.getX();
			final int sampleZ = startPos.getZ() + spacing * ring.getZ();

			// The corners of a ring reach past its edges, so part of the outer rings lies beyond the
			// configured radius
			if (isWithinMaxRadius(sampleX, sampleZ)) {
				currentPos = new BlockPos(sampleX, startPos.getY(), sampleZ);

				final Pair<BlockPos, ResourceLocation> pair = getTargetBiomeAt(sampleX, sampleZ);
				if (pair != null && !shouldIgnore(pair.getFirst())) {
					succeed(pair.getFirst(), pair.getSecond());
				}
			}

			ring.advance();
		}

		if (hasWork()) {
			return true;
		}

		if (!finished) {
			fail();
		}

		return false;
	}

	/**
	 * Returns the position and key of the first biome searched for that fills part of the column at
	 * the given location, or null when none of them does.
	 */
	private Pair<BlockPos, ResourceLocation> getTargetBiomeAt(int x, int z) {
		final int quartX = QuartPos.fromBlock(x);
		final int quartZ = QuartPos.fromBlock(z);
		for (int quartY : quartYLevels) {
			final Holder<Biome> biome = biomeSource.getNoiseBiome(quartX, quartY, quartZ, sampler);
			samples++;
			if (!targets.contains(biome)) {
				continue;
			}

			final Optional<ResourceKey<Biome>> biomeKey = biome.unwrapKey();
			if (biomeKey.isPresent()) {
				// A height is only worth reporting where it is what sets the biome apart. The one the
				// search started at is reached by whatever fills the surface as much as by everything
				// else, so a match there says nothing beyond where to walk; a match anywhere else is a
				// biome filling the caves, and how far down it lies is most of what there is to know.
				final int y = quartY == quartYLevels[0] ? ExplorersCompassItem.UNKNOWN_Y : QuartPos.toBlock(quartY);
				return Pair.of(new BlockPos(x, y, z), biomeKey.get().location());
			}
		}

		return null;
	}

	/**
	 * The heights each column is sampled at, as quart positions and nearest the height the search
	 * started at first.
	 *
	 * <p>Sampling the whole height of the dimension is what makes the biomes filling the caves
	 * findable from the surface, and costs a search as many samples per column as there are heights
	 * to sample. Starting at the height the search was started from is what keeps a search standing
	 * in a forest answering with a forest, rather than with a cave under one further away.
	 */
	private static int[] computeQuartYLevels(ServerLevel level, int startY) {
		final int startQuartY = QuartPos.fromBlock(Mth.clamp(startY, level.getMinBuildHeight(), level.getMaxBuildHeight() - 1));
		final int verticalSpacing = ConfigHandler.GENERAL.biomeVerticalSampleSpacing.get();
		if (verticalSpacing <= 0) {
			return new int[] { startQuartY };
		}

		final List<Integer> quartYLevels = new ArrayList<Integer>();
		quartYLevels.add(startQuartY);
		for (int y = level.getMinBuildHeight(); y < level.getMaxBuildHeight(); y += verticalSpacing) {
			final int quartY = QuartPos.fromBlock(y);
			if (!quartYLevels.contains(quartY)) {
				quartYLevels.add(quartY);
			}
		}
		quartYLevels.sort(Comparator.comparingInt((Integer quartY) -> Math.abs(quartY - startQuartY)));

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
	 * this is the ring that has been walked all the way around rather than the location sampled
	 * last, since the cells of a ring are walked row by row and the first of them is a corner.
	 */
	@Override
	protected int getRadius() {
		return Math.min(ring.getCoveredLength() * spacing, maxRadius);
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
