package com.chaosthedude.explorerscompass.gui;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Anti-aliased vector shapes for the compass screens: rounded panels, pills, circles and hairlines.
 *
 * <p>A plain fill is an axis-aligned rectangle snapped to the scaled interface, which at a GUI scale
 * of 2 or more makes every rounded corner visibly stepped. These shapes are built out of triangles
 * placed at fractional positions instead, so they resolve at the precision of the screen's own
 * pixels, and every edge carries a fringe one screen pixel wide that fades to transparent.
 *
 * <p>The triangles go through the graphics object's own buffers, under a render type that matches
 * the one plain fills use apart from not culling, so the transform in force applies, and they are
 * flushed straight after the way the graphics object flushes a fill of its own: whatever is drawn
 * next lands on top of them, as it would of any other fill. Corner points come from precomputed
 * angle tables, so drawing allocates nothing. Render thread only.
 */
@OnlyIn(Dist.CLIENT)
public final class UiShapes {

	/**
	 * The fill render type, drawn as triangles and without culling: the outlines below are walked in
	 * whichever direction is convenient, and a culled triangle wound the other way would vanish.
	 */
	private static final RenderType SHAPES = RenderType.create("explorerscompass_ui_shapes",
			DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.TRIANGLES, 1 << 16, false, false,
			RenderType.CompositeState.builder()
					.setShaderState(RenderStateShard.RENDERTYPE_GUI_SHADER)
					.setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
					.setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
					.setCullState(RenderStateShard.NO_CULL)
					.createCompositeState(false));

	private static final int MAX_SEGMENTS = 24;
	/** Per segment count n: the cosines and sines of the 4 * (n + 1) corner points, clockwise from the top left. */
	private static final float[][] COS = new float[MAX_SEGMENTS + 1][];
	private static final float[][] SIN = new float[MAX_SEGMENTS + 1][];

	// Scratch outlines, two rings of up to 4 * (MAX_SEGMENTS + 1) points each
	private static final float[] AX = new float[4 * (MAX_SEGMENTS + 1)];
	private static final float[] AY = new float[4 * (MAX_SEGMENTS + 1)];
	private static final float[] BX = new float[4 * (MAX_SEGMENTS + 1)];
	private static final float[] BY = new float[4 * (MAX_SEGMENTS + 1)];

	private UiShapes() {
	}

	/** Filled rounded rectangle; the radius is held to half the shorter side. */
	public static void roundRect(GuiGraphics guiGraphics, float x, float y, float w, float h, float r, int color) {
		roundRect(guiGraphics, x, y, w, h, r, color, color);
	}

	/** Filled rounded rectangle that fades from one colour along its top to another along its bottom. */
	public static void roundRect(GuiGraphics guiGraphics, float x, float y, float w, float h, float r, int topColor, int bottomColor) {
		if (w <= 0 || h <= 0 || (alpha(topColor) == 0 && alpha(bottomColor) == 0)) {
			return;
		}
		r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
		final float aa = pixel();
		final int n = segments(r);
		final int count = outline(x, y, x + w, y + h, r, -aa / 2, n, AX, AY);
		outline(x, y, x + w, y + h, r, aa / 2, n, BX, BY);
		final Builder b = Builder.begin(guiGraphics, y, h, topColor, bottomColor);
		final float cx = x + w / 2;
		final float cy = y + h / 2;
		for (int i = 0; i < count; i++) {
			final int j = i + 1 == count ? 0 : i + 1;
			b.tri(cx, cy, b.shade(cy), AX[i], AY[i], b.shade(AY[i]), AX[j], AY[j], b.shade(AY[j]));
		}
		for (int i = 0; i < count; i++) {
			final int j = i + 1 == count ? 0 : i + 1;
			final int ci = b.shade(AY[i]);
			final int cj = b.shade(AY[j]);
			b.quad(AX[i], AY[i], AX[j], AY[j], BX[j], BY[j], BX[i], BY[i], ci, cj, transparent(cj), transparent(ci));
		}
		b.end();
	}

	/** Fully rounded ends: a pill, or a circle where the shape is square. */
	public static void pill(GuiGraphics guiGraphics, float x, float y, float w, float h, int color) {
		roundRect(guiGraphics, x, y, w, h, Math.min(w, h) / 2, color);
	}

	public static void circle(GuiGraphics guiGraphics, float cx, float cy, float radius, int color) {
		roundRect(guiGraphics, cx - radius, cy - radius, radius * 2, radius * 2, radius, color);
	}

	/** A border drawn just inside the edge of a rounded rectangle of the same size and radius. */
	public static void roundRectStroke(GuiGraphics guiGraphics, float x, float y, float w, float h, float r, float strokeWidth, int color) {
		if (w <= 0 || h <= 0 || alpha(color) == 0) {
			return;
		}
		r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
		final float aa = pixel();
		final float sw = Math.max(strokeWidth, aa);
		final int n = segments(r);
		final float x1 = x + w;
		final float y1 = y + h;
		final int clear = transparent(color);
		final Builder b = Builder.begin(guiGraphics, y, h, color, color);
		// The inner fringe, the solid band and the outer fringe, each ring reusing the edge of the last
		final int count = outline(x, y, x1, y1, r, -sw - aa / 2, n, AX, AY);
		outline(x, y, x1, y1, r, -sw + aa / 2, n, BX, BY);
		ring(b, AX, AY, BX, BY, count, clear, color);
		if (sw > aa) {
			outline(x, y, x1, y1, r, -aa / 2, n, AX, AY);
			ring(b, BX, BY, AX, AY, count, color, color);
		} else {
			System.arraycopy(BX, 0, AX, 0, count);
			System.arraycopy(BY, 0, AY, 0, count);
		}
		outline(x, y, x1, y1, r, aa / 2, n, BX, BY);
		ring(b, AX, AY, BX, BY, count, color, clear);
		b.end();
	}

	/**
	 * A soft shadow under a floating rounded rectangle, a few rings fading out from its edge, which is
	 * what lifts a tooltip or a dropped-down panel off whatever it covers.
	 */
	public static void shadow(GuiGraphics guiGraphics, float x, float y, float w, float h, float r, float spread, int color) {
		if (alpha(color) == 0 || spread <= 0) {
			return;
		}
		final int n = segments(r + spread);
		final int clear = transparent(color);
		final Builder b = Builder.begin(guiGraphics, y, h, color, color);
		final int count = outline(x, y, x + w, y + h, r, -Math.min(r, 1.0F), n, AX, AY);
		outline(x, y, x + w, y + h, r, spread, n, BX, BY);
		ring(b, AX, AY, BX, BY, count, color, clear);
		b.end();
	}

	/** A crisp line one screen pixel thick, however far the interface is scaled up. */
	public static void hairline(GuiGraphics guiGraphics, float x0, float x1, float y, int color) {
		if (alpha(color) == 0 || x1 <= x0) {
			return;
		}
		final float t = pixel();
		final Builder b = Builder.begin(guiGraphics, y, t, color, color);
		b.quad(x0, y, x1, y, x1, y + t, x0, y + t, color, color, color, color);
		b.end();
	}

	/**
	 * A filled triangle with an anti-aliased edge, such as a compass needle. Each side is given a
	 * fringe one screen pixel wide pushed outwards along its own normal; where two fringes meet at a
	 * corner they leave a sliver no wider than that pixel, which is not to be seen.
	 */
	public static void triangle(GuiGraphics guiGraphics, float x0, float y0, float x1, float y1, float x2, float y2, int color) {
		if (alpha(color) == 0) {
			return;
		}
		// Wound one way round, so that every side's outward normal is on the same hand of it
		if ((x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0) < 0) {
			final float sx = x1;
			final float sy = y1;
			x1 = x2;
			y1 = y2;
			x2 = sx;
			y2 = sy;
		}
		final float aa = pixel();
		final int clear = transparent(color);
		final float top = Math.min(y0, Math.min(y1, y2));
		final Builder b = Builder.begin(guiGraphics, top, 1.0F, color, color);
		b.tri(x0, y0, color, x1, y1, color, x2, y2, color);
		fringe(b, x0, y0, x1, y1, aa, color, clear);
		fringe(b, x1, y1, x2, y2, aa, color, clear);
		fringe(b, x2, y2, x0, y0, aa, color, clear);
		b.end();
	}

	/** The fringe along one side of a triangle wound so that its outside lies to the left of the side. */
	private static void fringe(Builder b, float ax, float ay, float bx, float by, float aa, int color, int clear) {
		final float dx = bx - ax;
		final float dy = by - ay;
		final float length = (float) Math.sqrt(dx * dx + dy * dy);
		if (length < 1.0E-4F) {
			return;
		}
		final float nx = dy / length * aa;
		final float ny = -dx / length * aa;
		b.quad(ax, ay, bx, by, bx + nx, by + ny, ax + nx, ay + ny, color, color, clear, clear);
	}

	/** One screen pixel, in the units of the scaled interface. */
	public static float pixel() {
		return 1.0F / (float) Minecraft.getInstance().getWindow().getGuiScale();
	}

	/** The width of a border that stays about one screen pixel at a GUI scale of 2 and never vanishes. */
	public static float stroke() {
		return Math.max(pixel(), 0.5F);
	}

	private static int segments(float r) {
		final float screenRadius = r / pixel();
		return Math.max(1, Math.min(MAX_SEGMENTS, (int) Math.ceil(screenRadius * 0.5F)));
	}

	/**
	 * Writes the outline of a rounded rectangle, grown outwards by {@code d} or shrunk where it is
	 * negative, clockwise from the top left corner with {@code n + 1} points to a corner, and answers
	 * how many points that came to. The outlines of a rounded rectangle at an offset are concentric
	 * arcs, until an inward offset passes the radius and the corner turns sharp.
	 */
	private static int outline(float x0, float y0, float x1, float y1, float r, float d, int n, float[] xs, float[] ys) {
		float[] cos = COS[n];
		float[] sin = SIN[n];
		if (cos == null) {
			cos = new float[4 * (n + 1)];
			sin = new float[4 * (n + 1)];
			final double[] start = { Math.PI, Math.PI * 1.5, 0, Math.PI * 0.5 };
			int k = 0;
			for (int corner = 0; corner < 4; corner++) {
				for (int s = 0; s <= n; s++) {
					final double a = start[corner] + (Math.PI / 2) * s / n;
					cos[k] = (float) Math.cos(a);
					sin[k] = (float) Math.sin(a);
					k++;
				}
			}
			COS[n] = cos;
			SIN[n] = sin;
		}
		final float rr = Math.max(0, r + d);
		final float inset = Math.max(r, -d);
		final float left = x0 + inset;
		final float right = x1 - inset;
		final float top = y0 + inset;
		final float bottom = y1 - inset;
		final int perCorner = n + 1;
		for (int k = 0; k < 4 * perCorner; k++) {
			final int corner = k / perCorner;
			final float cx = corner == 0 || corner == 3 ? left : right;
			final float cy = corner < 2 ? top : bottom;
			xs[k] = cx + rr * cos[k];
			ys[k] = cy + rr * sin[k];
		}
		return 4 * perCorner;
	}

	private static void ring(Builder b, float[] ix, float[] iy, float[] ox, float[] oy, int count, int innerColor, int outerColor) {
		for (int i = 0; i < count; i++) {
			final int j = i + 1 == count ? 0 : i + 1;
			b.quad(ix[i], iy[i], ix[j], iy[j], ox[j], oy[j], ox[i], oy[i], innerColor, innerColor, outerColor, outerColor);
		}
	}

	private static int alpha(int color) {
		return color >>> 24;
	}

	private static int transparent(int color) {
		return color & 0x00FFFFFF;
	}

	/** Writes triangles into the graphics object's buffer for the shape render type, and flushes them when done. */
	private static final class Builder {

		private static final Builder INSTANCE = new Builder();

		private GuiGraphics guiGraphics;
		private VertexConsumer buffer;
		private Matrix4f matrix;
		private float top;
		private float height;
		private int topColor;
		private int bottomColor;

		static Builder begin(GuiGraphics guiGraphics, float top, float height, int topColor, int bottomColor) {
			final Builder b = INSTANCE;
			b.guiGraphics = guiGraphics;
			b.buffer = guiGraphics.bufferSource().getBuffer(SHAPES);
			b.matrix = guiGraphics.pose().last().pose();
			b.top = top;
			b.height = height;
			b.topColor = topColor;
			b.bottomColor = bottomColor;
			return b;
		}

		/** The colour of a point at the given height, for a shape that fades from top to bottom. */
		int shade(float y) {
			if (topColor == bottomColor || height <= 0) {
				return topColor;
			}
			return GuiTheme.lerpColor(topColor, bottomColor, (y - top) / height);
		}

		void tri(float x0, float y0, int c0, float x1, float y1, int c1, float x2, float y2, int c2) {
			buffer.addVertex(matrix, x0, y0, 0.0F).setColor(c0);
			buffer.addVertex(matrix, x1, y1, 0.0F).setColor(c1);
			buffer.addVertex(matrix, x2, y2, 0.0F).setColor(c2);
		}

		void quad(float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3, int c0, int c1, int c2, int c3) {
			tri(x0, y0, c0, x1, y1, c1, x2, y2, c2);
			tri(x0, y0, c0, x2, y2, c2, x3, y3, c3);
		}

		void end() {
			// What the graphics object does after a fill of its own, so that the shape is on the screen
			// in the order it was asked for rather than at the end of the frame
			guiGraphics.flush();
			guiGraphics = null;
			buffer = null;
			matrix = null;
		}

	}

}
