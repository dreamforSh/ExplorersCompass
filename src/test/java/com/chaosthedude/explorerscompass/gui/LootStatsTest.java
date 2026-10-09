package com.chaosthedude.explorerscompass.gui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class LootStatsTest {

	private static final int BREAD = 10;
	private static final int DIAMOND = 20;
	private static final int POTION = 30;
	private static final int BOOK = 40;

	/** Tables built one at a time, plain unless told otherwise: one of each item, no potion, no glint. */
	private static final class Tables {

		private final List<String> ids = new ArrayList<String>();
		private final List<int[]> items = new ArrayList<int[]>();
		private final List<int[]> permilles = new ArrayList<int[]>();
		private final List<int[]> minCounts = new ArrayList<int[]>();
		private final List<int[]> maxCounts = new ArrayList<int[]>();
		private final List<int[]> potions = new ArrayList<int[]>();
		private final List<boolean[]> enchanted = new ArrayList<boolean[]>();
		private final List<Integer> markers = new ArrayList<Integer>();

		/** Adds a table named by the given number of containers, and returns its index. */
		int add(String id, int containers, int[] itemIds, int[] chances) {
			final int table = ids.size();
			ids.add(id);
			items.add(itemIds);
			permilles.add(chances);
			final int[] ones = new int[itemIds.length];
			final int[] noPotions = new int[itemIds.length];
			for (int i = 0; i < itemIds.length; i++) {
				ones[i] = 1;
				noPotions[i] = -1;
			}
			minCounts.add(ones);
			maxCounts.add(ones.clone());
			potions.add(noPotions);
			enchanted.add(new boolean[itemIds.length]);
			for (int i = 0; i < containers; i++) {
				markers.add(table);
			}
			return table;
		}

		LootStats compute() {
			final int[] markerTables = new int[markers.size()];
			for (int i = 0; i < markerTables.length; i++) {
				markerTables[i] = markers.get(i);
			}
			return LootStats.compute(new LootStats.Input(markerTables, ids.toArray(new String[0]), items.toArray(new int[0][]), permilles.toArray(new int[0][]), minCounts.toArray(new int[0][]), maxCounts.toArray(new int[0][]), potions.toArray(new int[0][]), enchanted.toArray(new boolean[0][])));
		}

	}

	@Test
	void anItemInTwoTablesIsOneVariantFoundInBoth() {
		final Tables tables = new Tables();
		tables.add("minecraft:chests/a", 2, new int[] {DIAMOND}, new int[] {500});
		tables.add("minecraft:chests/b", 3, new int[] {DIAMOND}, new int[] {200});
		final LootStats stats = tables.compute();
		assertEquals(1, stats.variants().size());
		final LootStats.Variant diamond = stats.variant(0);
		assertEquals(2, diamond.tables().length);
		assertEquals(5, diamond.containersWith());
		assertEquals(stats.variantOf(0, 0), stats.variantOf(1, 0));
		assertEquals(1.6D, diamond.expected(), 1e-9);
	}

	@Test
	void aDifferentPotionOrGlintIsADifferentVariant() {
		final Tables tables = new Tables();
		final int table = tables.add("minecraft:chests/a", 1, new int[] {POTION, POTION, BOOK, BOOK}, new int[] {500, 500, 500, 500});
		assertEquals(2, tables.compute().variants().size());
		tables.potions.get(table)[1] = 5;
		tables.enchanted.get(table)[3] = true;
		final LootStats stats = tables.compute();
		assertEquals(4, stats.variants().size());
		assertNotEquals(stats.variantOf(table, 0), stats.variantOf(table, 1));
		assertNotEquals(stats.variantOf(table, 2), stats.variantOf(table, 3));
		assertTrue(stats.variant(stats.variantOf(table, 3)).enchanted());
		assertEquals(5, stats.variant(stats.variantOf(table, 1)).potion());
	}

	@Test
	void anItemATableNamesTwiceIsMergedAsTwoRolls() {
		final Tables tables = new Tables();
		final int table = tables.add("minecraft:chests/a", 1, new int[] {DIAMOND, DIAMOND}, new int[] {500, 500});
		tables.minCounts.get(table)[1] = 2;
		tables.maxCounts.get(table)[1] = 4;
		final LootStats stats = tables.compute();
		assertEquals(1, stats.variants().size());
		assertEquals(stats.variantOf(table, 0), stats.variantOf(table, 1));
		final LootStats.Variant diamond = stats.variant(0);
		assertArrayEquals(new int[] {750}, diamond.permilles());
		assertEquals(1, diamond.minCount());
		assertEquals(4, diamond.maxCount());
	}

	@Test
	void placedItemsAreCertain() {
		final Tables tables = new Tables();
		tables.add("", 1, new int[] {BREAD}, new int[] {1000});
		final LootStats.Variant bread = tables.compute().variant(0);
		assertTrue(bread.certain());
		assertEquals(1000, bread.structurePermille());
		assertEquals(1.0D, bread.structureChance(), 0.0D);
	}

	@Test
	void aTableThatCouldNotBeReadIsCountedAndGivesNothing() {
		final Tables tables = new Tables();
		tables.add("minecraft:chests/unknown", 2, new int[0], new int[0]);
		tables.add("", 1, new int[0], new int[0]);
		final LootStats stats = tables.compute();
		assertEquals(1, stats.unresolvedTables());
		assertTrue(stats.variants().isEmpty());
	}

	@Test
	void variantsAreOrderedByStructureChanceThenBestChanceThenKey() {
		final Tables tables = new Tables();
		// The potion is likeliest, bread in four containers overtakes what one container gives more
		// often, and the diamond and the book tie on both chances
		tables.add("minecraft:chests/a", 1, new int[] {BOOK, DIAMOND, POTION}, new int[] {300, 300, 900});
		tables.add("minecraft:chests/b", 4, new int[] {BREAD}, new int[] {100});
		final LootStats stats = tables.compute();
		final List<LootStats.Variant> variants = stats.variants();
		assertEquals(POTION, variants.get(0).itemId());
		// 1 - 0.9^4 = 0.3439, above the diamond's and the book's 0.3
		assertEquals(BREAD, variants.get(1).itemId());
		assertEquals(DIAMOND, variants.get(2).itemId());
		assertEquals(BOOK, variants.get(3).itemId());
		for (int i = 0; i < variants.size(); i++) {
			assertEquals(i, variants.get(i).index());
		}
	}

	@Test
	void aTieOnStructureChanceGoesToTheBetterSingleContainer() {
		final Tables tables = new Tables();
		// 1 - 0.5^2 = 0.75 across two containers, against 0.75 in a single one
		tables.add("minecraft:chests/a", 2, new int[] {BREAD}, new int[] {500});
		tables.add("minecraft:chests/b", 1, new int[] {DIAMOND}, new int[] {750});
		final List<LootStats.Variant> variants = tables.compute().variants();
		assertEquals(DIAMOND, variants.get(0).itemId());
		assertEquals(BREAD, variants.get(1).itemId());
	}

	@Test
	void theBestTableIsTheOneMoreContainersNameWhereTheyTie() {
		final Tables tables = new Tables();
		tables.add("minecraft:chests/a", 1, new int[] {DIAMOND}, new int[] {400});
		final int busier = tables.add("minecraft:chests/b", 3, new int[] {DIAMOND}, new int[] {400});
		final LootStats.Variant diamond = tables.compute().variant(0);
		assertEquals(busier, diamond.bestTable());
		assertEquals(400, diamond.bestPermille());
		assertEquals(busier, diamond.tables()[0]);
	}

	@Test
	void containersAreCountedPerTable() {
		final Tables tables = new Tables();
		tables.add("minecraft:chests/a", 2, new int[] {BREAD}, new int[] {500});
		tables.add("minecraft:chests/b", 0, new int[] {DIAMOND}, new int[] {500});
		tables.add("minecraft:chests/c", 3, new int[] {BOOK}, new int[] {500});
		final LootStats stats = tables.compute();
		assertEquals(3, stats.tableCount());
		assertEquals(2, stats.containers(0));
		assertEquals(0, stats.containers(1));
		assertEquals(3, stats.containers(2));
		assertEquals(5, stats.totalContainers());
		assertEquals(0, stats.firstMarker(0));
		assertEquals(-1, stats.firstMarker(1));
		assertEquals(2, stats.firstMarker(2));
		assertTrue(stats.sourceTables(stats.variantOf(2, 0)).get(2));
	}

}
