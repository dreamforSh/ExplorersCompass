package com.chaosthedude.explorerscompass.gui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.preview.StructurePreview;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.model.data.ModelData;

/**
 * Builds the model of a structure preview, off the render thread.
 *
 * <p>Nothing here touches the graphics card: what comes out is vertices in memory, which whoever
 * asked for them hands to the card once they are back on the thread that owns it. Everything used
 * along the way — the block models, the tesselator that turns them into faces, the colours — is
 * what the game itself builds its chunks out of on threads of its own, so all of it is safe to use
 * from another thread here too.
 *
 * <p>Two kinds of model are built:
 *
 * <ul>
 * <li>The <b>textured</b> model puts every cell in as the block it stands for, through the same
 * tesselator that draws blocks in the world: faces with a solid neighbour against them are left out,
 * corners are darkened where blocks meet, each face is shaded by which way it points, and the grass
 * and leaves take their colour from a biome. What does not fit in a single buffer is the blocks that
 * are see-through, which go in a buffer of their own so that they can be drawn last with blending.
 * <li>The <b>coloured</b> model puts every cell in as a plain cube in the colour its block shows on a
 * map, shaded the same two ways — by the way each face points, and by how tucked in each corner is —
 * so that the shape reads without any texture. A cube is a handful of vertices, so a structure of any
 * size builds this way in a fraction of a second.
 * </ul>
 *
 * <p>A build reports how far it has got and asks, every so often, whether it is still wanted, so
 * that one made stale by a change of what is being shown stops rather than finishing for nothing.
 *
 * <p>The buffers the vertices are written into are lent out of a pool and given back once the model
 * has been handed to the graphics card, rather than made afresh for every build: see
 * {@link BufferPool} for why.
 */
@OnlyIn(Dist.CLIENT)
final class PreviewMeshBuilder {

	/** What the model is drawn as. */
	enum Mode {
		TEXTURED,
		COLOURED
	}

	/** Which of the buffers of a built model one is, which is what decides how it is drawn. */
	enum Kind {
		OPAQUE,
		TRANSLUCENT,
		COLOURED
	}

	/** Roughly what a cell comes to in each format, so that a buffer large enough is asked for outright. */
	private static final int BYTES_PER_TEXTURED_CELL = 400;
	private static final int BYTES_PER_COLOURED_CELL = 160;
	private static final int MIN_BUFFER_BYTES = 1 << 18;
	private static final int MAX_INITIAL_BUFFER_BYTES = 48 << 20;
	/** How many cells go by between two looks at whether the build is still wanted. */
	private static final int CANCEL_CHECK_INTERVAL = 512;
	/** What a block with no colour of its own is drawn as, so that it is still visible as something. */
	private static final int UNKNOWN_COLOR = 0x9A9A9A;

	/** How much of its colour each face of a cube keeps, which is what gives a flat colour its shape. */
	private static final float SHADE_TOP = 1.0F;
	private static final float SHADE_BOTTOM = 0.5F;
	private static final float SHADE_NORTH_SOUTH = 0.8F;
	private static final float SHADE_EAST_WEST = 0.6F;
	/** How much a corner keeps by how many of the three cells around it are filled, most enclosed first. */
	private static final float[] CORNER_LIGHT = { 0.5F, 0.68F, 0.84F, 1.0F };

	/**
	 * The four corners of each face of a unit cube, by the face's 3D index, in the order that puts the
	 * face the right way round for the pipeline it is drawn through: counter-clockwise seen from
	 * outside the cube.
	 */
	private static final int[][][] FACE_CORNERS = {
			// DOWN
			{ { 0, 0, 0 }, { 1, 0, 0 }, { 1, 0, 1 }, { 0, 0, 1 } },
			// UP
			{ { 0, 1, 0 }, { 0, 1, 1 }, { 1, 1, 1 }, { 1, 1, 0 } },
			// NORTH
			{ { 0, 0, 0 }, { 0, 1, 0 }, { 1, 1, 0 }, { 1, 0, 0 } },
			// SOUTH
			{ { 0, 0, 1 }, { 1, 0, 1 }, { 1, 1, 1 }, { 0, 1, 1 } },
			// WEST
			{ { 0, 0, 0 }, { 0, 0, 1 }, { 0, 1, 1 }, { 0, 1, 0 } },
			// EAST
			{ { 1, 0, 0 }, { 1, 1, 0 }, { 1, 1, 1 }, { 1, 0, 1 } } };

	/**
	 * The builders vertices are written into, lent out and taken back rather than made for each build.
	 *
	 * <p>A builder allocates its memory off the heap the moment it is made, and never gives it back:
	 * the game makes its own builders once and keeps them for as long as it runs. One made for every
	 * build of a preview would leak its buffer each time — tens of megabytes for a large structure —
	 * so the same few are lent out in turn instead, which keeps the lot bounded by the largest model
	 * built. A builder given back is emptied first, so that whoever borrows it next starts clean.
	 */
	static final class BufferPool {

		/** More than could ever be out at once: the two or three of a build, and the few of a delivery awaiting upload. */
		private static final int MAX_POOLED = 8;
		private static final ArrayDeque<BufferBuilder> POOL = new ArrayDeque<BufferBuilder>();

		private BufferPool() {
		}

		/**
		 * A builder to write into, one from the pool where there is one and a new one otherwise.
		 *
		 * @param expectedBytes roughly how much is about to be written, so that a new builder is made
		 *                      large enough not to have to grow, copying everything it holds each time
		 */
		static BufferBuilder borrow(int expectedBytes) {
			synchronized (POOL) {
				final BufferBuilder pooled = POOL.pollFirst();
				if (pooled != null) {
					return pooled;
				}
			}
			// The constructor allocates six times what it is asked for
			return new BufferBuilder(Math.max(1, expectedBytes / 6));
		}

		/** Takes a builder back, emptying whatever was left in it, finished or not. */
		static void giveBack(BufferBuilder builder) {
			if (builder.building()) {
				final BufferBuilder.RenderedBuffer leftover = builder.endOrDiscardIfEmpty();
				if (leftover != null) {
					leftover.release();
				}
			}
			builder.discard();
			synchronized (POOL) {
				if (POOL.size() < MAX_POOLED) {
					POOL.addLast(builder);
				}
			}
		}

	}

	/**
	 * One buffer of a built model: the vertices, and the builder they were written into, which is kept
	 * until they have been handed to the graphics card and then given back to the pool.
	 */
	static final class Layer implements AutoCloseable {

		final Kind kind;
		final BufferBuilder builder;
		final BufferBuilder.RenderedBuffer rendered;
		private boolean done;

		private Layer(Kind kind, BufferBuilder builder, BufferBuilder.RenderedBuffer rendered) {
			this.kind = kind;
			this.builder = builder;
			this.rendered = rendered;
		}

		/** How many faces this buffer holds. */
		int quads() {
			return rendered.drawState().vertexCount() / 4;
		}

		/**
		 * Gives the builder back once the vertices have been uploaded. Uploading releases the rendered
		 * buffer itself, so this only has the builder left to see to.
		 */
		void recycle() {
			if (!done) {
				done = true;
				BufferPool.giveBack(builder);
			}
		}

		/** Throws the vertices away without uploading them, and gives the builder back. */
		@Override
		public void close() {
			if (!done) {
				done = true;
				rendered.release();
				BufferPool.giveBack(builder);
			}
		}

	}

	/** Everything one build produced. Closed by whoever takes it, whether or not they used it. */
	static final class Result implements AutoCloseable {

		final Mode mode;
		final List<Layer> layers;
		final int quads;
		final int cells;
		final long tookMillis;

		private Result(Mode mode, List<Layer> layers, int cells, long tookMillis) {
			this.mode = mode;
			this.layers = layers;
			this.cells = cells;
			this.tookMillis = tookMillis;
			int total = 0;
			for (Layer layer : layers) {
				total += layer.quads();
			}
			quads = total;
		}

		@Override
		public void close() {
			for (Layer layer : layers) {
				layer.close();
			}
		}

	}

	private PreviewMeshBuilder() {
	}

	/**
	 * Builds the given preview in the given way. Answers null when the build was called off before
	 * it finished, in which case everything it had allocated has already been given back.
	 *
	 * @param cancelled asked every so often; answering true stops the build
	 * @param progress  told how far along the build is, in thousandths
	 */
	static Result build(Mode mode, StructurePreview preview, PreviewBlockGetter getter, BooleanSupplier cancelled, IntConsumer progress) {
		final long startedAt = System.currentTimeMillis();
		final List<Layer> layers = new ArrayList<Layer>();
		final int cells;
		try {
			cells = mode == Mode.TEXTURED
					? buildTextured(preview, getter, layers, cancelled, progress)
					: buildColoured(preview, getter, layers, cancelled, progress);
		} catch (Throwable t) {
			for (Layer layer : layers) {
				layer.close();
			}
			throw t;
		}
		if (cells < 0) {
			for (Layer layer : layers) {
				layer.close();
			}
			return null;
		}
		return new Result(mode, layers, cells, System.currentTimeMillis() - startedAt);
	}

	/** A buffer in the making: the builder writing vertices into it, begun in the format it is for. */
	private static final class Sink implements AutoCloseable {

		final Kind kind;
		final VertexFormat format;
		final BufferBuilder builder;

		Sink(Kind kind, int bytes, VertexFormat format) {
			this.kind = kind;
			this.format = format;
			builder = BufferPool.borrow(bytes);
			builder.begin(VertexFormat.Mode.QUADS, format);
		}

		/**
		 * Finishes the buffer into a layer, or gives it back when nothing was ever written to it. A
		 * model that left its last vertex half written cannot be finished, and costs the whole buffer:
		 * every vertex after it would be read shifted by however much of it was written.
		 */
		Layer finish() {
			if (builder.currentElement() != format.getElements().get(0)) {
				ExplorersCompass.LOGGER.warn("A structure preview buffer was left with a vertex half written and was dropped");
				BufferPool.giveBack(builder);
				return null;
			}
			final BufferBuilder.RenderedBuffer rendered = builder.endOrDiscardIfEmpty();
			if (rendered == null) {
				BufferPool.giveBack(builder);
				return null;
			}
			return new Layer(kind, builder, rendered);
		}

		/** Gives the builder back without finishing, when the build is being thrown away. */
		@Override
		public void close() {
			BufferPool.giveBack(builder);
		}

	}

	private static int initialBytes(int cells, int bytesPerCell) {
		return (int) Mth.clamp((long) Math.max(1, cells) * bytesPerCell, MIN_BUFFER_BYTES, MAX_INITIAL_BUFFER_BYTES);
	}

	/**
	 * Puts every cell into the buffers as the block it stands for, through the tesselator that draws
	 * blocks in the world. Answers how many cells were put in, or -1 when the build was called off.
	 */
	private static int buildTextured(StructurePreview preview, PreviewBlockGetter getter, List<Layer> layers, BooleanSupplier cancelled, IntConsumer progress) {
		final Minecraft mc = Minecraft.getInstance();
		final BlockRenderDispatcher dispatcher = mc.getBlockRenderer();
		final ModelBlockRenderer modelRenderer = dispatcher.getModelRenderer();
		final RandomSource random = RandomSource.create();
		final PoseStack poseStack = new PoseStack();
		final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		final int layerLimit = getter.getLayerLimit();

		final Sink opaque = new Sink(Kind.OPAQUE, initialBytes(preview.getCellCount(), BYTES_PER_TEXTURED_CELL), DefaultVertexFormat.BLOCK);
		final Sink translucent = new Sink(Kind.TRANSLUCENT, MIN_BUFFER_BYTES, DefaultVertexFormat.BLOCK);
		// Only the cells the cut has opened up are worth putting in from the walled-in middle; every
		// other face of them is against a solid block and would be left out anyway
		final int exposedInterior = layerLimit < preview.getGridY() ? countInteriorAt(preview, layerLimit - 1) : 0;
		final int total = Math.max(1, preview.getCellCount() + exposedInterior);
		int done = 0;
		int failed = 0;

		// The tesselator keeps a small cache of the light and shade it has worked out for nearby
		// cells, which is worth having on while a whole model goes through it
		ModelBlockRenderer.enableCaching();
		try {
			for (int cell = 0; cell < preview.getCellCount(); cell++) {
				if ((cell & (CANCEL_CHECK_INTERVAL - 1)) == 0) {
					if (cancelled.getAsBoolean()) {
						return abandon(opaque, translucent);
					}
					progress.accept(done * 1000 / total);
				}
				final int y = preview.getCellY(cell);
				if (y >= layerLimit) {
					continue;
				}
				if (!tesselate(preview.getCellState(cell), preview.getCellX(cell), y, preview.getCellZ(cell), getter, dispatcher, modelRenderer, random, poseStack, pos, opaque.builder, translucent.builder)) {
					failed++;
				}
				done++;
			}

			if (exposedInterior > 0) {
				for (int run = 0; run < preview.getInteriorRunCount(); run++) {
					final int start = preview.getInteriorRunStart(run);
					final int length = preview.getInteriorRunLength(run);
					// A run never crosses a layer: the packing puts height in the top bits
					if (StructurePreview.unpackY(start) != layerLimit - 1) {
						continue;
					}
					final BlockState state = preview.getPaletteState(preview.getInteriorRunPaletteIndex(run));
					for (int offset = 0; offset < length; offset++) {
						final int packed = start + offset;
						final int y = StructurePreview.unpackY(packed);
						if (y != layerLimit - 1) {
							continue;
						}
						if (!tesselate(state, StructurePreview.unpackX(packed), y, StructurePreview.unpackZ(packed), getter, dispatcher, modelRenderer, random, poseStack, pos, opaque.builder, translucent.builder)) {
							failed++;
						}
						done++;
					}
					if ((run & 63) == 0) {
						if (cancelled.getAsBoolean()) {
							return abandon(opaque, translucent);
						}
						progress.accept(done * 1000 / total);
					}
				}
			}
		} finally {
			ModelBlockRenderer.clearCache();
		}

		if (failed > 0) {
			ExplorersCompass.LOGGER.debug("{} blocks could not be tesselated for a structure preview and were left out", failed);
		}
		finish(opaque, layers);
		finish(translucent, layers);
		return done;
	}

	/** How many walled-in cells lie in the given layer, which is what the cut there exposes. */
	private static int countInteriorAt(StructurePreview preview, int y) {
		int count = 0;
		for (int run = 0; run < preview.getInteriorRunCount(); run++) {
			if (StructurePreview.unpackY(preview.getInteriorRunStart(run)) == y) {
				count += preview.getInteriorRunLength(run);
			}
		}
		return count;
	}

	/**
	 * Puts one block through the tesselator, into whichever buffer each of its render types belongs
	 * in. Answers whether it could be drawn: a model from a mod may not be able to build itself out
	 * here, and one that cannot costs its own cell and nothing else.
	 */
	private static boolean tesselate(BlockState state, int x, int y, int z, PreviewBlockGetter getter, BlockRenderDispatcher dispatcher, ModelBlockRenderer modelRenderer, RandomSource random, PoseStack poseStack, BlockPos.MutableBlockPos pos, BufferBuilder opaque, BufferBuilder translucent) {
		// Anything not drawn from a model was left out when the preview was built; this is the guard
		// for a block whose id means something else on this side of the connection
		if (state.getRenderShape() != RenderShape.MODEL) {
			return true;
		}
		pos.set(x, y, z);
		try {
			final BakedModel model = dispatcher.getBlockModel(state);
			final ModelData modelData = model.getModelData(getter, pos, state, ModelData.EMPTY);
			final long seed = state.getSeed(pos);
			random.setSeed(seed);
			for (RenderType renderType : model.getRenderTypes(state, random, modelData)) {
				final BufferBuilder target = isTranslucent(renderType) ? translucent : opaque;
				poseStack.pushPose();
				poseStack.translate(x, y, z);
				// The tesselator leaves out every face with a solid block against it, darkens the
				// corners, shades each face by the way it points and colours the tinted ones, all by
				// asking the getter what stands around the block
				modelRenderer.tesselateBlock(getter, model, state, pos, poseStack, target, true, random, seed, OverlayTexture.NO_OVERLAY, modelData, renderType);
				poseStack.popPose();
			}
			return true;
		} catch (Throwable t) {
			// The pose is only ever left pushed by a throw from inside the tesselator
			while (!poseStack.clear()) {
				poseStack.popPose();
			}
			return false;
		}
	}

	/** Whether a render type is one of the two the world draws last, with blending. */
	private static boolean isTranslucent(RenderType renderType) {
		return renderType == RenderType.translucent() || renderType == RenderType.tripwire();
	}

	/**
	 * Puts every cell into the buffer as a plain cube in the colour its block shows on a map. Only the
	 * faces with nothing solid on the other side of them are put in, which on a shell takes most of
	 * them away, and each corner is darkened by how many of the cells around it are filled. Answers
	 * how many cells were put in, or -1 when the build was called off.
	 */
	private static int buildColoured(StructurePreview preview, PreviewBlockGetter getter, List<Layer> layers, BooleanSupplier cancelled, IntConsumer progress) {
		final int layerLimit = getter.getLayerLimit();
		final Sink sink = new Sink(Kind.COLOURED, initialBytes(preview.getCellCount() + preview.getComponentCount(), BYTES_PER_COLOURED_CELL), DefaultVertexFormat.POSITION_COLOR);

		// Resolved once for each distinct block rather than once for each cell holding one
		final int[] paletteColours = new int[preview.getPaletteSize()];
		for (int entry = 0; entry < paletteColours.length; entry++) {
			paletteColours[entry] = colourOf(preview.getPaletteState(entry));
		}

		final int total = Math.max(1, preview.getCellCount() + preview.getComponentCount());
		int done = 0;

		for (int cell = 0; cell < preview.getCellCount(); cell++) {
			if ((cell & (CANCEL_CHECK_INTERVAL - 1)) == 0) {
				if (cancelled.getAsBoolean()) {
					return abandon(sink);
				}
				progress.accept(done * 1000 / total);
			}
			final int y = preview.getCellY(cell);
			if (y >= layerLimit) {
				continue;
			}
			emitCube(sink.builder, getter, preview.getCellX(cell), y, preview.getCellZ(cell), paletteColours[preview.getCellPaletteIndex(cell)]);
			done++;
		}

		// The coloured tier has no renderers of its own, so the chests and beds are cubes like the rest
		for (int component = 0; component < preview.getComponentCount(); component++) {
			final int y = preview.getComponentY(component);
			if (y >= layerLimit) {
				continue;
			}
			emitCube(sink.builder, getter, preview.getComponentX(component), y, preview.getComponentZ(component), colourOf(preview.getComponentState(component)));
			done++;
		}

		// What the cut has opened up of the walled-in middle shows only its top, so only that is put in
		if (layerLimit < preview.getGridY()) {
			for (int run = 0; run < preview.getInteriorRunCount(); run++) {
				final int start = preview.getInteriorRunStart(run);
				if (StructurePreview.unpackY(start) != layerLimit - 1) {
					continue;
				}
				final int colour = paletteColours[preview.getInteriorRunPaletteIndex(run)];
				for (int offset = 0; offset < preview.getInteriorRunLength(run); offset++) {
					final int packed = start + offset;
					final int y = StructurePreview.unpackY(packed);
					if (y == layerLimit - 1) {
						emitFace(sink.builder, getter, StructurePreview.unpackX(packed), y, StructurePreview.unpackZ(packed), Direction.UP, colour);
					}
				}
				if ((run & 63) == 0 && cancelled.getAsBoolean()) {
					return abandon(sink);
				}
			}
		}

		finish(sink, layers);
		return done;
	}

	private static void finish(Sink sink, List<Layer> layers) {
		final Layer layer = sink.finish();
		if (layer != null) {
			layers.add(layer);
		}
	}

	private static int abandon(Sink... sinks) {
		for (Sink sink : sinks) {
			sink.close();
		}
		return -1;
	}

	/** The colour a block shows on a map, which is the one colour every block already has to its name. */
	static int colourOf(BlockState state) {
		final int colour = state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col;
		return colour == 0 ? UNKNOWN_COLOR : colour;
	}

	/** Puts the faces of one cube into the buffer, leaving out any with a solid cube against it. */
	private static void emitCube(BufferBuilder builder, PreviewBlockGetter getter, int x, int y, int z, int colour) {
		for (Direction direction : Direction.values()) {
			if (!getter.isOccluding(x + direction.getStepX(), y + direction.getStepY(), z + direction.getStepZ())) {
				emitFace(builder, getter, x, y, z, direction, colour);
			}
		}
	}

	/**
	 * Puts one face of a cube into the buffer, each of its corners darkened by how many of the three
	 * cells that meet at that corner, on the far side of the face, are filled. That is what the world
	 * does to the corners of its own blocks, and it is what makes a flat colour read as a solid.
	 */
	private static void emitFace(BufferBuilder builder, PreviewBlockGetter getter, int x, int y, int z, Direction direction, int colour) {
		final int shaded = shade(colour, shadeFor(direction));
		final int[][] corners = FACE_CORNERS[direction.get3DDataValue()];
		final int nx = x + direction.getStepX();
		final int ny = y + direction.getStepY();
		final int nz = z + direction.getStepZ();
		final Direction.Axis axis = direction.getAxis();

		for (int[] corner : corners) {
			// Which way this corner lies from the middle of the face, along each of the face's two axes
			final int ox = axis == Direction.Axis.X ? 0 : (corner[0] == 1 ? 1 : -1);
			final int oy = axis == Direction.Axis.Y ? 0 : (corner[1] == 1 ? 1 : -1);
			final int oz = axis == Direction.Axis.Z ? 0 : (corner[2] == 1 ? 1 : -1);
			final boolean side1;
			final boolean side2;
			final boolean across;
			if (axis == Direction.Axis.X) {
				side1 = getter.isOccluding(nx, ny + oy, nz);
				side2 = getter.isOccluding(nx, ny, nz + oz);
				across = getter.isOccluding(nx, ny + oy, nz + oz);
			} else if (axis == Direction.Axis.Y) {
				side1 = getter.isOccluding(nx + ox, ny, nz);
				side2 = getter.isOccluding(nx, ny, nz + oz);
				across = getter.isOccluding(nx + ox, ny, nz + oz);
			} else {
				side1 = getter.isOccluding(nx + ox, ny, nz);
				side2 = getter.isOccluding(nx, ny + oy, nz);
				across = getter.isOccluding(nx + ox, ny + oy, nz);
			}
			// A corner with both sides filled is fully tucked in whatever lies across from it
			final int open = side1 && side2 ? 0 : 3 - ((side1 ? 1 : 0) + (side2 ? 1 : 0) + (across ? 1 : 0));
			vertex(builder, x + corner[0], y + corner[1], z + corner[2], shade(shaded, CORNER_LIGHT[open]));
		}
	}

	private static float shadeFor(Direction direction) {
		switch (direction) {
			case UP:
				return SHADE_TOP;
			case DOWN:
				return SHADE_BOTTOM;
			case NORTH:
			case SOUTH:
				return SHADE_NORTH_SOUTH;
			default:
				return SHADE_EAST_WEST;
		}
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

}
