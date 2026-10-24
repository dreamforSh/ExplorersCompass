package com.chaosthedude.explorerscompass.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class LootGridTest {

	@Test
	void theOverviewGroupsTilesByTierUnderAHeaderEach() {
		// Two certain, three common and one rare, given out of order
		final int[] permilles = { 600, 1000, 50, 700, 1000, 550 };
		final LootGrid.Layout layout = LootGrid.grouped(permilles, 2, null);

		assertEquals(3, layout.bands().size());
		final LootGrid.Band guaranteed = layout.bands().get(0);
		final LootGrid.Band common = layout.bands().get(1);
		final LootGrid.Band rare = layout.bands().get(2);
		assertSame(LootTier.GUARANTEED, guaranteed.tier());
		assertSame(LootTier.COMMON, common.tier());
		assertSame(LootTier.RARE, rare.tier());

		assertEquals(2, guaranteed.count());
		assertEquals(3, common.count());
		assertEquals(1, rare.count());

		// Each group's header comes first, then its rows, then a little gap before the next header
		assertEquals(0, guaranteed.top());
		assertEquals(LootSlots.GROUP_HEAD + LootSlots.ROW_PLAIN, guaranteed.bottom());
		assertEquals(guaranteed.bottom() + LootSlots.BAND_GAP, common.top());
		assertEquals(common.top() + LootSlots.GROUP_HEAD + 2 * LootSlots.ROW_LABELLED, common.bottom());
		assertEquals(common.bottom() + LootSlots.BAND_GAP, rare.top());
		assertEquals(rare.top() + LootSlots.GROUP_HEAD + LootSlots.ROW_LABELLED, rare.bottom());
		assertEquals(rare.bottom(), layout.height());
		assertEquals(34, guaranteed.bottom());
		assertEquals(107, rare.top());
		assertEquals(149, layout.height());
		// As wide as its columns, with nothing down the side
		assertEquals(2 * LootSlots.TILE_W, layout.width());
		assertEquals(48, layout.width());
		assertEquals(0, layout.hidden());

		final List<LootGrid.Tile> tiles = layout.tiles();
		assertEquals(permilles.length, tiles.size());
		// Each group keeps the order it was given in
		final int[] order = { 1, 4, 0, 3, 5, 2 };
		for (int i = 0; i < order.length; i++) {
			assertEquals(order[i], tiles.get(i).entry());
		}
		assertEquals(0, tiles.get(0).x());
		assertEquals(LootSlots.TILE_W, tiles.get(1).x());
		assertEquals(guaranteed.top() + LootSlots.GROUP_HEAD, tiles.get(0).y());
		assertEquals(LootSlots.ROW_PLAIN, tiles.get(0).height());
		assertEquals(common.top() + LootSlots.GROUP_HEAD, tiles.get(2).y());
		assertEquals(LootSlots.ROW_LABELLED, tiles.get(2).height());
		// The third common tile wraps to a row of its own
		assertEquals(0, tiles.get(4).x());
		assertEquals(common.top() + LootSlots.GROUP_HEAD + LootSlots.ROW_LABELLED, tiles.get(4).y());
		assertEquals(rare.top() + LootSlots.GROUP_HEAD, tiles.get(5).y());
	}

	@Test
	void pickingOutATierHidesTheRest() {
		final int[] permilles = { 1000, 600, 300, 50, 20 };
		final LootGrid.Layout layout = LootGrid.grouped(permilles, 4, LootTier.RARE);

		assertEquals(1, layout.bands().size());
		assertSame(LootTier.RARE, layout.bands().get(0).tier());
		assertEquals(0, layout.bands().get(0).top());
		assertEquals(2, layout.bands().get(0).count());
		assertEquals(LootSlots.GROUP_HEAD + LootSlots.ROW_LABELLED, layout.height());
		assertEquals(3, layout.hidden());
		assertEquals(2, layout.tiles().size());
		assertEquals(3, layout.tiles().get(0).entry());
		assertEquals(4, layout.tiles().get(1).entry());
	}

	@Test
	void aTierWithNothingInItLaysOutNothing() {
		final LootGrid.Layout layout = LootGrid.grouped(new int[] { 1000, 600 }, 4, LootTier.RARE);

		assertTrue(layout.tiles().isEmpty());
		assertTrue(layout.bands().isEmpty());
		assertEquals(0, layout.height());
		assertEquals(2, layout.hidden());
	}

	@Test
	void aCardOfCertainItemsIsDrawnInShortRows() {
		final LootGrid.Layout layout = LootGrid.flow(new int[] { 1000, 1000, 1000, 1000 }, 3, 2);

		assertEquals(4, layout.tiles().size());
		for (LootGrid.Tile tile : layout.tiles()) {
			assertEquals(LootSlots.ROW_PLAIN, tile.height());
		}
		assertEquals(2 * LootSlots.ROW_PLAIN, layout.height());
		assertEquals(3 * LootSlots.TILE_W, layout.width());
		assertTrue(layout.bands().isEmpty());
		assertEquals(0, layout.hidden());
	}

	@Test
	void aCardFlowsWithoutBreakingBetweenTiers() {
		final LootGrid.Layout layout = LootGrid.flow(new int[] { 1000, 800, 300, 50 }, 3, 0);

		assertEquals(4, layout.tiles().size());
		assertEquals(0, layout.tiles().get(0).x());
		assertEquals(2 * LootSlots.TILE_W, layout.tiles().get(2).x());
		// A row holding any label is tall enough for it, certain tiles and all
		assertEquals(LootSlots.ROW_LABELLED, layout.tiles().get(0).height());
		assertEquals(0, layout.tiles().get(3).x());
		assertEquals(LootSlots.ROW_LABELLED, layout.tiles().get(3).y());
		assertEquals(2 * LootSlots.ROW_LABELLED, layout.height());
	}

	@Test
	void aCutCardCountsTheRestInItsLastPlace() {
		final int[] permilles = new int[20];
		for (int i = 0; i < permilles.length; i++) {
			permilles[i] = 900 - i * 10;
		}
		final LootGrid.Layout layout = LootGrid.flow(permilles, 3, 2);

		assertEquals(6, layout.tiles().size());
		assertEquals(15, layout.hidden());
		final LootGrid.Tile last = layout.tiles().get(5);
		assertEquals(-1, last.entry());
		assertEquals(15, last.hidden());
		assertEquals(2 * LootSlots.TILE_W, last.x());
		assertEquals(LootSlots.ROW_LABELLED, last.y());
		for (int i = 0; i < 5; i++) {
			assertEquals(i, layout.tiles().get(i).entry());
		}
	}

	@Test
	void aRowOfCertainItemsAndTheCountOfTheRestIsShort() {
		final LootGrid.Layout layout = LootGrid.flow(new int[] { 1000, 1000, 1000, 1000, 1000 }, 2, 2);

		assertEquals(4, layout.tiles().size());
		assertEquals(2, layout.hidden());
		assertEquals(-1, layout.tiles().get(3).entry());
		assertEquals(LootSlots.ROW_PLAIN, layout.tiles().get(3).height());
		assertEquals(2 * LootSlots.ROW_PLAIN, layout.height());
	}

	@Test
	void aTileAnswersForItsWholePlaceAndNoMore() {
		final LootGrid.Layout layout = LootGrid.flow(new int[] { 800, 400 }, 2, 0);
		final int originX = 100;
		final int originY = 50;

		assertEquals(0, LootGrid.tileAt(layout, originX, originY, 100.0D, 50.0D).entry());
		assertEquals(0, LootGrid.tileAt(layout, originX, originY, 123.9D, 78.9D).entry());
		// The right and bottom edges belong to what comes after
		assertEquals(1, LootGrid.tileAt(layout, originX, originY, 124.0D, 60.0D).entry());
		assertNull(LootGrid.tileAt(layout, originX, originY, 148.0D, 60.0D));
		assertNull(LootGrid.tileAt(layout, originX, originY, 110.0D, 79.0D));
		assertNull(LootGrid.tileAt(layout, originX, originY, 99.9D, 60.0D));
		assertNull(LootGrid.tileAt(layout, originX, originY, 110.0D, 49.9D));
	}

}
