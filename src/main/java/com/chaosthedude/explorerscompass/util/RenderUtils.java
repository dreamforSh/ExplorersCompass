package com.chaosthedude.explorerscompass.util;

import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Matrix4f;

/**
 * The shapes the interface is drawn out of.
 *
 * <p>These used to write vertices straight to the tesselator and bind a shader themselves. They go
 * through the graphics object now: that is what applies the transform in force, so a panel drawn
 * inside anything that has moved the origin lands where it was asked to rather than at fixed screen
 * coordinates, and it batches with the rest of the interface instead of forcing a draw of its own
 * and leaving the shader colour poked for whoever draws next.
 */
@OnlyIn(Dist.CLIENT)
public class RenderUtils {

	private RenderUtils() {
	}

	/**
	 * The font, looked up when it is wanted rather than when this class is loaded. Capturing it in a
	 * static field means capturing whatever was in place before the first resource reload, which
	 * measures text with glyph widths that are then replaced.
	 */
	private static Font font() {
		return Minecraft.getInstance().font;
	}

	/**
	 * Draws how far along something is as a filled bar. The fill is rounded rather than truncated, so
	 * that a bar which is nearly full does not sit a pixel short of its own end.
	 */
	public static void drawProgressBar(GuiGraphics guiGraphics, int left, int top, int right, int bottom, float progress, int backgroundColor, int fillColor) {
		drawRect(guiGraphics, left, top, right, bottom, backgroundColor);
		final int filled = Math.round((right - left) * Math.min(Math.max(progress, 0.0F), 1.0F));
		if (filled > 0) {
			drawRect(guiGraphics, left, top, left + filled, bottom, fillColor);
		}
	}

	/** Fills a rectangle with a colour given as alpha, red, green and blue bytes. */
	public static void drawRect(GuiGraphics guiGraphics, int left, int top, int right, int bottom, int color) {
		guiGraphics.fill(Math.min(left, right), Math.min(top, bottom), Math.max(left, right), Math.max(top, bottom), color);
	}

	/**
	 * Fills a rectangle with a colour that fades from one side to the other.
	 *
	 * <p>This is the one shape still written out as vertices: the graphics object only offers a
	 * gradient that runs from top to bottom. The vertices go into its own buffer rather than into one
	 * of this class's, so the transform and the batching still apply.
	 */
	public static void drawHorizontalGradient(GuiGraphics guiGraphics, int left, int top, int right, int bottom, int leftColor, int rightColor) {
		final int minX = Math.min(left, right);
		final int maxX = Math.max(left, right);
		final int minY = Math.min(top, bottom);
		final int maxY = Math.max(top, bottom);

		final Matrix4f matrix = guiGraphics.pose().last().pose();
		final VertexConsumer buffer = guiGraphics.bufferSource().getBuffer(RenderType.gui());
		buffer.addVertex(matrix, minX, minY, 0.0F).setColor(leftColor);
		buffer.addVertex(matrix, minX, maxY, 0.0F).setColor(leftColor);
		buffer.addVertex(matrix, maxX, maxY, 0.0F).setColor(rightColor);
		buffer.addVertex(matrix, maxX, minY, 0.0F).setColor(rightColor);
		// What the graphics object does for itself after an unmanaged fill, so that these vertices are
		// put on the screen in the order they were asked for rather than at the end of the frame
		guiGraphics.flush();
	}

	/** Fills a rectangle with a colour that fades from top to bottom. */
	public static void drawVerticalGradient(GuiGraphics guiGraphics, int left, int top, int right, int bottom, int topColor, int bottomColor) {
		guiGraphics.fillGradient(Math.min(left, right), Math.min(top, bottom), Math.max(left, right), Math.max(top, bottom), topColor, bottomColor);
	}

	/** Draws a one pixel wide outline just outside the given rectangle. */
	public static void drawOutline(GuiGraphics guiGraphics, int left, int top, int right, int bottom, int color) {
		drawRect(guiGraphics, left - 1, top - 1, right + 1, top, color);
		drawRect(guiGraphics, left - 1, bottom, right + 1, bottom + 1, color);
		drawRect(guiGraphics, left - 1, top, left, bottom, color);
		drawRect(guiGraphics, right, top, right + 1, bottom, color);
	}

	/** Draws a one pixel wide outline along the inside edge of the given rectangle. */
	public static void drawInnerOutline(GuiGraphics guiGraphics, int left, int top, int right, int bottom, int color) {
		drawRect(guiGraphics, left, top, right, top + 1, color);
		drawRect(guiGraphics, left, bottom - 1, right, bottom, color);
		drawRect(guiGraphics, left, top + 1, left + 1, bottom - 1, color);
		drawRect(guiGraphics, right - 1, top + 1, right, bottom - 1, color);
	}

	/**
	 * Draws the raised surface the interface is built out of: a panel that fades from top to bottom
	 * behind a thin border, so that groups of controls read as belonging together.
	 */
	public static void drawPanel(GuiGraphics guiGraphics, int left, int top, int right, int bottom, int topColor, int bottomColor, int borderColor) {
		drawVerticalGradient(guiGraphics, left, top, right, bottom, topColor, bottomColor);
		drawInnerOutline(guiGraphics, left, top, right, bottom, borderColor);
	}

	/**
	 * Draws a label on a filled pill, and answers how wide it came out, so that several of them can
	 * be laid out in a row.
	 */
	public static int drawChip(GuiGraphics guiGraphics, String text, int x, int y, int backgroundColor, int textColor) {
		final Font font = font();
		final int chipWidth = font.width(text) + 8;
		drawRect(guiGraphics, x, y, x + chipWidth, y + 12, backgroundColor);
		guiGraphics.drawString(font, text, x + 4, y + 2, textColor, false);
		return chipWidth;
	}

	/** Shortens a string to fit the given width, marking that it was shortened. */
	public static String trimToWidth(String text, int width) {
		final Font font = font();
		if (font.width(text) <= width) {
			return text;
		}
		return font.plainSubstrByWidth(text, Math.max(0, width - font.width("…"))) + "…";
	}

}
