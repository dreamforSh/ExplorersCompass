package com.chaosthedude.explorerscompass.gui;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.opengl.GL11;

import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.chaosthedude.explorerscompass.preview.StructurePreview;
import com.chaosthedude.explorerscompass.util.RenderUtils;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Matrix4f;
import com.mojang.math.Vector3f;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.model.data.ModelData;

/**
 * Draws a structure preview, and holds where it is being looked at from.
 *
 * <p>What is drawn is built once into a buffer on the graphics card and then only drawn again.
 * Turning it and zooming it are a transform on a model that has already been built, so neither
 * costs anything; only taking layers off it, which changes what there is to draw, builds it again.
 *
 * <p>How it is built depends on how much of it there is:
 *
 * <ul>
 * <li><b>Up to the detail limit</b>, every cell is the block it stands for, drawn from that block's
 * own model with its own textures. This is what a preview is for, and it is also what costs: a few
 * thousand block models take a moment to assemble, and every one of them past that adds to the wait
 * before the preview appears.
 * <li><b>Past it</b>, every cell is a plain cube in the colour that block shows on a map, with the
 * faces between neighbouring cells left out and the remaining ones shaded by which way they face.
 * A cube is a few vertices and no texture, so a structure of any size can be shown this way. What
 * is lost is the materials; what is kept is the shape, the massing and the colours.
 * </ul>
 *
 * <p>The chests, beds, banners and signs are drawn apart from both: they have no usable block model
 * and are drawn instead by a renderer of their own, live on every frame, after the built model so
 * that the depth buffer composes the two. The coloured tier draws them as cubes like everything
 * else.
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
 * here, against the projection's, is what makes two. The flip is put on the interface's own matrix
 * rather than on the stack the model is turned on, which is where the game puts it when it draws an
 * item in an inventory, and which keeps it away from the normals the shading is worked out from.
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
	/**
	 * Roughly what a cell comes to in the buffer, so that one large enough is asked for outright. A
	 * preview shown one cell to one block runs to tens of megabytes of vertices, and a buffer that
	 * has to double its way up to that copies everything it holds each time it does.
	 */
	private static final int BYTES_PER_COLOURED_CELL = 208;
	private static final int BYTES_PER_MODELLED_CELL = 900;
	private static final int MIN_BUFFER_BYTES = 1 << 18;
	/**
	 * How many of the blocks with a renderer of their own are drawn live. Each one is drawn from
	 * scratch on every frame, which is what a built model exists to avoid, so the number of them is
	 * held to what a building shows on its outside.
	 */
	private static final int MAX_LIVE_COMPONENTS = 256;
	/** What a block with no colour of its own is drawn as, so that it is still visible as something. */
	private static final int UNKNOWN_COLOR = 0x9A9A9A;

	/** How much of its colour each face of a cube keeps, which is what gives a flat colour its shape. */
	private static final float SHADE_TOP = 1.0F;
	private static final float SHADE_BOTTOM = 0.5F;
	private static final float SHADE_NORTH_SOUTH = 0.8F;
	private static final float SHADE_EAST_WEST = 0.6F;

	/** One of the blocks drawn by a renderer of its own, and where in the model it stands. */
	private record PlacedComponent(BlockPos pos, BlockEntity entity) {
	}

	private float yaw = DEFAULT_YAW;
	private float pitch = DEFAULT_PITCH;
	private float zoom = DEFAULT_ZOOM;
	/** How many layers are drawn, counted from the ground up, which is how to look inside. */
	private int visibleLayers = Integer.MAX_VALUE;
	private boolean dragging;
	private long lastFrameAt;

	/** The built model, and what it was built from, so that it is only ever built again when it must be. */
	private VertexBuffer geometry;
	private boolean simplified;
	private StructurePreview builtFrom;
	private int builtLayers;
	private final List<PlacedComponent> components = new ArrayList<PlacedComponent>();

	/** Whether what is being drawn is coloured cubes rather than the blocks themselves. */
	public boolean isSimplified() {
		return simplified;
	}

	/** How many of the blocks with a renderer of their own are being drawn. */
	public int getDrawnComponents() {
		return components.size();
	}

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

	public void render(StructurePreview preview, int left, int top, int right, int bottom) {
		advanceSpin();

		final int panelWidth = right - left;
		final int panelHeight = bottom - top;
		// A structure can be nothing but a chest, so what makes a preview empty is holding neither a
		// shell nor anything with a renderer of its own
		if (panelWidth <= 0 || panelHeight <= 0 || (preview.getCellCount() == 0 && preview.getComponentCount() == 0)) {
			return;
		}

		final int layerLimit = layersShown(preview);
		if (preview != builtFrom || layerLimit != builtLayers) {
			build(preview, layerLimit);
		}
		if (geometry == null && components.isEmpty()) {
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
		screenStack.translate(left + panelWidth / 2.0D, top + panelHeight / 2.0D, MODEL_DEPTH);
		screenStack.scale(scale, -scale, scale);
		RenderSystem.applyModelViewMatrix();

		// How the model is turned, on a stack of its own, so that what it does to the normals is only
		// ever a rotation
		final PoseStack modelStack = new PoseStack();
		modelStack.mulPose(Vector3f.XP.rotationDegrees(pitch));
		modelStack.mulPose(Vector3f.YP.rotationDegrees(yaw));
		modelStack.translate(-gridX / 2.0F, -gridY / 2.0F, -gridZ / 2.0F);

		if (geometry != null) {
			if (simplified) {
				drawColouredCubes(modelStack);
			} else {
				drawBlockModels(modelStack);
			}
		}
		if (!components.isEmpty()) {
			drawComponents(modelStack);
		}

		screenStack.popPose();
		RenderSystem.applyModelViewMatrix();

		clearDepthInsideThePanel();
		RenderSystem.disableDepthTest();
		RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
		RenderUtils.disableScissor();
	}

	/** The matrix the built model is drawn through: where the panel is, and how the model is turned. */
	private static Matrix4f modelViewFor(PoseStack modelStack) {
		final Matrix4f modelView = RenderSystem.getModelViewMatrix().copy();
		modelView.multiply(modelStack.last().pose());
		return modelView;
	}

	/** Draws the tier built from the blocks' own models, under the lighting an inventory uses. */
	private void drawBlockModels(PoseStack modelStack) {
		setupTurnedLighting();
		final RenderType renderType = Sheets.cutoutBlockSheet();
		renderType.setupRenderState();
		geometry.bind();
		geometry.drawWithShader(modelViewFor(modelStack), RenderSystem.getProjectionMatrix(), RenderSystem.getShader());
		VertexBuffer.unbind();
		renderType.clearRenderState();
		Lighting.setupFor3DItems();
	}

	/**
	 * Draws the tier built from coloured cubes. The shading is already in the colours the cubes were
	 * built with, so this needs no lighting and no texture: position and colour is the whole of it.
	 */
	private void drawColouredCubes(PoseStack modelStack) {
		RenderSystem.setShader(GameRenderer::getPositionColorShader);
		RenderSystem.disableBlend();
		RenderSystem.enableCull();
		RenderSystem.enableDepthTest();
		RenderSystem.depthFunc(GL11.GL_LEQUAL);
		RenderSystem.depthMask(true);
		geometry.bind();
		geometry.drawWithShader(modelViewFor(modelStack), RenderSystem.getProjectionMatrix(), RenderSystem.getShader());
		VertexBuffer.unbind();
	}

	/**
	 * Draws the chests, beds, banners and signs, each through the renderer that owns it. These cannot
	 * be built into the model — their models are put together as they are drawn, from atlases of their
	 * own — so they are drawn from scratch every frame, after the built model so that the depth
	 * buffer puts each of them where it belongs among the blocks around it.
	 */
	private void drawComponents(PoseStack modelStack) {
		final Minecraft mc = Minecraft.getInstance();
		// These are drawn from normals the stack has already turned, unlike the built model, so they
		// want the lighting that stands still in front of the viewer rather than the turned one
		Lighting.setupForEntityInInventory();
		final BlockEntityRenderDispatcher dispatcher = mc.getBlockEntityRenderDispatcher();
		final MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
		for (PlacedComponent component : components) {
			modelStack.pushPose();
			modelStack.translate(component.pos().getX(), component.pos().getY(), component.pos().getZ());
			try {
				final BlockEntityRenderer<BlockEntity> renderer = dispatcher.getRenderer(component.entity());
				if (renderer != null) {
					renderer.render(component.entity(), 0.0F, modelStack, buffers, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
				}
			} catch (Throwable t) {
				// One renderer that cannot draw outside a real world must not take the preview with it
			}
			modelStack.popPose();
		}
		buffers.endBatch();
		Lighting.setupFor3DItems();
	}

	/**
	 * Builds the model into a buffer on the graphics card, in whichever of the two ways this many
	 * cells calls for. The cells are laid out where they stand in the preview and nowhere else: how
	 * the model is turned and how large it is drawn are a transform applied to the built model, so
	 * that neither of them is a reason to build it again.
	 */
	private void build(StructurePreview preview, int layerLimit) {
		close();
		builtFrom = preview;
		builtLayers = layerLimit;
		simplified = preview.getCellCount() > ConfigHandler.CLIENT.structurePreviewDetailLimit.get();

		final int drawnCells = Math.max(1, preview.getCellCount() + preview.getComponentCount());
		final int bytesPerCell = simplified ? BYTES_PER_COLOURED_CELL : BYTES_PER_MODELLED_CELL;
		final BufferBuilder builder = new BufferBuilder(Math.max(MIN_BUFFER_BYTES, drawnCells * bytesPerCell));
		if (simplified) {
			buildColouredCubes(builder, preview, layerLimit);
		} else {
			buildBlockModels(builder, preview, layerLimit);
			buildComponents(preview, layerLimit);
		}

		final BufferBuilder.RenderedBuffer rendered = builder.endOrDiscardIfEmpty();
		if (rendered == null) {
			return;
		}

		geometry = new VertexBuffer();
		geometry.bind();
		geometry.upload(rendered);
		VertexBuffer.unbind();
	}

	/** Puts every cell into the buffer as the block it stands for, drawn from that block's own model. */
	private void buildBlockModels(BufferBuilder builder, StructurePreview preview, int layerLimit) {
		final BlockRenderDispatcher blockRenderer = Minecraft.getInstance().getBlockRenderer();
		final RenderType renderType = Sheets.cutoutBlockSheet();
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
	}

	/**
	 * Puts every cell into the buffer as a plain cube in the colour its block shows on a map. Only the
	 * faces with nothing on the other side of them are put in, which on a shell takes most of them
	 * away, and each is shaded by which way it faces so that the shape reads without any lighting.
	 */
	private void buildColouredCubes(BufferBuilder builder, StructurePreview preview, int layerLimit) {
		final IntOpenHashSet filled = new IntOpenHashSet(preview.getCellCount() + preview.getComponentCount());
		for (int cell = 0; cell < preview.getCellCount(); cell++) {
			if (preview.getCellY(cell) < layerLimit) {
				filled.add(preview.getCellPosition(cell));
			}
		}
		for (int component = 0; component < preview.getComponentCount(); component++) {
			if (preview.getComponentY(component) < layerLimit) {
				filled.add(preview.getComponentPosition(component));
			}
		}

		// Resolved once for each distinct block rather than once for each cell holding one
		final int[] paletteColours = new int[preview.getPaletteSize()];
		for (int entry = 0; entry < paletteColours.length; entry++) {
			paletteColours[entry] = colourOf(preview.getPaletteState(entry));
		}

		builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
		for (int cell = 0; cell < preview.getCellCount(); cell++) {
			final int y = preview.getCellY(cell);
			if (y < layerLimit) {
				emitCube(builder, preview, filled, layerLimit, preview.getCellX(cell), y, preview.getCellZ(cell), paletteColours[preview.getCellPaletteIndex(cell)]);
			}
		}
		// The coloured tier has no renderers of its own, so the chests and beds are cubes like the rest
		for (int component = 0; component < preview.getComponentCount(); component++) {
			final int y = preview.getComponentY(component);
			if (y < layerLimit) {
				emitCube(builder, preview, filled, layerLimit, preview.getComponentX(component), y, preview.getComponentZ(component), colourOf(preview.getComponentState(component)));
			}
		}
	}

	/** The colour a block shows on a map, which is the one colour every block already has to its name. */
	private static int colourOf(BlockState state) {
		final int colour = state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col;
		return colour == 0 ? UNKNOWN_COLOR : colour;
	}

	/** Puts the faces of one cube into the buffer, leaving out any with another cube against it. */
	private static void emitCube(BufferBuilder builder, StructurePreview preview, IntOpenHashSet filled, int layerLimit, int x, int y, int z, int colour) {
		final float x0 = x;
		final float y0 = y;
		final float z0 = z;
		final float x1 = x + 1.0F;
		final float y1 = y + 1.0F;
		final float z1 = z + 1.0F;

		if (isOpen(preview, filled, layerLimit, x, y + 1, z)) {
			final int shaded = shade(colour, SHADE_TOP);
			vertex(builder, x0, y1, z0, shaded);
			vertex(builder, x0, y1, z1, shaded);
			vertex(builder, x1, y1, z1, shaded);
			vertex(builder, x1, y1, z0, shaded);
		}
		if (isOpen(preview, filled, layerLimit, x, y - 1, z)) {
			final int shaded = shade(colour, SHADE_BOTTOM);
			vertex(builder, x0, y0, z0, shaded);
			vertex(builder, x1, y0, z0, shaded);
			vertex(builder, x1, y0, z1, shaded);
			vertex(builder, x0, y0, z1, shaded);
		}
		if (isOpen(preview, filled, layerLimit, x, y, z - 1)) {
			final int shaded = shade(colour, SHADE_NORTH_SOUTH);
			vertex(builder, x0, y0, z0, shaded);
			vertex(builder, x0, y1, z0, shaded);
			vertex(builder, x1, y1, z0, shaded);
			vertex(builder, x1, y0, z0, shaded);
		}
		if (isOpen(preview, filled, layerLimit, x, y, z + 1)) {
			final int shaded = shade(colour, SHADE_NORTH_SOUTH);
			vertex(builder, x0, y0, z1, shaded);
			vertex(builder, x1, y0, z1, shaded);
			vertex(builder, x1, y1, z1, shaded);
			vertex(builder, x0, y1, z1, shaded);
		}
		if (isOpen(preview, filled, layerLimit, x - 1, y, z)) {
			final int shaded = shade(colour, SHADE_EAST_WEST);
			vertex(builder, x0, y0, z0, shaded);
			vertex(builder, x0, y0, z1, shaded);
			vertex(builder, x0, y1, z1, shaded);
			vertex(builder, x0, y1, z0, shaded);
		}
		if (isOpen(preview, filled, layerLimit, x + 1, y, z)) {
			final int shaded = shade(colour, SHADE_EAST_WEST);
			vertex(builder, x1, y0, z0, shaded);
			vertex(builder, x1, y1, z0, shaded);
			vertex(builder, x1, y1, z1, shaded);
			vertex(builder, x1, y0, z1, shaded);
		}
	}

	/** Whether there is nothing in the given cell, which is what makes the face towards it worth drawing. */
	private static boolean isOpen(StructurePreview preview, IntOpenHashSet filled, int layerLimit, int x, int y, int z) {
		if (x < 0 || y < 0 || z < 0 || x >= preview.getGridX() || y >= preview.getGridY() || z >= preview.getGridZ() || y >= layerLimit) {
			return true;
		}
		return !filled.contains(StructurePreview.pack(x, y, z));
	}

	private static void vertex(BufferBuilder builder, float x, float y, float z, int colour) {
		builder.vertex(x, y, z).color((colour >> 16) & 255, (colour >> 8) & 255, colour & 255, 255).endVertex();
	}

	/** Takes a colour down towards black, which is what stands in for lighting on a flat colour. */
	private static int shade(int colour, float factor) {
		final int red = Mth.clamp((int) (((colour >> 16) & 255) * factor), 0, 255);
		final int green = Mth.clamp((int) (((colour >> 8) & 255) * factor), 0, 255);
		final int blue = Mth.clamp((int) ((colour & 255) * factor), 0, 255);
		return (red << 16) | (green << 8) | blue;
	}

	/**
	 * Builds the block entity behind each of the blocks that has a renderer of its own, so that a
	 * chest is drawn as a chest rather than as the hole its model would leave.
	 *
	 * <p>Each is given the client's own level, which is what has vanilla's renderers read the block
	 * state they were built for — which way a chest faces, which half of a bed a block is — instead
	 * of falling back to the shape they use for an item. What that level holds at the position a
	 * preview places them is nothing to do with the structure, but nothing here asks it for anything
	 * that would show.
	 */
	private void buildComponents(StructurePreview preview, int layerLimit) {
		final Minecraft mc = Minecraft.getInstance();
		final BlockEntityRenderDispatcher dispatcher = mc.getBlockEntityRenderDispatcher();
		for (int component = 0; component < preview.getComponentCount() && components.size() < MAX_LIVE_COMPONENTS; component++) {
			final int y = preview.getComponentY(component);
			if (y >= layerLimit) {
				continue;
			}

			final BlockState state = preview.getComponentState(component);
			if (!(state.getBlock() instanceof EntityBlock entityBlock)) {
				continue;
			}

			final BlockPos pos = new BlockPos(preview.getComponentX(component), y, preview.getComponentZ(component));
			try {
				final BlockEntity entity = entityBlock.newBlockEntity(pos, state);
				if (entity == null || dispatcher.getRenderer(entity) == null) {
					continue;
				}
				if (mc.level != null) {
					entity.setLevel(mc.level);
				}
				components.add(new PlacedComponent(pos, entity));
			} catch (RuntimeException e) {
				// A block entity that cannot be built outside a real world costs its own detail alone
			}
		}
	}

	/**
	 * Frees the built model. The screen showing it calls this when it closes: what is held here is
	 * memory on the graphics card, which nothing else would ever give back.
	 */
	@Override
	public void close() {
		if (geometry != null) {
			geometry.close();
			geometry = null;
		}
		components.clear();
		builtFrom = null;
		builtLayers = 0;
		simplified = false;
	}

	/** Empties the depth buffer over whatever the scissor currently confines drawing to. */
	private static void clearDepthInsideThePanel() {
		RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
	}

	/**
	 * Points the two lights the built model is shaded by, turned so that they stand still while it
	 * turns.
	 *
	 * <p>The shader a block model is drawn through shades it against the normals as they were built
	 * into the buffer, without putting them through the transform that turns the model. That is part
	 * of what makes a model worth building once — nothing in the buffer has to change as it is turned
	 * — but it also means the shading would turn along with it, and read as a lamp bolted to the
	 * building rather than as daylight. Turning the lights the other way instead costs two vectors a
	 * frame and leaves the light where light belongs.
	 */
	private void setupTurnedLighting() {
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
