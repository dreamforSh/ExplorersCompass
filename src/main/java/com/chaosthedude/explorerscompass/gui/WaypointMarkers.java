package com.chaosthedude.explorerscompass.gui;

import com.chaosthedude.explorerscompass.client.WaypointMarkerStyle;
import com.chaosthedude.explorerscompass.util.RenderUtils;

import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Draws the shape a waypoint is marked with, in whichever style the player has chosen. The same
 * drawing serves the direction strip and the rows of the waypoints screen, so that a waypoint looks
 * the same wherever it is met.
 *
 * <p>Every shape is laid out against the top and bottom of the space it is given and centred on a
 * column, so that the strip and a row of the list, which are not the same height, both get a shape
 * that fits them.
 */
@OnlyIn(Dist.CLIENT)
public final class WaypointMarkers {

	/** How far the shapes reach either side of their column, which is what a row has to leave clear. */
	public static final int HALF_WIDTH = 3;

	private WaypointMarkers() {
	}

	/**
	 * Draws the marker with a shadow under it, for where it stands on nothing but the world.
	 *
	 * @param x      the column the marker is centred on
	 * @param top    the top of the space the marker may fill
	 * @param bottom the bottom of that space, exclusive
	 * @param color  alpha, red, green and blue
	 */
	public static void drawWithShadow(GuiGraphics guiGraphics, WaypointMarkerStyle style, int x, int top, int bottom, int color, int shadowColor) {
		draw(guiGraphics, style, x + 1, top + 1, bottom + 1, shadowColor);
		draw(guiGraphics, style, x, top, bottom, color);
	}

	public static void draw(GuiGraphics guiGraphics, WaypointMarkerStyle style, int x, int top, int bottom, int color) {
		switch (style) {
			case BOOKMARK:
				drawBookmark(guiGraphics, x, top, bottom, color);
				break;
			case FLAG:
				drawFlag(guiGraphics, x, top, bottom, color);
				break;
			case DIAMOND:
				drawDiamond(guiGraphics, x, top, bottom, color);
				break;
			default:
				drawPin(guiGraphics, x, top, bottom, color);
				break;
		}
	}

	/**
	 * A ribbon five wide hanging from the top edge, ending two short of the bottom in a notch cut up
	 * into it: the two lowest rows lose their middle and then their inner pixels, which is what makes
	 * the end read as the forked tail of a bookmark rather than as a bar.
	 */
	private static void drawBookmark(GuiGraphics guiGraphics, int x, int top, int bottom, int color) {
		final int tail = bottom - 2;
		RenderUtils.drawRect(guiGraphics, x - 2, top, x + 3, tail - 2, color);
		RenderUtils.drawRect(guiGraphics, x - 2, tail - 2, x, tail - 1, color);
		RenderUtils.drawRect(guiGraphics, x + 1, tail - 2, x + 3, tail - 1, color);
		RenderUtils.drawRect(guiGraphics, x - 2, tail - 1, x - 1, tail, color);
		RenderUtils.drawRect(guiGraphics, x + 2, tail - 1, x + 3, tail, color);
	}

	/** A head three pixels square on a stem down to the bottom edge. */
	private static void drawPin(GuiGraphics guiGraphics, int x, int top, int bottom, int color) {
		final int head = Math.max(top, bottom - 8);
		RenderUtils.drawRect(guiGraphics, x - 1, head, x + 2, head + 3, color);
		RenderUtils.drawRect(guiGraphics, x, head + 3, x + 1, bottom - 1, color);
	}

	/** A pole from near the top to the bottom edge, with a pennant flying to the right of its top. */
	private static void drawFlag(GuiGraphics guiGraphics, int x, int top, int bottom, int color) {
		final int pole = top + 1;
		RenderUtils.drawRect(guiGraphics, x, pole, x + 1, bottom - 1, color);
		RenderUtils.drawRect(guiGraphics, x + 1, pole, x + 5, pole + 1, color);
		RenderUtils.drawRect(guiGraphics, x + 1, pole + 1, x + 4, pole + 2, color);
		RenderUtils.drawRect(guiGraphics, x + 1, pole + 2, x + 3, pole + 3, color);
		RenderUtils.drawRect(guiGraphics, x + 1, pole + 3, x + 2, pole + 4, color);
	}

	/** A diamond five across, in the middle of the space. */
	private static void drawDiamond(GuiGraphics guiGraphics, int x, int top, int bottom, int color) {
		final int middle = (top + bottom) / 2;
		RenderUtils.drawRect(guiGraphics, x, middle - 2, x + 1, middle - 1, color);
		RenderUtils.drawRect(guiGraphics, x - 1, middle - 1, x + 2, middle, color);
		RenderUtils.drawRect(guiGraphics, x - 2, middle, x + 3, middle + 1, color);
		RenderUtils.drawRect(guiGraphics, x - 1, middle + 1, x + 2, middle + 2, color);
		RenderUtils.drawRect(guiGraphics, x, middle + 2, x + 1, middle + 3, color);
	}

}
