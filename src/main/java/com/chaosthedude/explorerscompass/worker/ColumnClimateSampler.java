package com.chaosthedude.explorerscompass.worker;

import com.chaosthedude.explorerscompass.ExplorersCompass;

import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * A climate sampler that works out the parts of a location's climate that do not change with height
 * only once for each column it is asked about.
 *
 * <p>Six things decide which biome generates somewhere, and a search of the world away from the
 * height it was started at asks about several heights of the same column one after another. In the
 * generator the game ships, only how deep a location is changes down a column; the temperature,
 * the humidity, how far inland it is, how eroded it is and how strange it is are all worked out from
 * where the column stands and nothing else. Sampling a column of five heights therefore works those
 * five out five times over, and each of them is a run of noise.
 *
 * <p>Nothing says a generator has to be like that, though: a world generation data pack is free to
 * write a climate that changes with height. So the assumption is checked rather than trusted — see
 * {@link #climateIsSameDownAColumn} — and a search whose generator does not hold to it goes on
 * asking about every height in full.
 */
final class ColumnClimateSampler {

	/** How many columns the check looks at. Scattered, so that one flat region cannot speak for all. */
	private static final int PROBE_COLUMNS = 12;

	/** How far apart in quart positions those columns are, growing as they go. */
	private static final int PROBE_SPACING = 373;

	private ColumnClimateSampler() {
	}

	/**
	 * Whether the parts of the climate this would cache really are the same all the way up and down a
	 * column, in the generator this world is using.
	 *
	 * <p>Answered by asking, at a scattering of columns, what each of them says at the bottom of the
	 * world, at the top, and halfway between. That is a check rather than a proof: a generator could
	 * be built whose climate changes with height only somewhere none of these columns look. It is a
	 * strong enough one for the generators worlds are actually made with, and what it guards against
	 * is a search reading the biome of the wrong height — so it is deliberately cheap to run and
	 * deliberately quick to give up.
	 */
	static boolean climateIsSameDownAColumn(Climate.Sampler sampler, ServerLevel level, BlockPos startPos) {
		final int minQuartY = QuartPos.fromBlock(level.getMinBuildHeight());
		final int maxQuartY = QuartPos.fromBlock(level.getMaxBuildHeight() - 1);
		if (minQuartY >= maxQuartY) {
			return true;
		}

		final int middleQuartY = (minQuartY + maxQuartY) / 2;
		final int startQuartX = QuartPos.fromBlock(startPos.getX());
		final int startQuartZ = QuartPos.fromBlock(startPos.getZ());
		for (int probe = 0; probe < PROBE_COLUMNS; probe++) {
			// Walked out in both directions and turning as it goes, so that the columns are neither in a
			// line nor all in the region the search happens to have started in
			final int step = (probe + 1) * PROBE_SPACING;
			final int quartX = startQuartX + ((probe & 1) == 0 ? step : -step);
			final int quartZ = startQuartZ + ((probe & 2) == 0 ? step : -step);
			for (DensityFunction function : columnFunctions(sampler)) {
				final double atTheTop = valueAt(function, quartX, maxQuartY, quartZ);
				if (valueAt(function, quartX, middleQuartY, quartZ) != atTheTop || valueAt(function, quartX, minQuartY, quartZ) != atTheTop) {
					return false;
				}
			}
		}

		return true;
	}

	/**
	 * The same sampler, answering for the parts of a climate that do not change with height out of
	 * what it last worked out for the column being asked about.
	 *
	 * <p>What it keeps is one column, so a search has to ask about a column's heights one after
	 * another to gain anything, and each thread of a search needs one of its own.
	 */
	static Climate.Sampler cachedByColumn(Climate.Sampler sampler) {
		return new Climate.Sampler(
				new ColumnCache(sampler.temperature()),
				new ColumnCache(sampler.humidity()),
				new ColumnCache(sampler.continentalness()),
				new ColumnCache(sampler.erosion()),
				// How deep a location is is the one that changes down a column, so it is left alone
				sampler.depth(),
				new ColumnCache(sampler.weirdness()),
				sampler.spawnTarget());
	}

	private static DensityFunction[] columnFunctions(Climate.Sampler sampler) {
		return new DensityFunction[] { sampler.temperature(), sampler.humidity(), sampler.continentalness(), sampler.erosion(), sampler.weirdness() };
	}

	private static double valueAt(DensityFunction function, int quartX, int quartY, int quartZ) {
		try {
			return function.compute(new DensityFunction.SinglePointContext(QuartPos.toBlock(quartX), QuartPos.toBlock(quartY), QuartPos.toBlock(quartZ)));
		} catch (Throwable t) {
			// A generator that cannot answer this at all is one nothing should be assumed about
			ExplorersCompass.LOGGER.warn("Could not check whether this world's climate changes with height; biome searches will sample every height in full", t);
			return Double.NaN;
		}
	}

	/**
	 * One part of a climate, answered out of what it last worked out for the column it was asked
	 * about. Only ever used by the thread that made it.
	 */
	private static final class ColumnCache implements DensityFunction {

		private final DensityFunction function;

		private int columnX;
		private int columnZ;
		private double value;
		private boolean known;

		private ColumnCache(DensityFunction function) {
			this.function = function;
		}

		@Override
		public double compute(FunctionContext context) {
			final int blockX = context.blockX();
			final int blockZ = context.blockZ();
			if (known && blockX == columnX && blockZ == columnZ) {
				return value;
			}

			value = function.compute(context);
			columnX = blockX;
			columnZ = blockZ;
			known = true;
			return value;
		}

		@Override
		public void fillArray(double[] values, ContextProvider provider) {
			// A search asks about one location at a time, so nothing here takes this path; handing it
			// straight on keeps the answer right if anything ever does
			function.fillArray(values, provider);
		}

		@Override
		public DensityFunction mapAll(Visitor visitor) {
			// Whatever is being built out of this wants the climate itself rather than a search's view
			// of it, so the caching is left behind
			return function.mapAll(visitor);
		}

		@Override
		public double minValue() {
			return function.minValue();
		}

		@Override
		public double maxValue() {
			return function.maxValue();
		}

		@Override
		public KeyDispatchDataCodec<? extends DensityFunction> codec() {
			// This never leaves the search that made it, so it is never written out; what it wraps is
			// the closest thing to an honest answer
			return function.codec();
		}

	}

}
