package com.chaosthedude.explorerscompass.gui;

import org.lwjgl.opengl.GL11;

import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.chaosthedude.explorerscompass.preview.StructurePreview;
import com.chaosthedude.explorerscompass.util.RenderUtils;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Vector3f;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.model.data.ModelData;

/**
 * Draws a structure preview, and holds where it is being looked at from.
 *
 * <p>The cells of a preview are drawn as the blocks they stand for, one block model each, so that
 * what is shown is the structure's own materials rather than a diagram of it. There are at most a
 * few thousand of them, since everything enclosed was dropped before the preview was ever sent, and
 * they are drawn through the same buffers the game draws an item in an inventory through.
 *
 * <p>The whole model is turned rather than the camera moved: the interface has no camera, and a
 * turned model is what the transform on the stack already expresses. Two of the three axes are
 * flipped, which puts the world's up on the screen's up and leaves the model the right way round —
 * flipping one alone would turn every face inside out and cut the model away.
 */
@OnlyIn(Dist.CLIENT)
public class StructurePreviewView {

	private static final float MIN_ZOOM = 0.3F;
	private static final float MAX_ZOOM = 5.0F;
	/** Looking down at the structure from one corner, which is how a building reads best at a glance. */
	private static final float DEFAULT_YAW = 45.0F;
	private static final float DEFAULT_PITCH = 30.0F;
	private static final float DEFAULT_ZOOM = 1.2F;
	private static final float MAX_PITCH = 89.0F;
	private static final float DEGREES_PER_PIXEL = 1.4F;
	private static final float SPIN_DEGREES_PER_SECOND = 9.0F;
	/** Anything longer than this is a frame that never happened, such as the one after a pause. */
	private static final long MAX_FRAME_MILLIS = 100L;
	/** How far in front of the panel the model is drawn, so that it stands over its background. */
	private static final double MODEL_DEPTH = 200.0D;

	private float yaw = DEFAULT_YAW;
	private float pitch = DEFAULT_PITCH;
	private float zoom = DEFAULT_ZOOM;
	/** How many layers are drawn, counted from the ground up, which is how to look inside. */
	private int visibleLayers = Integer.MAX_VALUE;
	private boolean dragging;
	private long lastFrameAt;

	/** Puts the view back where it opens, which is what makes turning it around freely safe. */
	public void reset() {
		yaw = DEFAULT_YAW;
		pitch = DEFAULT_PITCH;
		zoom = DEFAULT_ZOOM;
		visibleLayers = Integer.MAX_VALUE;
	}

	public void setDragging(boolean dragging) {
		this.dragging = dragging;
	}

	/** Turns the model with the pointer. Looking past straight up or down would flip it over. */
	public void drag(double dragX, double dragY) {
		yaw += (float) dragX * DEGREES_PER_PIXEL;
		pitch = Mth.clamp(pitch + (float) dragY * DEGREES_PER_PIXEL, -MAX_PITCH, MAX_PITCH);
	}

	public void zoom(double amount) {
		zoom = Mth.clamp(zoom * (float) Math.pow(1.2D, amount), MIN_ZOOM, MAX_ZOOM);
	}

	/** Takes layers off the top, or puts them back, so that the inside of a building can be seen. */
	public void cut(double amount, StructurePreview preview) {
		final int layers = layersShown(preview);
		visibleLayers = Mth.clamp(layers + (int) Math.signum(amount), 1, preview.getGridY());
	}

	/** How many layers are currently drawn, which is all of them until any have been taken off. */
	public int layersShown(StructurePreview preview) {
		return Math.min(visibleLayers, preview.getGridY());
	}

	public boolean isCutAway(StructurePreview preview) {
		return layersShown(preview) < preview.getGridY();
	}

	public void render(PoseStack poseStack, StructurePreview preview, int left, int top, int right, int bottom) {
		advanceSpin();

		final int panelWidth = right - left;
		final int panelHeight = bottom - top;
		if (panelWidth <= 0 || panelHeight <= 0 || preview.getCellCount() == 0) {
			return;
		}

		final float gridX = preview.getGridX();
		final float gridY = preview.getGridY();
		final float gridZ = preview.getGridZ();
		// Scaled against the longest way through the model rather than its widest side, so that turning
		// it around never swings a corner of it out of the panel
		final float extent = Math.max(1.0F, Mth.sqrt(gridX * gridX + gridY * gridY + gridZ * gridZ));
		final float scale = (Math.min(panelWidth, panelHeight) * 0.8F / extent) * zoom;

		RenderUtils.enableScissor(left, top, right, bottom);
		// The interface is flat and lays its panels out along the depth the model is about to be drawn
		// through, so the depth inside the panel is emptied before the model goes in — otherwise the
		// half of it furthest from the viewer would be hidden behind the flat background it stands on —
		// and emptied again afterwards, so that a corner of it reaching towards the viewer cannot punch
		// through a tooltip drawn over the panel later. Clearing obeys the scissor, so both of these
		// reach no further than the panel itself.
		clearDepthInsideThePanel();
		RenderSystem.enableDepthTest();
		RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
		// The lighting a block model is drawn under in an inventory, which is what gives its faces
		// their shading; without it every side of every block comes out the same flat colour
		Lighting.setupFor3DItems();

		poseStack.pushPose();
		poseStack.translate(left + panelWidth / 2.0D, top + panelHeight / 2.0D, MODEL_DEPTH);
		poseStack.scale(scale, -scale, -scale);
		poseStack.mulPose(Vector3f.XP.rotationDegrees(pitch));
		poseStack.mulPose(Vector3f.YP.rotationDegrees(yaw));
		poseStack.translate(-gridX / 2.0F, -gridY / 2.0F, -gridZ / 2.0F);

		final Minecraft mc = Minecraft.getInstance();
		final BlockRenderDispatcher blockRenderer = mc.getBlockRenderer();
		final MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
		final int layerLimit = layersShown(preview);
		for (int cell = 0; cell < preview.getCellCount(); cell++) {
			final int cellY = preview.getCellY(cell);
			if (cellY >= layerLimit) {
				continue;
			}

			final BlockState state = preview.getCellState(cell);
			// A block drawn by a block entity renderer draws nothing through here, and one that draws
			// nothing at all was already left out when the preview was built
			if (state.getRenderShape() != RenderShape.MODEL) {
				continue;
			}

			poseStack.pushPose();
			poseStack.translate(preview.getCellX(cell), cellY, preview.getCellZ(cell));
			// Every block through one render type, so that the whole model is drawn in a single batch
			// and every face of it is lit the same way
			blockRenderer.renderSingleBlock(state, poseStack, buffers, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, ModelData.EMPTY, Sheets.cutoutBlockSheet());
			poseStack.popPose();
		}
		buffers.endBatch();

		poseStack.popPose();

		clearDepthInsideThePanel();
		RenderSystem.disableDepthTest();
		RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
		RenderUtils.disableScissor();
	}

	/** Empties the depth buffer over whatever the scissor currently confines drawing to. */
	private static void clearDepthInsideThePanel() {
		RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
	}

	/**
	 * Turns the model slowly on its own, so that it is seen from more than one side without being
	 * touched. Measured against the clock rather than counted in frames, so that it turns at the same
	 * rate however fast the game is drawing.
	 */
	private void advanceSpin() {
		final long now = Util.getMillis();
		final long sinceLastFrame = now - lastFrameAt;
		lastFrameAt = now;
		if (dragging || !ConfigHandler.CLIENT.structurePreviewAutoSpin.get() || sinceLastFrame <= 0L || sinceLastFrame > MAX_FRAME_MILLIS) {
			return;
		}
		yaw += SPIN_DEGREES_PER_SECOND * sinceLastFrame / 1000.0F;
	}

}
