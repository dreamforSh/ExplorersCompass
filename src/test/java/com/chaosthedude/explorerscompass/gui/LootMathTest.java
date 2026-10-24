package com.chaosthedude.explorerscompass.gui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.ToIntFunction;

import org.junit.jupiter.api.Test;

class LootMathTest {

	/** The widths the game's font gives a digit, a percent sign and a point. */
	private static final ToIntFunction<String> GLYPHS = (text) -> {
		int width = 0;
		for (char c : text.toCharArray()) {
			width += c == '.' ? 2 : 6;
		}
		return width;
	};

	@Test
	void chancesFallIntoTheirTiers() {
		assertEquals(LootTier.GUARANTEED, LootMath.tier(1000));
		assertEquals(LootTier.COMMON, LootMath.tier(999));
		assertEquals(LootTier.COMMON, LootMath.tier(500));
		assertEquals(LootTier.UNCOMMON, LootMath.tier(499));
		assertEquals(LootTier.UNCOMMON, LootMath.tier(100));
		assertEquals(LootTier.RARE, LootMath.tier(99));
		assertEquals(LootTier.RARE, LootMath.tier(1));
		assertEquals(LootTier.RARE, LootMath.tier(0));
	}

	@Test
	void aSlotLabelIsWholePercentsFromTenAndTenthsBelow() {
		assertNull(LootMath.formatGridChance(1000));
		assertEquals("99%", LootMath.formatGridChance(999));
		assertEquals("99%", LootMath.formatGridChance(995));
		assertEquals("45%", LootMath.formatGridChance(450));
		assertEquals("10%", LootMath.formatGridChance(104));
		assertEquals("9.9%", LootMath.formatGridChance(99));
		assertEquals("5%", LootMath.formatGridChance(50));
		assertEquals("0.4%", LootMath.formatGridChance(4));
		assertEquals("0.1%", LootMath.formatGridChance(1));
		assertEquals("<1%", LootMath.formatGridChance(0));
	}

	@Test
	void aChanceThatRoundsToNothingIsNotWrittenAsNothing() {
		assertEquals("<0.1%", LootMath.formatChance(0));
		assertEquals("0.1%", LootMath.formatChance(1));
		assertEquals("100%", LootMath.formatChance(1000));
	}

	@Test
	void everySlotLabelFitsUnderTheSlot() {
		for (int permille = 0; permille < 1000; permille++) {
			final String label = LootMath.formatGridChance(permille);
			assertTrue(GLYPHS.applyAsInt(label) <= 20, label);
		}
	}

	@Test
	void aSlotCountIsWrittenOnlyWhereItIsFixed() {
		assertNull(LootMath.formatGridCount(1, 1));
		assertEquals("3", LootMath.formatGridCount(3, 3));
		assertNull(LootMath.formatGridCount(1, 3));
		assertNull(LootMath.formatGridCount(1, 10));
		assertEquals("64", LootMath.formatGridCount(64, 64));
		assertEquals("99", LootMath.formatGridCount(100, 100));
	}

	@Test
	void aStructureWideChanceNeverClaimsMoreThanItKnows() {
		assertEquals("100%", LootMath.formatProbability(1.0D, true));
		assertEquals(">99.9%", LootMath.formatProbability(0.99996D, false));
		assertEquals("<0.1%", LootMath.formatProbability(0.0004D, false));
		assertEquals("50%", LootMath.formatProbability(0.5D, false));
		assertEquals("12.3%", LootMath.formatProbability(0.1234D, false));
	}

	@Test
	void containersMissAnItemIndependently() {
		assertEquals(0.75D, LootMath.structureChance(new int[] {500}, new int[] {2}), 1e-9);
		assertEquals(0.68D, LootMath.structureChance(new int[] {500, 200}, new int[] {1, 2}), 1e-9);
	}

	@Test
	void aRareItemInManyContainersIsAlmostButNotQuiteCertain() {
		final int[] permilles = {1};
		final int[] containers = {10000};
		final double chance = LootMath.structureChance(permilles, containers);
		assertTrue(Double.isFinite(chance));
		assertTrue(chance > 0.9999D);
		assertFalse(LootMath.isCertain(permilles, containers));
		assertEquals(999, LootMath.structurePermille(chance, false));
	}

	@Test
	void aTableThatAlwaysGivesItMakesItCertain() {
		assertTrue(LootMath.isCertain(new int[] {300, 1000}, new int[] {4, 1}));
		assertFalse(LootMath.isCertain(new int[] {300, 1000}, new int[] {4, 0}));
		assertEquals(1000, LootMath.structurePermille(1.0D, true));
		assertEquals(1, LootMath.structurePermille(0.00001D, false));
	}

	@Test
	void theExpectedNumberOfContainersIsExact() {
		assertEquals(1.6D, LootMath.expected(new int[] {500, 200}, new int[] {2, 3}), 1e-9);
		assertEquals("1.6", LootMath.formatExpected(1.6D));
	}

	@Test
	void anExpectedNumberTooSmallForATenthIsNotWrittenAsNought() {
		// A one-in-a-hundred item in four containers: nought would say none of them holds it
		assertEquals("<0.1", LootMath.formatExpected(LootMath.expected(new int[] {10}, new int[] {4})));
		assertEquals("0.1", LootMath.formatExpected(0.05D));
		assertEquals("0.0", LootMath.formatExpected(0.0D));
	}

	@Test
	void anEnchantedItemIsWorthAStepMore() {
		assertEquals(2, LootMath.promotedWorth(0, true));
		assertEquals(2, LootMath.promotedWorth(1, true));
		assertEquals(3, LootMath.promotedWorth(2, true));
		assertEquals(3, LootMath.promotedWorth(3, true));
		assertEquals(1, LootMath.promotedWorth(1, false));
	}

	@Test
	void aCardThatFitsEverythingKeepsEverything() {
		assertArrayEquals(new int[] {0, 1, 2}, LootMath.cardSelection(new int[] {900, 500, 50}, 9, 2));
	}

	@Test
	void aCutCardKeepsItsLastRowForTheRareItems() {
		final int[] permilles = new int[64];
		for (int i = 0; i < permilles.length; i++) {
			permilles[i] = i < 54 ? 900 - i : 50;
		}
		final int[] visible = LootMath.cardSelection(permilles, 9, 2);
		assertEquals(17, visible.length);
		for (int i = 0; i < 9; i++) {
			assertEquals(i, visible[i]);
		}
		for (int i = 0; i < 8; i++) {
			assertEquals(54 + i, visible[9 + i]);
		}
	}

	@Test
	void rareItemsThatAlreadyFitAreKeptInOrder() {
		final int[] permilles = new int[30];
		for (int i = 0; i < permilles.length; i++) {
			permilles[i] = i < 3 ? 600 : 40;
		}
		final int[] visible = LootMath.cardSelection(permilles, 9, 2);
		assertEquals(17, visible.length);
		for (int i = 0; i < visible.length; i++) {
			assertEquals(i, visible[i]);
		}
	}

	@Test
	void aSingleColumnKeepsNoRowForRareItems() {
		final int[] permilles = {900, 800, 700, 600, 50, 40};
		assertArrayEquals(new int[] {0, 1, 2}, LootMath.cardSelection(permilles, 1, 4));
	}

}
