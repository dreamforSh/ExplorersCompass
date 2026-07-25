package com.chaosthedude.explorerscompass.util;

import com.chaosthedude.explorerscompass.client.OverlaySide;
import com.chaosthedude.explorerscompass.config.ConfigHandler;
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

	public static void drawStringLeft(PoseStack poseStack, String string, Font fontRenderer, int x, int y, int color) {
		fontRenderer.drawShadow(poseStack, string, x, y, color);
	}

	public static void drawStringRight(PoseStack poseStack, String string, Font fontRenderer, int x, int y, int color) {
		fontRenderer.drawShadow(poseStack, string, x - fontRenderer.width(string), y, color);
	}

	public static void drawConfiguredStringOnHUD(PoseStack poseStack, String string, int xOffset, int yOffset, int color, int relLineOffset) {
		yOffset += (relLineOffset + ConfigHandler.CLIENT.overlayLineOffset.get()) * 9;
		if (ConfigHandler.CLIENT.overlaySide.get() == OverlaySide.LEFT) {
			drawStringLeft(poseStack, string, font, xOffset + 2, yOffset + 2, color);
		} else {
			drawStringRight(poseStack, string, font, mc.getWindow().getGuiScaledWidth() - xOffset - 2, yOffset + 2, color);
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