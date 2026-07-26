package com.chaosthedude.explorerscompass.util;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class RenderUtils {

	private static final Minecraft mc = Minecraft.getInstance();
	private static final Font font = mc.font;

	/**
	 * Draws how far along something is as a filled bar. The fill is rounded rather than truncated, so
	 * that a bar which is nearly full does not sit a pixel short of its own end.
	 */
	public static void drawProgressBar(int left, int top, int right, int bottom, float progress, int backgroundColor, int fillColor) {
		drawRect(left, top, right, bottom, backgroundColor);
		final int filled = Math.round((right - left) * Math.min(Math.max(progress, 0.0F), 1.0F));
		if (filled > 0) {
			drawRect(left, top, left + filled, bottom, fillColor);
		}
	}

	/**
	 * Fills a rectangle with a colour given as alpha, red, green and blue bytes.
	 *
	 * <p>The colour travels with the vertices and the shader is named outright, rather than being
	 * left to the colour modulator and to whatever shader the previous draw happened to bind: the
	 * modulator survives between draws, so a rectangle set up that way comes out in an earlier
	 * colour instead of its own.
	 */
	public static void drawRect(int left, int top, int right, int bottom, int color) {
		final int minX = Math.min(left, right);
		final int maxX = Math.max(left, right);
		final int minY = Math.min(top, bottom);
		final int maxY = Math.max(top, bottom);

		final float alpha = (float) (color >> 24 & 255) / 255.0F;
		final float red = (float) (color >> 16 & 255) / 255.0F;
		final float green = (float) (color >> 8 & 255) / 255.0F;
		final float blue = (float) (color & 255) / 255.0F;

		final Tesselator tesselator = Tesselator.getInstance();
		final BufferBuilder buffer = tesselator.getBuilder();

		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.setShader(GameRenderer::getPositionColorShader);
		RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

		buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
		buffer.vertex(minX, maxY, 0.0D).color(red, green, blue, alpha).endVertex();
		buffer.vertex(maxX, maxY, 0.0D).color(red, green, blue, alpha).endVertex();
		buffer.vertex(maxX, minY, 0.0D).color(red, green, blue, alpha).endVertex();
		buffer.vertex(minX, minY, 0.0D).color(red, green, blue, alpha).endVertex();
		tesselator.end();

		RenderSystem.disableBlend();
	}

	/**
	 * Fills a rectangle with a colour that fades from one side to the other. The two colours ride
	 * along on the vertices, so this costs no more than a plain rectangle.
	 */
	public static void drawHorizontalGradient(int left, int top, int right, int bottom, int leftColor, int rightColor) {
		final int minX = Math.min(left, right);
		final int maxX = Math.max(left, right);
		final int minY = Math.min(top, bottom);
		final int maxY = Math.max(top, bottom);

		final Tesselator tesselator = Tesselator.getInstance();
		final BufferBuilder buffer = tesselator.getBuilder();

		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.setShader(GameRenderer::getPositionColorShader);
		RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

		buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
		vertexWithColor(buffer, minX, maxY, leftColor);
		vertexWithColor(buffer, maxX, maxY, rightColor);
		vertexWithColor(buffer, maxX, minY, rightColor);
		vertexWithColor(buffer, minX, minY, leftColor);
		tesselator.end();

		RenderSystem.disableBlend();
	}

	/**
	 * Fills a rectangle with a colour that fades from top to bottom. Like the horizontal gradient,
	 * the colours ride along on the vertices, so this costs no more than a plain rectangle.
	 */
	public static void drawVerticalGradient(int left, int top, int right, int bottom, int topColor, int bottomColor) {
		final int minX = Math.min(left, right);
		final int maxX = Math.max(left, right);
		final int minY = Math.min(top, bottom);
		final int maxY = Math.max(top, bottom);

		final Tesselator tesselator = Tesselator.getInstance();
		final BufferBuilder buffer = tesselator.getBuilder();

		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.setShader(GameRenderer::getPositionColorShader);
		RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

		buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
		vertexWithColor(buffer, minX, maxY, bottomColor);
		vertexWithColor(buffer, maxX, maxY, bottomColor);
		vertexWithColor(buffer, maxX, minY, topColor);
		vertexWithColor(buffer, minX, minY, topColor);
		tesselator.end();

		RenderSystem.disableBlend();
	}

	private static void vertexWithColor(BufferBuilder buffer, int x, int y, int color) {
		buffer.vertex(x, y, 0.0D).color((float) (color >> 16 & 255) / 255.0F, (float) (color >> 8 & 255) / 255.0F, (float) (color & 255) / 255.0F, (float) (color >> 24 & 255) / 255.0F).endVertex();
	}

	/** Draws a one pixel wide outline just outside the given rectangle. */
	public static void drawOutline(int left, int top, int right, int bottom, int color) {
		drawRect(left - 1, top - 1, right + 1, top, color);
		drawRect(left - 1, bottom, right + 1, bottom + 1, color);
		drawRect(left - 1, top, left, bottom, color);
		drawRect(right, top, right + 1, bottom, color);
	}

	/** Draws a one pixel wide outline along the inside edge of the given rectangle. */
	public static void drawInnerOutline(int left, int top, int right, int bottom, int color) {
		drawRect(left, top, right, top + 1, color);
		drawRect(left, bottom - 1, right, bottom, color);
		drawRect(left, top + 1, left + 1, bottom - 1, color);
		drawRect(right - 1, top + 1, right, bottom - 1, color);
	}

	/**
	 * Draws the raised surface the interface is built out of: a panel that fades from top to bottom
	 * behind a thin border, so that groups of controls read as belonging together.
	 */
	public static void drawPanel(int left, int top, int right, int bottom, int topColor, int bottomColor, int borderColor) {
		drawVerticalGradient(left, top, right, bottom, topColor, bottomColor);
		drawInnerOutline(left, top, right, bottom, borderColor);
	}

	/**
	 * Draws a label on a filled pill, and answers how wide it came out, so that several of them can
	 * be laid out in a row.
	 */
	public static int drawChip(PoseStack poseStack, String text, int x, int y, int backgroundColor, int textColor) {
		final int chipWidth = font.width(text) + 8;
		drawRect(x, y, x + chipWidth, y + 12, backgroundColor);
		font.draw(poseStack, text, x + 4, y + 2, textColor);
		return chipWidth;
	}

	/** Shortens a string to fit the given width, marking that it was shortened. */
	public static String trimToWidth(String text, int width) {
		if (font.width(text) <= width) {
			return text;
		}
		return font.plainSubstrByWidth(text, Math.max(0, width - font.width("…"))) + "…";
	}

	/**
	 * Confines everything drawn until {@link #disableScissor()} to the given rectangle, in the same
	 * scaled coordinates the rest of the interface is laid out in.
	 */
	public static void enableScissor(int left, int top, int right, int bottom) {
		final Window window = mc.getWindow();
		final double scale = window.getGuiScale();
		final int scissorHeight = (int) Math.round((bottom - top) * scale);
		// Scissor rectangles are in framebuffer pixels, measured from the bottom of the window
		RenderSystem.enableScissor((int) Math.round(left * scale), window.getHeight() - (int) Math.round(top * scale) - scissorHeight, (int) Math.round((right - left) * scale), scissorHeight);
	}

	public static void disableScissor() {
		RenderSystem.disableScissor();
	}

}