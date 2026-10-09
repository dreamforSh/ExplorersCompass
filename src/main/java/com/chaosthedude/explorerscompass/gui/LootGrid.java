package com.chaosthedude.explorerscompass.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.chaosthedude.explorerscompass.util.RenderUtils;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Where a set of drops is laid out as tiles, and how the tiles are drawn once they are. The card
 * beside a badge and the overview of a whole structure lay their tiles out differently, for
 * different reasons, but draw and hit-test them the same way, so both live here.
 *
 * <p>A card flows its tiles from likeliest down without a break, so that a short table reads as a
 * single run and a long one as a few rows, with the tier told by the colour of each slot. The
 * overview has room to scroll, so it groups its tiles by tier instead, each group starting a row of
 * its own with a ribbon of the tier's colour down its side, so that a long table can be read by
 * tier without reading a number.
 *
 * <p>Laying out works from chances alone and touches nothing of the game, so that how a grid is cut
 * and where each tile falls can be checked by a test on its own. Only drawing needs the game.
 */
@OnlyIn(Dist.CLIENT)
final class LootGrid {

	/**
	 * One drop as a grid draws it: the stack, the chance its label and tint come from, and how many
	 * come at once, together with where it came from, so that pointing at its tile can say more. The
	 * table and the item in it are -1 where the drop stands for every table that gives it.
	 */
	record Entry(ItemStack stack, int permille, int minCount, int maxCount, int variant, int table, int item) {
	}

	/**
	 * Where one tile falls, relative to the top left of its grid, and how tall the row it sits in is.
	 * The entry is the index of the chance it was laid out from, or -1 for the tile that counts the
	 * hidden ones, in which case it carries how many that is.
	 */
	record Tile(int x, int y, int height, int entry, int hidden) {
	}

	/** The rows a tier's group takes up, from the top of its first to the bottom of its last, exclusive. */
	record Band(LootTier tier, int top, int bottom) {
	}

	/** The tiles of a grid, the groups they fall into where there are any, how much room it all takes, and how many were left out. */
	record Layout(List<Tile> tiles, List<Band> bands, int width, int height, int hidden) {
	}

	private LootGrid() {
	}

	/**
	 * Lays chances out as a card does: in the order given, row by row, at most the given number of
	 * rows, or as many as it takes where that is not above nothing. Where they do not all fit, the
	 * card keeps the ones {@link LootMath#cardSelection} keeps and gives the last place to a tile
	 * counting the rest.
	 *
	 * <p>A row with no label to show, which is a row of certain items alone, is drawn short, so that
	 * a container of fixed contents takes no more room than it needs.
	 */
	static Layout flow(int[] permilles, int columns, int maxRows) {
		final int cols = Math.max(1, columns);
		final int[] visible = maxRows > 0 ? LootMath.cardSelection(permilles, cols, maxRows) : identity(permilles.length);
		final int hidden = permilles.length - visible.length;
		final int count = visible.length + (hidden > 0 ? 1 : 0);
		final List<Tile> tiles = new ArrayList<Tile>(count);
		int y = 0;
		for (int rowStart = 0; rowStart < count; rowStart += cols) {
			final int rowEnd = Math.min(count, rowStart + cols);
			boolean labelled = false;
			for (int k = rowStart; k < rowEnd; k++) {
				if (k < visible.length && LootMath.formatGridChance(permilles[visible[k]]) != null) {
					labelled = true;
					break;
				}
			}
			final int height = labelled ? LootSlots.ROW_LABELLED : LootSlots.ROW_PLAIN;
			for (int k = rowStart; k < rowEnd; k++) {
				final int x = (k - rowStart) * LootSlots.TILE_W;
				if (k < visible.length) {
					tiles.add(new Tile(x, y, height, visible[k], 0));
				} else {
					tiles.add(new Tile(x, y, height, -1, hidden));
				}
			}
			y += height;
		}
		return new Layout(Collections.unmodifiableList(tiles), Collections.<Band>emptyList(), cols * LootSlots.TILE_W, y, hidden);
	}

	/**
	 * Lays chances out as the overview does: grouped by tier from certain down, each group in the
	 * order given and starting a row of its own, a little apart from the one before. Where a tier is
	 * picked out, only its group is laid out, and the rest are counted as hidden.
	 *
	 * <p>The certain group is drawn in short rows, since none of its tiles has a label, and every
	 * other group in rows tall enough for one. Tiles start to the right of the ribbon that runs down
	 * each group's side.
	 */
	static Layout grouped(int[] permilles, int columns, LootTier solo) {
		final int cols = Math.max(1, columns);
		final List<Tile> tiles = new ArrayList<Tile>(permilles.length);
		final List<Band> bands = new ArrayList<Band>();
		int hidden = 0;
		int y = 0;
		for (LootTier tier : LootTier.values()) {
			int inTier = 0;
			for (int permille : permilles) {
				if (LootTier.of(permille) == tier) {
					inTier++;
				}
			}
			if (inTier == 0) {
				continue;
			}
			if (solo != null && tier != solo) {
				hidden += inTier;
				continue;
			}
			if (!bands.isEmpty()) {
				y += LootSlots.BAND_GAP;
			}
			final int height = tier == LootTier.GUARANTEED ? LootSlots.ROW_PLAIN : LootSlots.ROW_LABELLED;
			final int top = y;
			int k = 0;
			for (int i = 0; i < permilles.length; i++) {
				if (LootTier.of(permilles[i]) != tier) {
					continue;
				}
				tiles.add(new Tile(LootSlots.RIBBON + (k % cols) * LootSlots.TILE_W, top + (k / cols) * height, height, i, 0));
				k++;
			}
			y = top + ((inTier + cols - 1) / cols) * height;
			bands.add(new Band(tier, top, y));
		}
		return new Layout(Collections.unmodifiableList(tiles), Collections.unmodifiableList(bands), LootSlots.RIBBON + cols * LootSlots.TILE_W, y, hidden);
	}

	/**
	 * The tile under the given point, for a grid drawn with its top left corner at the given origin,
	 * or null where there is none. A tile answers for the whole of its place in the row, label and
	 * margins included, so that the pointer does not fall through the gaps between slots.
	 */
	static Tile tileAt(Layout layout, int originX, int originY, double mouseX, double mouseY) {
		for (Tile tile : layout.tiles()) {
			final int left = originX + tile.x();
			final int top = originY + tile.y();
			if (mouseX >= left && mouseX < left + LootSlots.TILE_W && mouseY >= top && mouseY < top + tile.height()) {
				return tile;
			}
		}
		return null;
	}

	/**
	 * Draws a laid out grid with its top left corner at the given origin: each group's ribbon, then
	 * its tiles, the hovered one lit. Tiles wholly outside the given span of rows are skipped, so that
	 * a long list scrolled through a window draws only what shows; the window itself still has to be
	 * cut to by whoever draws it, for the tiles half in it.
	 */
	static void render(GuiGraphics guiGraphics, Font font, Layout layout, List<Entry> entries, int originX, int originY, Tile hovered, int clipTop, int clipBottom) {
		for (Band band : layout.bands()) {
			final int top = originY + band.top();
			final int bottom = originY + band.bottom();
			if (bottom <= clipTop || top >= clipBottom) {
				continue;
			}
			RenderUtils.drawRect(guiGraphics, originX, top, originX + 2, bottom, band.tier().ribbonColour());
		}
		for (Tile tile : layout.tiles()) {
			final int x = originX + tile.x();
			final int y = originY + tile.y();
			if (y + tile.height() <= clipTop || y >= clipBottom) {
				continue;
			}
			final boolean lit = tile.equals(hovered);
			if (tile.entry() < 0) {
				LootSlots.drawMoreTile(guiGraphics, font, x, y, tile.hidden(), lit);
			} else {
				final Entry entry = entries.get(tile.entry());
				LootSlots.drawTile(guiGraphics, font, x, y, entry.stack(), entry.permille(), entry.minCount(), entry.maxCount(), lit);
			}
		}
	}

	private static int[] identity(int count) {
		final int[] all = new int[count];
		for (int i = 0; i < count; i++) {
			all[i] = i;
		}
		return all;
	}

}
