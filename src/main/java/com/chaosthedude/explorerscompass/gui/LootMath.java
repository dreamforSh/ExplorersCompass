package com.chaosthedude.explorerscompass.gui;

import java.util.Locale;
import java.util.function.ToIntFunction;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * The arithmetic behind what the loot interface says: how likely an item is across a whole
 * structure rather than in one container, how many containers can be expected to hold it, how a
 * chance is written in the little room under a slot, and which slots a card keeps when it cannot
 * show them all.
 *
 * <p>Kept apart from anything that draws, and from anything that needs the game running, so that
 * every number the player reads can be checked by a test on its own.
 *
 * <p>Chances arrive in thousandths, rounded to the nearest one where the preview is built. A chance
 * of 1000 therefore means at least 0.9995 rather than certainty in the strict sense, and is treated
 * as certain all the same: a table that misses one time in two thousand is, for anyone reading a
 * card, a table that always gives the item.
 */
@OnlyIn(Dist.CLIENT)
final class LootMath {

	/** Thousandths of certainty. */
	private static final int ALWAYS = 1000;
	/** The widest a count may be written in the corner of a slot before only the larger end is kept. */
	private static final int COUNT_MAX_WIDTH = 18;
	/** The largest count written in the corner of a slot; anything over it is said to be more. */
	private static final int MAX_GRID_COUNT = 999;

	private LootMath() {
	}

	/** The tier a chance in thousandths falls in. */
	static LootTier tier(int permille) {
		return LootTier.of(permille);
	}

	/**
	 * The chance that at least one of the given containers holds an item, where each table in turn
	 * gives it with the given chance in thousandths and is named by the given number of containers.
	 *
	 * <p>Worked as one less the chance that every container misses, and summed as logarithms rather
	 * than multiplied out, since a structure with hundreds of containers of a one-in-a-thousand item
	 * would otherwise lose the answer to rounding long before it got there. Whether the item is
	 * certain outright is left to {@link #isCertain}.
	 */
	static double structureChance(int[] permilles, int[] containers) {
		double logSum = 0.0D;
		for (int i = 0; i < permilles.length; i++) {
			// A table no container names adds nothing, and a certain one would make nothing times infinity of it
			if (containers[i] <= 0) {
				continue;
			}
			logSum += containers[i] * Math.log1p(-Math.min(ALWAYS, permilles[i]) / (double) ALWAYS);
		}
		return -Math.expm1(logSum);
	}

	/** Whether some container is all but sure to hold the item: a table that always gives it, named at least once. */
	static boolean isCertain(int[] permilles, int[] containers) {
		for (int i = 0; i < permilles.length; i++) {
			if (permilles[i] >= ALWAYS && containers[i] >= 1) {
				return true;
			}
		}
		return false;
	}

	/**
	 * A structure-wide chance in thousandths, for a slot's label and tier. Anything short of certain
	 * stays short of 1000 and above 0, so that an item a player could still miss never reads as
	 * guaranteed, and one that can be found at all never reads as nothing.
	 */
	static int structurePermille(double chance, boolean certain) {
		if (certain) {
			return ALWAYS;
		}
		return Math.max(1, Math.min(ALWAYS - 1, (int) Math.round(chance * ALWAYS)));
	}

	/** How many of the given containers can be expected to hold the item, between them. */
	static double expected(int[] permilles, int[] containers) {
		double sum = 0.0D;
		for (int i = 0; i < permilles.length; i++) {
			sum += containers[i] * (permilles[i] / (double) ALWAYS);
		}
		return sum;
	}

	/**
	 * How much an item is worth finding, as an ordinal of the game's rarities, raised a step where it
	 * comes enchanted. The game raises a stack's rarity the same way only for a stack that really is
	 * enchanted; the stacks drawn here only glint, so the raise is made here instead.
	 */
	static int promotedWorth(int baseOrdinal, boolean enchanted) {
		if (!enchanted) {
			return baseOrdinal;
		}
		if (baseOrdinal <= 1) {
			return 2;
		}
		if (baseOrdinal == 2) {
			return 3;
		}
		return baseOrdinal;
	}

	/** A chance in thousandths, written as a percentage with a decimal only where it says something. */
	static String formatChance(int permille) {
		if (permille >= ALWAYS) {
			return "100%";
		}
		if (permille <= 0) {
			return "0%";
		}
		if (permille % 10 == 0) {
			return (permille / 10) + "%";
		}
		return (permille / 10) + "." + (permille % 10) + "%";
	}

	/**
	 * A structure-wide chance, written so that it never says more than it knows: only a certain item
	 * reads as 100%, one that merely rounds there reads as more than 99.9%, and one too unlikely to
	 * show a digit for reads as less than 0.1% rather than as nothing.
	 */
	static String formatProbability(double chance, boolean certain) {
		if (certain) {
			return "100%";
		}
		if (chance >= 0.9995D) {
			return ">99.9%";
		}
		if (chance > 0.0D && chance < 0.001D) {
			return "<0.1%";
		}
		return formatChance((int) Math.round(chance * ALWAYS));
	}

	/**
	 * A chance as the label under a slot, which has twenty pixels to say it in: whole percentages
	 * from ten up, and a tenth below that, where a decimal is what tells one rare item from another.
	 * Never 100% short of certain, and nothing at all for a certain item, whose slot is told apart by
	 * its colour and needs no label.
	 */
	static String formatGridChance(int permille) {
		if (permille >= ALWAYS) {
			return null;
		}
		if (permille >= 100) {
			return Math.min(99, (permille + 5) / 10) + "%";
		}
		return formatChance(permille);
	}

	/**
	 * How many come at once, as written in the corner of a slot the way the game writes a stack's
	 * size: nothing for one, the count where it is fixed, and the range where it fits, falling back
	 * to the larger end where it does not. The whole range is in the tooltip.
	 */
	static String formatGridCount(int minCount, int maxCount, ToIntFunction<String> width) {
		if (maxCount <= 1) {
			return null;
		}
		if (maxCount > MAX_GRID_COUNT) {
			return "99+";
		}
		if (minCount == maxCount) {
			return String.valueOf(maxCount);
		}
		final String range = minCount + "-" + maxCount;
		if (width.applyAsInt(range) <= COUNT_MAX_WIDTH) {
			return range;
		}
		return String.valueOf(maxCount);
	}

	/**
	 * An expected number of containers, to one decimal, with a point whatever the language, as every
	 * other number here is. One too small to show a tenth of is written as less than a tenth rather
	 * than as nought, which would say that no container holds the item at all.
	 */
	static String formatExpected(double expected) {
		if (expected > 0.0D && expected < 0.05D) {
			return "<0.1";
		}
		return String.format(Locale.ROOT, "%.1f", expected);
	}

	/**
	 * Which of a table's items a card shows when they do not all fit, given the chances of all of
	 * them from likeliest down: the indices of the ones kept, in order. The last place is left for
	 * the slot that counts the rest.
	 *
	 * <p>Cutting from the bottom alone would drop exactly the rare items a player reads a card for,
	 * so the last row is kept for the rarest ones where there are any, and the likeliest fill the
	 * rows above. Where the rare items begin early enough to fit anyway, the items are kept as they
	 * come, so that nothing is left out between them.
	 */
	static int[] cardSelection(int[] permilles, int columns, int rows) {
		final int count = permilles.length;
		final int capacity = columns * rows;
		if (count <= capacity) {
			final int[] all = new int[count];
			for (int i = 0; i < count; i++) {
				all[i] = i;
			}
			return all;
		}
		final int shown = Math.max(0, capacity - 1);
		int firstRare = count;
		for (int i = 0; i < count; i++) {
			if (permilles[i] < LootTier.UNCOMMON.minPermille) {
				firstRare = i;
				break;
			}
		}
		final int rare = columns <= 1 ? 0 : Math.min(Math.min(columns - 1, shown), count - firstRare);
		final int[] visible = new int[shown];
		final int head = firstRare >= shown - rare ? shown - rare : shown;
		for (int i = 0; i < head; i++) {
			visible[i] = i;
		}
		for (int i = head; i < shown; i++) {
			visible[i] = firstRare + (i - head);
		}
		return visible;
	}

}
