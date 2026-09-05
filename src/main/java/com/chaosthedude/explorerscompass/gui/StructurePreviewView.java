package com.chaosthedude.explorerscompass.gui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.lwjgl.opengl.GL11;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.chaosthedude.explorerscompass.preview.StructurePreview;
import com.chaosthedude.explorerscompass.util.RenderUtils;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Matrix4f;
import com.mojang.math.Vector3f;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Draws a structure preview, and holds where it is being looked at from.
 *
 * <p>What is drawn is built once into buffers on the graphics card and then only drawn again.
 * Turning it, zooming it and sliding it about are a transform on a model that has already been
 * built, so none of them costs anything; only taking layers off it, or changing what it is drawn as,
 * changes what there is to draw and has it built again.
 *
 * <p>Building happens off the render thread: see {@link PreviewMeshBuilder}. While a model is being
 * built the last one goes on being shown, and where there is none yet — or where the new one is
 * taking its time — a rough one in flat colours, which builds in a fraction of the time, stands in
 * until the finished one lands. Nothing about it holds up a frame.
 *
 * <p>The blocks with a renderer of their own — the chests, beds, banners and signs — cannot go
 * through the block tesselator, so each is drawn once through its own renderer into buffers of its
 * own when the model is uploaded, and those buffers are then drawn along with the rest. That keeps
 * the cost of a chest to a draw call rather than to a renderer run on every frame.
 *
 * <p>The whole model is turned rather than the camera moved: the interface has no camera, and a
 * turned model is what the transform on the stack already expresses.
 *
 * <p><b>Exactly one axis is flipped, and it has to stay exactly one.</b> The interface counts its
 * height downwards where the screen counts it upwards, so the projection every screen is drawn
 * through already carries a flip of its own. Whether a face counts as pointing towards the viewer
 * follows from how many flips there are altogether: an even number leaves faces the way round they
 * were built, an odd number turns every one of them inside out. One flip here, against the
 * projection's, is what makes two. The flip is put on the interface's own matrix rather than on the
 * stack the model is turned on, which keeps it away from the normals the shading is worked out from.
 */
@OnlyIn(Dist.CLIENT)
public class StructurePreviewView implements AutoCloseable {

	private static final float MIN_ZOOM = 0.3F;
	private static final float MAX_ZOOM = 6.0F;
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
	/**
	 * How long a model that no longer shows quite the right thing stays up before the rough
	 * stand-in takes its place. Long enough that a quick rebuild lands without anything flickering,
	 * short enough that a slow one is not mistaken for nothing having happened.
	 */
	private static final long STALE_MODEL_GRACE_MILLIS = 150L;
	/** Below this many cells the textured model builds fast enough that no stand-in is worth building first. */
	private static final int QUICK_BUILD_CELLS = 4000;
	/**
	 * How many of the blocks with a renderer of their own are drawn. Each is run through its renderer
	 * once, when the model is uploaded, and drawn from a buffer thereafter, so this is a bound on how
	 * long an upload can take rather than on what a frame costs.
	 */
	private static final int MAX_BAKED_COMPONENTS = 512;
	private static final int COMPONENT_BUFFER_BYTES = 1 << 16;

	private static final int GRID_EDGE_COLOR = 0x70FFFFFF;
	private static final int GRID_LINE_COLOR = 0x24FFFFFF;
	private static final int NORTH_COLOR = 0xE0FFC24B;
	private static final int COMPASS_DISC_COLOR = 0x90000000;
	private static final int COMPASS_RING_COLOR = 0x40FFFFFF;
	private static final int COMPASS_TAIL_COLOR = 0xA0A0A6AD;

	/**
	 * The see-through blocks, drawn the way the world draws them but into the screen rather than into
	 * the buffer the world composes them from, which is what the world's own render type would do
	 * under fancy graphics. Depth is not written: nothing here is sorted, and a pane of glass that
	 * wrote its depth would hide the one behind it rather than show it through.
	 */
	private static final RenderType TRANSLUCENT_BLOCKS = PreviewRenderTypes.translucentBlocks();

	/**
	 * The pieces a render type is put together from are kept away from anything that is not one, so
	 * they are reached from something that is: a shard of no state of its own, which exists only to
	 * stand inside the family and hand the pieces out.
	 */
	@OnlyIn(Dist.CLIENT)
	private static final class PreviewRenderTypes extends RenderStateShard {

		private PreviewRenderTypes() {
			super("explorerscompass_preview", () -> {
			}, () -> {
			});
		}

		static RenderType translucentBlocks() {
			return RenderType.create("explorerscompass_preview_translucent", DefaultVertexFormat.BLOCK, VertexFormat.Mode.QUADS, 1536, false, true,
					RenderType.CompositeState.builder()
							.setLightmapState(LIGHTMAP)
							.setShaderState(RENDERTYPE_TRANSLUCENT_SHADER)
							.setTextureState(BLOCK_SHEET_MIPPED)
							.setTransparencyState(TRANSLUCENT_TRANSPARENCY)
							.setWriteMaskState(COLOR_WRITE)
							.createCompositeState(false));
		}

	}

	/** The one thread models are built on, so that two builds never contend for the same cores. */
	private static final ExecutorService BUILDER = Executors.newSingleThreadExecutor((task) -> {
		final Thread thread = new Thread(task, "Explorer's Compass preview builder");
		thread.setDaemon(true);
		thread.setPriority(Thread.NORM_PRIORITY - 1);
		return thread;
	});

	/** What a model is built for: which preview, how many layers of it, and drawn as what. */
	private record Wanted(StructurePreview preview, int layers, PreviewMeshBuilder.Mode mode) {
	}

	/** What a build sent back: the model, or null where the build failed. */
	private record Delivery(int generation, Wanted wanted, PreviewMeshBuilder.Result result) {
	}

	/** The buffers of one block with a renderer of its own, under the render type it asked for. */
	private record BakedComponents(RenderType type, VertexBuffer buffer) {
	}

	/** A model on the graphics card, and what it is a model of. */
	private static final class Uploaded implements AutoCloseable {

		final Wanted wanted;
		final PreviewMeshBuilder.Mode mode;
		final int quads;
		final int cells;
		VertexBuffer opaque;
		VertexBuffer translucent;
		VertexBuffer coloured;
		List<BakedComponents> components = List.of();
		int componentCount;

		Uploaded(Wanted wanted, PreviewMeshBuilder.Mode mode, int quads, int cells) {
			this.wanted = wanted;
			this.mode = mode;
			this.quads = quads;
			this.cells = cells;
		}

		/** Whether this shows exactly what is asked for, rather than standing in for it. */
		boolean isFinishedFor(Wanted asked) {
			return wanted.equals(asked) && mode == asked.mode();
		}

		@Override
		public void close() {
			if (opaque != null) {
				opaque.close();
			}
			if (translucent != null) {
				translucent.close();
			}
			if (coloured != null) {
				coloured.close();
			}
			for (BakedComponents component : components) {
				component.buffer().close();
			}
		}

	}

	private float yaw = DEFAULT_YAW;
	private float pitch = DEFAULT_PITCH;
	private float zoom = DEFAULT_ZOOM;
	/** How far the model has been slid across the panel, in pixels of the interface. */
	private float panX;
	private float panY;
	/** How many layers are drawn, counted from the ground up, which is how to look inside. */
	private int visibleLayers = Integer.MAX_VALUE;
	private boolean dragging;
	private boolean panning;
	private long lastFrameAt;
	/** What the model is drawn as, or null to let how large it is decide. */
	private PreviewMeshBuilder.Mode requestedMode;
	private StructurePreview lastPreview;

	private Uploaded current;
	/** A model in flat colours standing in for the one being built, when the build is taking a while. */
	private Uploaded standIn;
	/** Whichever of the two went on screen last, which is what the readings beside the model describe. */
	private Uploaded shownLastFrame;
	private Wanted inFlight;
	private long inFlightSince;
	/** A model that could not be built, so that it is not asked for again on the next frame. */
	private Wanted failed;
	/** Bumped whenever a build starts, so that a build that is no longer wanted can tell. */
	private final AtomicInteger generation = new AtomicInteger();
	/** How far the build on its way has got, in thousandths. */
	private final AtomicInteger progress = new AtomicInteger();
	private final ArrayDeque<Delivery> deliveries = new ArrayDeque<Delivery>();

	// Readings

	/** Whether what is being drawn is coloured cubes rather than the blocks themselves. */
	public boolean isSimplified() {
		return shownLastFrame != null && shownLastFrame.mode == PreviewMeshBuilder.Mode.COLOURED;
	}

	/** Whether the model on screen is a rough stand-in for one that is still being built. */
	public boolean isShowingStandIn() {
		return shownLastFrame != null && shownLastFrame == standIn;
	}

	/** How many of the blocks with a renderer of their own are being drawn. */
	public int getDrawnComponents() {
		return shownLastFrame != null ? shownLastFrame.componentCount : 0;
	}

	/** How many faces the model on screen is made of. */
	public int getDrawnQuads() {
		return shownLastFrame != null ? shownLastFrame.quads : 0;
	}

	/** Whether a model is being built at the moment. */
	public boolean isBuilding() {
		return inFlight != null;
	}

	/**
	 * How far the textured model on its way has got, in thousandths, or -1 when none is. A model in
	 * flat colours lands too soon to be worth reporting on.
	 */
	public int getBuildProgress() {
		return inFlight != null && inFlight.mode() == PreviewMeshBuilder.Mode.TEXTURED ? progress.get() : -1;
	}

	public float getYaw() {
		return yaw;
	}

	public float getPitch() {
		return pitch;
	}

	/** Which way the model is being asked to be drawn, or null while its size is left to decide. */
	public PreviewMeshBuilder.Mode getRequestedMode() {
		return requestedMode;
	}

	/** Which way the given preview is drawn: as asked, or as its size decides. */
	public PreviewMeshBuilder.Mode getMode(StructurePreview preview) {
		if (requestedMode != null) {
			return requestedMode;
		}
		return preview.getCellCount() > ConfigHandler.CLIENT.structurePreviewDetailLimit.get() ? PreviewMeshBuilder.Mode.COLOURED : PreviewMeshBuilder.Mode.TEXTURED;
	}

	// Controls

	/** Puts the view back where it opens, which is what makes turning it around freely safe. */
	public void reset() {
		yaw = DEFAULT_YAW;
		pitch = DEFAULT_PITCH;
		zoom = DEFAULT_ZOOM;
		panX = 0.0F;
		panY = 0.0F;
		visibleLayers = Integer.MAX_VALUE;
	}

	/** Looks at the model from the given angles, keeping how large it is and where it stands. */
	public void lookFrom(float yaw, float pitch) {
		this.yaw = yaw;
		this.pitch = Mth.clamp(pitch, -MAX_PITCH, MAX_PITCH);
	}

	public void setDragging(boolean dragging) {
		this.dragging = dragging;
	}

	public boolean isDragging() {
		return dragging;
	}

	public void setPanning(boolean panning) {
		this.panning = panning;
	}

	public boolean isPanning() {
		return panning;
	}

	/** Turns the model with the pointer. Looking past straight up or down would flip it over. */
	public void drag(double dragX, double dragY) {
		rotate((float) dragX * DEGREES_PER_PIXEL, (float) dragY * DEGREES_PER_PIXEL);
	}

	public void rotate(float yawDegrees, float pitchDegrees) {
		yaw += yawDegrees;
		pitch = Mth.clamp(pitch + pitchDegrees, -MAX_PITCH, MAX_PITCH);
	}

	/** Slides the model across the panel with the pointer, so that a corner of it can be brought to the middle. */
	public void pan(double dragX, double dragY) {
		panX += (float) dragX;
		panY += (float) dragY;
	}

	public void zoom(double amount) {
		zoom = Mth.clamp(zoom * (float) Math.pow(1.2D, amount), MIN_ZOOM, MAX_ZOOM);
	}

	/** Takes layers off the top, or puts them back, so that the inside of a building can be seen. */
	public void cut(double amount, StructurePreview preview) {
		setLayersShown(layersShown(preview) + (int) Math.signum(amount), preview);
	}

	public void setLayersShown(int layers, StructurePreview preview) {
		visibleLayers = Mth.clamp(layers, 1, preview.getGridY());
	}

	/** How many layers are currently drawn, which is all of them until any have been taken off. */
	public int layersShown(StructurePreview preview) {
		return Math.min(visibleLayers, preview.getGridY());
	}

	public boolean isCutAway(StructurePreview preview) {
		return layersShown(preview) < preview.getGridY();
	}

	/** Draws the model the other way: as coloured cubes where it was the blocks themselves, and back. */
	public void toggleMode(StructurePreview preview) {
		requestedMode = getMode(preview) == PreviewMeshBuilder.Mode.TEXTURED ? PreviewMeshBuilder.Mode.COLOURED : PreviewMeshBuilder.Mode.TEXTURED;
	}

	// Drawing

	public void render(PoseStack poseStack, StructurePreview preview, int left, int top, int right, int bottom) {
		advanceSpin();

		final int panelWidth = right - left;
		final int panelHeight = bottom - top;
		// A structure can be nothing but a chest, so what makes a preview empty is holding neither a
		// shell nor anything with a renderer of its own
		if (panelWidth <= 0 || panelHeight <= 0 || (preview.getCellCount() == 0 && preview.getComponentCount() == 0)) {
			return;
		}

		if (preview != lastPreview) {
			// Whatever was asked of the last structure was asked of that one, and whatever was built
			// of it is a model of something else
			lastPreview = preview;
			requestedMode = null;
			failed = null;
			if (current != null) {
				current.close();
				current = null;
			}
			if (standIn != null) {
				standIn.close();
				standIn = null;
			}
		}

		final Wanted wanted = new Wanted(preview, layersShown(preview), getMode(preview));
		takeDeliveries();
		if ((current == null || !current.isFinishedFor(wanted)) && !wanted.equals(inFlight) && !wanted.equals(failed)) {
			startBuild(wanted);
		}

		final Uploaded shown = chooseShown(wanted);
		shownLastFrame = shown;
		if (shown == null) {
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

		// Where on the screen the model goes, and the one flip, on the interface's own matrix
		final PoseStack screenStack = RenderSystem.getModelViewStack();
		screenStack.pushPose();
		screenStack.translate(left + panelWidth / 2.0D + panX, top + panelHeight / 2.0D + panY, MODEL_DEPTH);
		screenStack.scale(scale, -scale, scale);
		RenderSystem.applyModelViewMatrix();

		// How the model is turned, on a stack of its own, so that what it does to the normals is only
		// ever a rotation
		final PoseStack modelStack = new PoseStack();
		modelStack.mulPose(Vector3f.XP.rotationDegrees(pitch));
		modelStack.mulPose(Vector3f.YP.rotationDegrees(yaw));
		modelStack.translate(-gridX / 2.0F, -gridY / 2.0F, -gridZ / 2.0F);
		// The matrix classes are the game's own here: copying and multiplying in place are their spellings
		final Matrix4f modelView = RenderSystem.getModelViewMatrix().copy();
		modelView.multiply(modelStack.last().pose());

		if (shown.coloured != null) {
			drawColouredCubes(shown.coloured, modelView);
		}
		if (shown.opaque != null) {
			drawBlocks(shown.opaque, RenderType.cutoutMipped(), modelView);
		}
		drawGround(modelStack, preview);
		if (!shown.components.isEmpty()) {
			drawComponents(shown.components, modelView);
		}
		if (shown.translucent != null) {
			drawBlocks(shown.translucent, TRANSLUCENT_BLOCKS, modelView);
		}

		screenStack.popPose();
		RenderSystem.applyModelViewMatrix();

		clearDepthInsideThePanel();
		RenderSystem.disableDepthTest();
		RenderSystem.disableBlend();
		RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
		RenderUtils.disableScissor();
	}

	/**
	 * Which model to put on screen this frame: the finished one where there is one, the last finished
	 * one while a new one is quick in coming, and the rough stand-in once that has taken too long.
	 */
	private Uploaded chooseShown(Wanted wanted) {
		if (current != null && current.isFinishedFor(wanted)) {
			return current;
		}
		if (standIn != null && standIn.wanted.equals(wanted)) {
			if (current == null || Util.getMillis() - inFlightSince > STALE_MODEL_GRACE_MILLIS) {
				return standIn;
			}
		}
		return current;
	}

	/**
	 * Draws a compass in the corner of the panel that says which way north lies in the model as it is
	 * currently turned, since a building turned about freely soon stops saying so itself.
	 */
	public void renderCompass(PoseStack poseStack, Font font, int centerX, int centerY, int radius) {
		// North is the model's -z, turned by the yaw and then laid flat onto the panel: what points
		// away from the viewer on the model points up on the panel
		final float radians = yaw * Mth.DEG_TO_RAD;
		final float northX = -Mth.sin(radians);
		final float northY = -Mth.cos(radians);

		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.disableCull();
		RenderSystem.setShader(GameRenderer::getPositionColorShader);
		final Matrix4f pose = poseStack.last().pose();
		final BufferBuilder builder = Tesselator.getInstance().getBuilder();
		final int segments = 24;

		builder.begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);
		vertex(builder, pose, centerX, centerY, COMPASS_DISC_COLOR);
		for (int i = 0; i <= segments; i++) {
			final float angle = (float) (i * Math.PI * 2.0D / segments);
			vertex(builder, pose, centerX + Mth.cos(angle) * radius, centerY + Mth.sin(angle) * radius, COMPASS_DISC_COLOR);
		}
		BufferUploader.drawWithShader(builder.end());

		builder.begin(VertexFormat.Mode.DEBUG_LINE_STRIP, DefaultVertexFormat.POSITION_COLOR);
		for (int i = 0; i <= segments; i++) {
			final float angle = (float) (i * Math.PI * 2.0D / segments);
			vertex(builder, pose, centerX + Mth.cos(angle) * radius, centerY + Mth.sin(angle) * radius, COMPASS_RING_COLOR);
		}
		BufferUploader.drawWithShader(builder.end());

		// The needle: a bright half pointing north and a dim half pointing away from it
		final float tipX = centerX + northX * (radius - 3);
		final float tipY = centerY + northY * (radius - 3);
		final float sideX = -northY * 2.5F;
		final float sideY = northX * 2.5F;
		builder.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
		vertex(builder, pose, tipX, tipY, NORTH_COLOR);
		vertex(builder, pose, centerX + sideX, centerY + sideY, NORTH_COLOR);
		vertex(builder, pose, centerX - sideX, centerY - sideY, NORTH_COLOR);
		vertex(builder, pose, centerX - northX * (radius - 3), centerY - northY * (radius - 3), COMPASS_TAIL_COLOR);
		vertex(builder, pose, centerX - sideX, centerY - sideY, COMPASS_TAIL_COLOR);
		vertex(builder, pose, centerX + sideX, centerY + sideY, COMPASS_TAIL_COLOR);
		BufferUploader.drawWithShader(builder.end());

		RenderSystem.enableCull();
		RenderSystem.disableBlend();

		final String north = I18n.get("string.explorerscompass.direction.north");
		font.drawShadow(poseStack, north, Math.round(centerX + northX * (radius + 6)) - font.width(north) / 2, Math.round(centerY + northY * (radius + 6)) - font.lineHeight / 2, GuiTheme.ACCENT);
	}

	/** One flat vertex of the interface, coloured as alpha, red, green and blue bytes. */
	private static void vertex(VertexConsumer builder, Matrix4f pose, float x, float y, int colour) {
		builder.vertex(pose, x, y, 0.0F).color(colour).endVertex();
	}

	/** Draws a buffer of the blocks themselves, through the render type that owns the way it was built. */
	private static void drawBlocks(VertexBuffer buffer, RenderType renderType, Matrix4f modelView) {
		renderType.setupRenderState();
		final ShaderInstance shader = RenderSystem.getShader();
		if (shader.CHUNK_OFFSET != null) {
			// The shaders the world draws its blocks through add where the chunk stands; this is not a chunk
			shader.CHUNK_OFFSET.set(0.0F, 0.0F, 0.0F);
		}
		buffer.bind();
		buffer.drawWithShader(modelView, RenderSystem.getProjectionMatrix(), shader);
		VertexBuffer.unbind();
		renderType.clearRenderState();
	}

	/**
	 * Draws the model built from coloured cubes. The shading is already in the colours the cubes were
	 * built with, so this needs no lighting and no texture: position and colour is the whole of it.
	 */
	private static void drawColouredCubes(VertexBuffer buffer, Matrix4f modelView) {
		RenderSystem.setShader(GameRenderer::getPositionColorShader);
		RenderSystem.disableBlend();
		RenderSystem.enableCull();
		RenderSystem.enableDepthTest();
		RenderSystem.depthFunc(GL11.GL_LEQUAL);
		RenderSystem.depthMask(true);
		buffer.bind();
		buffer.drawWithShader(modelView, RenderSystem.getProjectionMatrix(), RenderSystem.getShader());
		VertexBuffer.unbind();
	}

	/**
	 * Draws the chests, beds, banners and signs from the buffers they were baked into, each under the
	 * render type its renderer asked for. Their shading comes from the two lights an inventory uses,
	 * turned against the model so that they stand still while it turns.
	 */
	private void drawComponents(List<BakedComponents> components, Matrix4f modelView) {
		setupTurnedLighting();
		for (BakedComponents component : components) {
			component.type().setupRenderState();
			component.buffer().bind();
			component.buffer().drawWithShader(modelView, RenderSystem.getProjectionMatrix(), RenderSystem.getShader());
			VertexBuffer.unbind();
			component.type().clearRenderState();
		}
		Lighting.setupFor3DItems();
	}

	/**
	 * Draws the ground the model stands on: a grid across its footprint, one cell to a square on a
	 * small structure and coarser on a large one, and an arrow off its north edge. Both are what
	 * gives a building turned about freely a size and a bearing.
	 */
	private static void drawGround(PoseStack modelStack, StructurePreview preview) {
		final Matrix4f pose = modelStack.last().pose();
		final int gridX = preview.getGridX();
		final int gridZ = preview.getGridZ();
		final int largest = Math.max(gridX, gridZ);
		final int spacing = largest <= 24 ? 1 : largest <= 96 ? 4 : largest <= 384 ? 16 : 64;
		// A shade below the model, so that its underside never fights the grid for the same depth
		final float y = -0.05F;

		RenderSystem.setShader(GameRenderer::getPositionColorShader);
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.disableCull();
		// The render type drawn before this switches the depth test off again as it clears its state
		RenderSystem.enableDepthTest();
		RenderSystem.depthFunc(GL11.GL_LEQUAL);
		RenderSystem.depthMask(true);

		final BufferBuilder builder = Tesselator.getInstance().getBuilder();
		builder.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
		for (int x = spacing; x < gridX; x += spacing) {
			line(builder, pose, x, y, 0, x, y, gridZ, GRID_LINE_COLOR);
		}
		for (int z = spacing; z < gridZ; z += spacing) {
			line(builder, pose, 0, y, z, gridX, y, z, GRID_LINE_COLOR);
		}
		line(builder, pose, 0, y, 0, 0, y, gridZ, GRID_EDGE_COLOR);
		line(builder, pose, gridX, y, 0, gridX, y, gridZ, GRID_EDGE_COLOR);
		line(builder, pose, 0, y, 0, gridX, y, 0, GRID_EDGE_COLOR);
		line(builder, pose, 0, y, gridZ, gridX, y, gridZ, GRID_EDGE_COLOR);
		BufferUploader.drawWithShader(builder.end());

		// The arrow: a triangle standing off the middle of the north edge, pointing away from the model
		final float size = Math.max(1.5F, largest / 24.0F);
		final float centerX = gridX / 2.0F;
		final float base = -0.6F * size;
		builder.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
		builder.vertex(pose, centerX, y, base - size * 1.4F).color(NORTH_COLOR).endVertex();
		builder.vertex(pose, centerX - size * 0.7F, y, base).color(NORTH_COLOR).endVertex();
		builder.vertex(pose, centerX + size * 0.7F, y, base).color(NORTH_COLOR).endVertex();
		BufferUploader.drawWithShader(builder.end());

		RenderSystem.enableCull();
		RenderSystem.disableBlend();
	}

	private static void line(VertexConsumer buffer, Matrix4f pose, float x0, float y0, float z0, float x1, float y1, float z1, int colour) {
		buffer.vertex(pose, x0, y0, z0).color(colour).endVertex();
		buffer.vertex(pose, x1, y1, z1).color(colour).endVertex();
	}

	// Building

	/**
	 * Has the given model built, off this thread. A textured model of anything but a small structure
	 * takes long enough to be worth a rough model first, in flat colours, which lands in a fraction of
	 * the time and stands in until the textured one does.
	 */
	private void startBuild(Wanted wanted) {
		final int myGeneration = generation.incrementAndGet();
		inFlight = wanted;
		inFlightSince = Util.getMillis();
		progress.set(0);
		if (standIn != null && !standIn.wanted.equals(wanted)) {
			standIn.close();
			standIn = null;
		}

		// A model of the same structure cut at the same height, whichever way it is drawn, shows the
		// right shape already, so nothing rough is worth building to stand in front of it
		final boolean nothingShown = current == null || current.wanted.preview() != wanted.preview();
		final boolean sameShapeShown = !nothingShown && current.wanted.layers() == wanted.layers();
		final boolean wantStandIn = wanted.mode() == PreviewMeshBuilder.Mode.TEXTURED && !sameShapeShown && (nothingShown || wanted.preview().getCellCount() > QUICK_BUILD_CELLS);
		final Biome biome = biomeForColours();
		final BooleanSupplier cancelled = () -> generation.get() != myGeneration;

		BUILDER.execute(() -> {
			if (cancelled.getAsBoolean()) {
				return;
			}
			try {
				final PreviewBlockGetter getter = new PreviewBlockGetter(wanted.preview(), wanted.layers(), biome);
				if (wantStandIn) {
					final PreviewMeshBuilder.Result rough = PreviewMeshBuilder.build(PreviewMeshBuilder.Mode.COLOURED, wanted.preview(), getter, cancelled, (permille) -> {
					});
					if (rough != null) {
						deliver(new Delivery(myGeneration, wanted, rough));
					}
				}
				final PreviewMeshBuilder.Result result = PreviewMeshBuilder.build(wanted.mode(), wanted.preview(), getter, cancelled, progress::set);
				if (result != null) {
					deliver(new Delivery(myGeneration, wanted, result));
				}
			} catch (Throwable t) {
				ExplorersCompass.LOGGER.warn("Could not build the model of a structure preview", t);
				deliver(new Delivery(myGeneration, wanted, null));
			}
		});
	}

	/** Takes in what a build sent back. Called from the builder thread; anything no longer wanted is freed here. */
	private void deliver(Delivery delivery) {
		synchronized (deliveries) {
			if (delivery.generation() == generation.get()) {
				deliveries.add(delivery);
				return;
			}
		}
		if (delivery.result() != null) {
			delivery.result().close();
		}
	}

	/** Puts whatever the builder has sent back onto the graphics card. Called on the render thread. */
	private void takeDeliveries() {
		final List<Delivery> taken;
		synchronized (deliveries) {
			if (deliveries.isEmpty()) {
				return;
			}
			taken = new ArrayList<Delivery>(deliveries);
			deliveries.clear();
		}

		for (Delivery delivery : taken) {
			if (delivery.generation() != generation.get() || !delivery.wanted().equals(inFlight)) {
				if (delivery.result() != null) {
					delivery.result().close();
				}
				continue;
			}
			if (delivery.result() == null) {
				failed = delivery.wanted();
				inFlight = null;
				continue;
			}

			final Uploaded uploaded = upload(delivery.wanted(), delivery.result());
			if (delivery.result().mode == delivery.wanted().mode()) {
				if (current != null) {
					current.close();
				}
				current = uploaded;
				if (standIn != null) {
					standIn.close();
					standIn = null;
				}
				inFlight = null;
				if (delivery.result().tookMillis >= 250L) {
					ExplorersCompass.LOGGER.debug("Built a structure preview of {} cells and {} faces in {}ms", uploaded.cells, uploaded.quads, delivery.result().tookMillis);
				}
			} else {
				if (standIn != null) {
					standIn.close();
				}
				standIn = uploaded;
			}
		}
	}

	/** Hands a built model to the graphics card, and bakes the blocks with renderers of their own alongside it. */
	private Uploaded upload(Wanted wanted, PreviewMeshBuilder.Result result) {
		final Uploaded uploaded = new Uploaded(wanted, result.mode, result.quads, result.cells);
		for (PreviewMeshBuilder.Layer layer : result.layers) {
			final VertexBuffer buffer = new VertexBuffer();
			buffer.bind();
			// Uploading releases the vertices; the builder they were written into goes back to the pool here
			buffer.upload(layer.rendered);
			VertexBuffer.unbind();
			layer.recycle();
			switch (layer.kind) {
				case OPAQUE:
					uploaded.opaque = buffer;
					break;
				case TRANSLUCENT:
					uploaded.translucent = buffer;
					break;
				default:
					uploaded.coloured = buffer;
					break;
			}
		}
		if (result.mode == PreviewMeshBuilder.Mode.TEXTURED) {
			bakeComponents(uploaded, wanted.preview(), wanted.layers());
		}
		return uploaded;
	}

	/**
	 * Runs each of the blocks that has a renderer of its own through that renderer once, into buffers
	 * kept by render type, so that a chest is drawn as a chest rather than as the hole its model would
	 * leave, and then drawn from a buffer like everything else.
	 *
	 * <p>Each is given the client's own level, which is what has vanilla's renderers read the block
	 * state they were built for — which way a chest faces, which half of a bed a block is — instead
	 * of falling back to the shape they use for an item. What that level holds at the position a
	 * preview places them is nothing to do with the structure, but nothing here asks it for anything
	 * that would show.
	 */
	private static void bakeComponents(Uploaded target, StructurePreview preview, int layerLimit) {
		if (preview.getComponentCount() == 0) {
			return;
		}
		final Minecraft mc = Minecraft.getInstance();
		final BlockEntityRenderDispatcher dispatcher = mc.getBlockEntityRenderDispatcher();
		final CapturingBufferSource buffers = new CapturingBufferSource();
		final PoseStack stack = new PoseStack();
		int baked = 0;

		for (int component = 0; component < preview.getComponentCount() && baked < MAX_BAKED_COMPONENTS; component++) {
			final int y = preview.getComponentY(component);
			if (y >= layerLimit) {
				continue;
			}
			final BlockState state = preview.getComponentState(component);
			if (!(state.getBlock() instanceof EntityBlock entityBlock)) {
				continue;
			}

			final int x = preview.getComponentX(component);
			final int z = preview.getComponentZ(component);
			try {
				final BlockEntity entity = entityBlock.newBlockEntity(new BlockPos(x, y, z), state);
				if (entity == null) {
					continue;
				}
				final BlockEntityRenderer<BlockEntity> renderer = dispatcher.getRenderer(entity);
				if (renderer == null) {
					continue;
				}
				if (mc.level != null) {
					entity.setLevel(mc.level);
				}
				stack.pushPose();
				stack.translate(x, y, z);
				renderer.render(entity, 0.0F, stack, buffers, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
				stack.popPose();
				baked++;
			} catch (Throwable t) {
				// One renderer that cannot draw outside a real world costs its own block alone
				while (!stack.clear()) {
					stack.popPose();
				}
			}
		}

		target.components = buffers.finish();
		target.componentCount = baked;
	}

	/**
	 * Takes in whatever the renderers draw, one buffer to a render type, and hands the lot to the
	 * graphics card at the end. The one render type that is swapped out is the one items are drawn
	 * with under fancy graphics, which writes into a buffer of the world's rather than the screen.
	 * The builders come out of the same pool the models are built in, and go back to it once uploaded.
	 */
	private static final class CapturingBufferSource implements MultiBufferSource {

		private final Map<RenderType, BufferBuilder> builders = new LinkedHashMap<RenderType, BufferBuilder>();

		@Override
		public VertexConsumer getBuffer(RenderType renderType) {
			final RenderType type = renderType == Sheets.translucentItemSheet() ? Sheets.translucentCullBlockSheet() : renderType;
			BufferBuilder builder = builders.get(type);
			if (builder == null) {
				builder = PreviewMeshBuilder.BufferPool.borrow(COMPONENT_BUFFER_BYTES);
				builder.begin(type.mode(), type.format());
				builders.put(type, builder);
			}
			return builder;
		}

		List<BakedComponents> finish() {
			final List<BakedComponents> baked = new ArrayList<BakedComponents>();
			for (Map.Entry<RenderType, BufferBuilder> entry : builders.entrySet()) {
				final BufferBuilder builder = entry.getValue();
				try {
					// A renderer that threw halfway through a vertex leaves every vertex after it read
					// shifted, which the builder does not notice for itself
					if (builder.currentElement() != entry.getKey().format().getElements().get(0)) {
						continue;
					}
					final BufferBuilder.RenderedBuffer rendered = builder.endOrDiscardIfEmpty();
					if (rendered != null) {
						final VertexBuffer buffer = new VertexBuffer();
						buffer.bind();
						buffer.upload(rendered);
						VertexBuffer.unbind();
						baked.add(new BakedComponents(entry.getKey(), buffer));
					}
				} catch (RuntimeException e) {
					// A renderer that left a vertex half written leaves its render type out
				} finally {
					PreviewMeshBuilder.BufferPool.giveBack(builder);
				}
			}
			builders.clear();
			return baked;
		}

	}

	/**
	 * The biome the grass and the leaves take their colours from. The plains, where the world has
	 * them, so that a structure looks the same wherever it is looked at from; whatever the player is
	 * standing in otherwise.
	 */
	private static Biome biomeForColours() {
		final ClientLevel level = Minecraft.getInstance().level;
		if (level == null) {
			return null;
		}
		try {
			final Biome plains = level.registryAccess().registryOrThrow(Registry.BIOME_REGISTRY).get(Biomes.PLAINS);
			if (plains != null) {
				return plains;
			}
			if (Minecraft.getInstance().player != null) {
				return level.getBiome(Minecraft.getInstance().player.blockPosition()).value();
			}
		} catch (RuntimeException e) {
			// A world without a biome registry to speak of colours its grass the default way
		}
		return null;
	}

	/**
	 * Frees everything held on the graphics card, and lets go of any build still on its way. The
	 * screen showing this calls it when it closes: what is held here is memory on the graphics card,
	 * which nothing else would ever give back.
	 */
	@Override
	public void close() {
		generation.incrementAndGet();
		inFlight = null;
		synchronized (deliveries) {
			for (Delivery delivery : deliveries) {
				if (delivery.result() != null) {
					delivery.result().close();
				}
			}
			deliveries.clear();
		}
		if (current != null) {
			current.close();
			current = null;
		}
		if (standIn != null) {
			standIn.close();
			standIn = null;
		}
		shownLastFrame = null;
	}

	/** Empties the depth buffer over whatever the scissor currently confines drawing to. */
	private static void clearDepthInsideThePanel() {
		RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
	}

	/**
	 * Points the two lights the baked renderers are shaded by, turned so that they stand still while
	 * the model turns.
	 *
	 * <p>The shaders those renderers draw through shade against the normals as they were built into
	 * the buffer, without putting them through the transform that turns the model. That is part of
	 * what makes a model worth building once — nothing in the buffer has to change as it is turned —
	 * but it also means the shading would turn along with it, and read as a lamp bolted to the
	 * building rather than as daylight. Turning the lights the other way instead costs two vectors a
	 * frame and leaves the light where light belongs.
	 */
	private void setupTurnedLighting() {
		RenderSystem.setShaderLights(lightTurnedBack(0.2F, 1.0F, -0.7F), lightTurnedBack(-0.2F, 1.0F, 0.7F));
	}

	/**
	 * One of the two directions the game lights the world from, turned by the opposite of however the
	 * model is currently turned. Undone innermost first: the model is turned about its own upright
	 * and then tilted, so a light standing still is tilted back and then turned back.
	 */
	private Vector3f lightTurnedBack(float x, float y, float z) {
		final Vector3f light = new Vector3f(x, y, z);
		light.transform(Vector3f.XP.rotationDegrees(-pitch));
		light.transform(Vector3f.YP.rotationDegrees(-yaw));
		return light;
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
		if (dragging || panning || !ConfigHandler.CLIENT.structurePreviewAutoSpin.get() || sinceLastFrame <= 0L || sinceLastFrame > MAX_FRAME_MILLIS) {
			return;
		}
		yaw += SPIN_DEGREES_PER_SECOND * sinceLastFrame / 1000.0F;
	}

}
