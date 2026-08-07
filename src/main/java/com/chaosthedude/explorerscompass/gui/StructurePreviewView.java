package com.chaosthedude.explorerscompass.gui;

import org.lwjgl.opengl.GL11;

import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.chaosthedude.explorerscompass.preview.StructurePreview;
import com.chaosthedude.explorerscompass.util.RenderUtils;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Matrix4f;
import com.mojang.math.Vector3f;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
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
 * few thousand of them, since everything enclosed was dropped before the preview was ever sent.
 *
 * <p>The model is built once into a buffer on the graphics card and then only drawn, rather than
 * being put together again on every frame. Turning it and zooming it are a transform on a model that
 * has already been built, so neither costs anything; only taking layers off it, which changes what
 * there is to draw, builds it again. Assembling several thousand block models sixty times a second
 * is what a preview would otherwise spend the whole frame on.
 *
 * <p>The whole model is turned rather than the camera moved: the interface has no camera, and a
 * turned model is what the transform on the stack already expresses.
 *
 * <p><b>Exactly one axis is flipped, and it has to stay exactly one.</b> The interface counts its
 * height downwards where the screen counts it upwards, so the projection every screen is drawn
 * through already carries a flip of its own. Whether a face counts as pointing towards the viewer
 * follows from how many flips there are altogether, not from how many this adds: an even number
 * leaves faces the way round they were built, an odd number turns every one of them inside out, and
 * the graphics card then draws the far side of the model and throws the near side away. One flip
 * here, against the projection's, is what makes two. This is the same one flip the game applies to
 * an item drawn in an inventory, for the same reason.
 */
@OnlyIn(Dist.CLIENT)
public class StructurePreviewView implements AutoCloseable {

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
	/** Room for a few thousand block models before the buffer has to grow itself. */
	private static final int INITIAL_BUFFER_BYTES = 1 << 21;

	private float yaw = DEFAULT_YAW;
	private float pitch = DEFAULT_PITCH;
	private float zoom = DEFAULT_ZOOM;
	/** How many layers are drawn, counted from the ground up, which is how to look inside. */
	private int visibleLayers = Integer.MAX_VALUE;
	private boolean dragging;
	private long lastFrameAt;

	/** The built model, and what it was built from, so that it is only ever built again when it must be. */
	private VertexBuffer vertexBuffer;
	private StructurePreview builtFrom;
	private int builtLayers;

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

		final int layerLimit = layersShown(preview);
		if (preview != builtFrom || layerLimit != builtLayers) {
			build(preview, layerLimit);
		}
		if (vertexBuffer == null) {
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
		setupLighting();

		poseStack.pushPose();
		poseStack.translate(left + panelWidth / 2.0D, top + panelHeight / 2.0D, MODEL_DEPTH);
		// One flip, and only one: see the note on this class for why the count is what matters
		poseStack.scale(scale, -scale, scale);
		poseStack.mulPose(Vector3f.XP.rotationDegrees(pitch));
		poseStack.mulPose(Vector3f.YP.rotationDegrees(yaw));
		poseStack.translate(-gridX / 2.0F, -gridY / 2.0F, -gridZ / 2.0F);

		final RenderType renderType = Sheets.cutoutBlockSheet();
		renderType.setupRenderState();
		// The interface's own transform is applied outside this screen's, which is what the buffer's
		// vertices were built in
		final Matrix4f modelView = RenderSystem.getModelViewMatrix().copy();
		modelView.multiply(poseStack.last().pose());
		final ShaderInstance shader = RenderSystem.getShader();
		vertexBuffer.bind();
		vertexBuffer.drawWithShader(modelView, RenderSystem.getProjectionMatrix(), shader);
		VertexBuffer.unbind();
		renderType.clearRenderState();

		poseStack.popPose();

		// Put the lights back where the rest of the interface expects them
		Lighting.setupFor3DItems();

		clearDepthInsideThePanel();
		RenderSystem.disableDepthTest();
		RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
		RenderUtils.disableScissor();
	}

	/**
	 * Builds the model into a buffer on the graphics card. The cells are laid out where they stand in
	 * the preview and nowhere else: how the model is turned and how large it is drawn are a transform
	 * applied to the built model, so that neither of them is a reason to build it again.
	 */
	private void build(StructurePreview preview, int layerLimit) {
		close();
		builtFrom = preview;
		builtLayers = layerLimit;

		final BlockRenderDispatcher blockRenderer = Minecraft.getInstance().getBlockRenderer();
		final RenderType renderType = Sheets.cutoutBlockSheet();
		final BufferBuilder builder = new BufferBuilder(INITIAL_BUFFER_BYTES);
		builder.begin(VertexFormat.Mode.QUADS, renderType.format());
		// Every block through the one render type the whole model is drawn in, so that it comes out as
		// a single buffer and every face of it is lit the same way
		final MultiBufferSource singleBuffer = (type) -> builder;
		final PoseStack cellStack = new PoseStack();

		for (int cell = 0; cell < preview.getCellCount(); cell++) {
			final int cellY = preview.getCellY(cell);
			if (cellY >= layerLimit) {
				continue;
			}

			final BlockState state = preview.getCellState(cell);
			// Anything not drawn from a model was left out when the preview was built; this is the
			// guard for a block whose id means something else on this side of the connection
			if (state.getRenderShape() != RenderShape.MODEL) {
				continue;
			}

			cellStack.pushPose();
			cellStack.translate(preview.getCellX(cell), cellY, preview.getCellZ(cell));
			blockRenderer.renderSingleBlock(state, cellStack, singleBuffer, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, ModelData.EMPTY, renderType);
			cellStack.popPose();
		}

		final BufferBuilder.RenderedBuffer rendered = builder.endOrDiscardIfEmpty();
		if (rendered == null) {
			return;
		}

		vertexBuffer = new VertexBuffer();
		vertexBuffer.bind();
		vertexBuffer.upload(rendered);
		VertexBuffer.unbind();
	}

	/**
	 * Frees the built model. The screen showing it calls this when it closes: what is held here is
	 * memory on the graphics card, which nothing else would ever give back.
	 */
	@Override
	public void close() {
		if (vertexBuffer != null) {
			vertexBuffer.close();
			vertexBuffer = null;
		}
		builtFrom = null;
		builtLayers = 0;
	}

	/**
	 * Points the two lights the model is shaded by, turned so that they stand still while it turns.
	 *
	 * <p>The shader a block model is drawn through shades it against the normals as they were built
	 * into the buffer, without putting them through the transform that turns the model. That is part
	 * of what makes a model worth building once — nothing in the buffer has to change as it is turned
	 * — but it also means the shading would turn along with it, and read as a lamp bolted to the
	 * building rather than as daylight. Turning the lights the other way instead costs two vectors a
	 * frame and leaves the light where light belongs.
	 */
	private void setupLighting() {
		RenderSystem.setShaderLights(lightTurnedBack(0.2F, 1.0F, -0.7F), lightTurnedBack(-0.2F, 1.0F, 0.7F));
	}

	/**
	 * One of the two directions the game lights an item in an inventory from, turned by the opposite
	 * of however the model is currently turned. Undone innermost first: the model is turned about its
	 * own upright and then tilted, so a light standing still is tilted back and then turned back.
	 */
	private Vector3f lightTurnedBack(float x, float y, float z) {
		final Vector3f light = new Vector3f(x, y, z);
		light.transform(Vector3f.XP.rotationDegrees(-pitch));
		light.transform(Vector3f.YP.rotationDegrees(-yaw));
		return light;
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
