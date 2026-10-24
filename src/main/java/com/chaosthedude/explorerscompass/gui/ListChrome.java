package com.chaosthedude.explorerscompass.gui;

import java.util.function.IntUnaryOperator;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * What every list on the compass screens draws around its rows: the band under the row being
 * pointed at, which fades in and out rather than snapping from row to row, the band under the row
 * that is picked, which slides to whichever row is picked next, the rules between rows, and the
 * scrollbar. Each list keeps one of these, since the fades and the slide are remembered from one
 * frame to the next.
 */
@OnlyIn(Dist.CLIENT)
final class ListChrome {

	private static final Object SELECTION = new Object();
	private static final Object SELECTION_ALPHA = new Object();
	/** A selection that moves further than this many rows at once jumps there instead of sliding past everything between. */
	private static final int MAX_SLIDE_ROWS = 4;

	private final UiAnim anim = new UiAnim();
	private int lastSelected = -1;

	/** Called once at the start of every frame the rows are drawn in. */
	void beginFrame() {
		anim.frame();
	}

	/** Forgets every fade and the selection's place, for when the rows have been replaced. */
	void reset() {
		anim.clear();
		lastSelected = -1;
	}

	/**
	 * Draws the band under the picked row, sliding toward it from wherever it stood last frame. Drawn
	 * before any of the rows, so that it passes under them.
	 *
	 * @param selected which row is picked, or -1 for none
	 * @param rowTop   where the given row's content starts, as the list lays it out this frame
	 */
	void drawSelection(GuiGraphics guiGraphics, int selected, IntUnaryOperator rowTop, int itemHeight, int left, int right) {
		if (selected < 0) {
			anim.approach(SELECTION_ALPHA, 0.0F, UiAnim.HOVER_SPEED);
			lastSelected = -1;
			return;
		}
		if (lastSelected < 0 || Math.abs(selected - lastSelected) > MAX_SLIDE_ROWS) {
			anim.snap(SELECTION, selected);
		}
		lastSelected = selected;
		final float index = anim.approach(SELECTION, selected, UiAnim.SLIDE_SPEED);
		final float alpha = anim.approach(SELECTION_ALPHA, 1.0F, UiAnim.HOVER_SPEED);
		// Measured from the first row rather than from the picked one, so that a band between two rows
		// stands where the list would put a row that far down
		final float top = rowTop.applyAsInt(0) + index * itemHeight - 2;
		GuiTheme.drawRowBand(guiGraphics, left, top + 1, right, top + itemHeight - 1, 0.0F, alpha, false);
	}

	/**
	 * Draws the band under a row that is pointed at or picked out with Ctrl-click, fading the pointer's
	 * highlight in and out.
	 */
	void drawRowBand(GuiGraphics guiGraphics, Object row, int left, int bandTop, int right, int bandBottom, boolean hovered, boolean multiSelected) {
		final float hover = anim.hover(row, hovered);
		if (hover > 0.01F || multiSelected) {
			GuiTheme.drawRowBand(guiGraphics, left, bandTop + 1, right, bandBottom - 1, hover, 0.0F, multiSelected);
		}
	}

	/** A rule between two rows, brighter where it closes off the rows held at the top of the list. */
	static void drawSeparator(GuiGraphics guiGraphics, int left, int right, int y, boolean section) {
		UiShapes.hairline(guiGraphics, left + 4, right - 4, y - UiShapes.pixel(), section ? GuiTheme.ROW_SECTION_SEPARATOR : GuiTheme.ROW_SEPARATOR);
	}

	/**
	 * The scrollbar of a list, standing in the strip along its right edge, lit while the pointer is
	 * over it.
	 *
	 * @param maxScroll   how far the list scrolls in all
	 * @param maxPosition how tall the rows stand together
	 */
	static void drawScrollbar(GuiGraphics guiGraphics, int x, int top, int bottom, int width, double scroll, int maxScroll, int maxPosition, int mouseX, int mouseY) {
		if (maxScroll <= 0) {
			return;
		}
		final int height = bottom - top;
		final int thumbHeight = Mth.clamp((int) ((float) (height * height) / (float) Math.max(1, maxPosition)), 32, Math.max(32, height - 8));
		final float thumbTop = top + (float) (Mth.clamp(scroll, 0, maxScroll) * (height - thumbHeight) / maxScroll);
		final boolean hovered = mouseX >= x - 2 && mouseX < x + width + 2 && mouseY >= top && mouseY < bottom;
		GuiTheme.drawScrollbar(guiGraphics, x + 1, top + 1, bottom - 1, width - 2, thumbTop + 1, thumbHeight - 2, hovered);
	}

}
