package com.chaosthedude.explorerscompass.preview;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.chaosthedude.explorerscompass.mixin.SinglePoolElementTemplateAccessor;
import com.chaosthedude.explorerscompass.mixin.StructureTemplatePalettesAccessor;
import com.chaosthedude.explorerscompass.util.StructureUtils;

import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.TemplateStructurePiece;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/**
 * Works out what a structure looks like, without any of it having to exist in the world.
 *
 * <p>World generation is asked to assemble the structure the same way it would while generating a
 * chunk, which answers with the pieces it is made of and where each of them would stand. The blocks
 * are then got out of those pieces in whichever of two ways the piece allows:
 *
 * <ul>
 * <li>A piece built from a template hands its blocks over as they are written on disk.
 * <li>A piece that builds itself as it generates — the strongholds, mineshafts, monuments, nether
 * fortresses and the scattered temples, none of which have a template anywhere — is let build
 * itself into a {@link RecordingLevel}, which takes down every block it lays and places none of
 * them. A piece that neither of these gets anything out of is drawn as its outline alone.
 * </ul>
 *
 * <p>None of it reads or writes any part of the world, and nothing is placed anywhere: what was
 * assembled is thrown away as soon as it has been drawn onto the grid.
 *
 * <p>Which chunk it is assembled in is not where the structure would actually generate. Where one
 * generates is decided by its placement, and a preview is asked for from wherever the player happens
 * to be standing, so this walks out from the world origin until the structure agrees to assemble
 * somewhere. Several such assemblies are compared and the one standing on the flattest ground is
 * kept: a village strung down a mountainside is a picture of the mountain rather than of the village.
 */
public final class StructurePreviewBuilder {

	/** How many chunks a preview tries to assemble a structure in before giving up on it. */
	private static final int MAX_ASSEMBLY_ATTEMPTS = 48;
	/**
	 * How far apart those chunks are. Spread out rather than adjacent, since a structure that turned
	 * one chunk down usually did so because of the ground there, and the next chunk over has the same
	 * ground.
	 */
	private static final int ATTEMPT_SPACING = 8;
	/** How many assemblies are compared before the most compact of them is kept. */
	private static final int COMPARED_ASSEMBLIES = 6;
	/**
	 * How tall an assembly may stand and still be kept without generating the structure again to
	 * compare it with. Judged against how far it reaches sideways as well as outright, since what this
	 * is looking for is a structure strung down a slope rather than one that is simply tall: a
	 * mineshaft is deeper than a village on a mountainside without anything being wrong with it.
	 */
	private static final int FLAT_ENOUGH_Y_SPAN = 32;

	/** A ceiling on how much of a structure is read, so that no one structure can hold up a tick. */
	private static final int MAX_SOURCE_BLOCKS = 1000000;
	/** How many times the grid is coarsened when what it holds does not fit inside the cell budget. */
	private static final int MAX_SHRINK_ATTEMPTS = 4;
	/**
	 * How many of the blocks drawn by a renderer of their own are carried. Each one is a block entity
	 * the client builds and draws for itself every frame, which is far more than a cell costs, and a
	 * building has only so many chests and beds worth seeing from outside it anyway.
	 */
	private static final int MAX_COMPONENTS = 512;

	private StructurePreviewBuilder() {
	}

	/**
	 * What a piece that cannot be read block by block is drawn as. Resolved when it is wanted rather
	 * than held in a field, so that loading this class does not pull the whole block registry in
	 * behind it.
	 */
	private static BlockState outlineBlock() {
		return Blocks.SMOOTH_STONE.defaultBlockState();
	}

	/**
	 * Builds a preview of the given structure in the given level, or null when it could not be
	 * assembled there. Reads no part of the world, but is not cheap, so callers cache the result:
	 * see {@link StructurePreviewService}.
	 */
	public static StructurePreview build(ServerLevel level, ResourceLocation structureKey) {
		final Structure structure = StructureUtils.getStructureForKey(level, structureKey);
		if (structure == null) {
			return null;
		}

		final StructureStart start = assemble(level, structure, structureKey);
		if (start == null) {
			return null;
		}

		try {
			final StructurePreview preview = draw(level, start);
			// Said outright rather than left to be noticed on the screen. Every structure the game
			// itself adds fits inside the budget several times over, so anything that does not is
			// either a structure from a mod or a budget that has been turned down, and either way it
			// is the one thing about a preview that a server owner would want to know.
			if (preview.getStep() > 1 || preview.isTruncated()) {
				ExplorersCompass.LOGGER.info("Preview of " + structureKey + " is not one cell to one block: " + preview.getCellCount() + " cells at " + preview.getStep() + " blocks each" + (preview.isTruncated() ? ", and more of it than that" : "") + ". Raise structurePreviewMaxBlocks to show it at its own size.");
			}
			return preview;
		} catch (Throwable t) {
			// A structure from a mod can hold pieces of its own, and reading one is running its code
			ExplorersCompass.LOGGER.warn("Could not draw a preview of " + structureKey, t);
			return null;
		}
	}

	/**
	 * Has world generation assemble the structure, and answers with the most compact assembly it got,
	 * or null when it would not assemble anywhere that was tried.
	 *
	 * <p>Every biome counts as one the structure may stand in. What biomes are for is deciding where
	 * a structure generates, which a preview is not asking; leaving them out is what lets a structure
	 * be previewed from a dimension it could never generate in.
	 */
	private static StructureStart assemble(ServerLevel level, Structure structure, ResourceLocation structureKey) {
		final ChunkGenerator generator = level.getChunkSource().getGenerator();
		final RandomState randomState = level.getChunkSource().randomState();
		final StructureTemplateManager templateManager = level.getServer().getStructureManager();

		StructureStart best = null;
		int assembled = 0;
		for (int attempt = 0; attempt < MAX_ASSEMBLY_ATTEMPTS && assembled < COMPARED_ASSEMBLIES; attempt++) {
			final ChunkPos chunkPos = chunkForAttempt(attempt);
			final StructureStart start;
			try {
				start = structure.generate(level.registryAccess(), generator, generator.getBiomeSource(), randomState, templateManager, level.getSeed(), chunkPos, 0, level, (biome) -> true);
			} catch (Throwable t) {
				ExplorersCompass.LOGGER.debug("Could not assemble " + structureKey + " in chunk " + chunkPos + " for a preview", t);
				continue;
			}

			if (!start.isValid() || start.getPieces().isEmpty()) {
				continue;
			}

			assembled++;
			if (best == null || start.getBoundingBox().getYSpan() < best.getBoundingBox().getYSpan()) {
				best = start;
			}
			// Anything this flat is standing on level ground, and no other assembly can improve on it
			// enough to be worth generating the whole structure again for
			if (isFlatEnough(best.getBoundingBox())) {
				return best;
			}
		}

		if (best == null) {
			ExplorersCompass.LOGGER.info("No preview for " + structureKey + ": it did not assemble in any of the " + MAX_ASSEMBLY_ATTEMPTS + " chunks that were tried");
		}
		return best;
	}

	/** Whether an assembly stands on ground level enough that looking for a better one is wasted work. */
	private static boolean isFlatEnough(BoundingBox bounds) {
		final int reach = Math.max(bounds.getXSpan(), bounds.getZSpan());
		return bounds.getYSpan() <= Math.max(FLAT_ENOUGH_Y_SPAN, reach / 2);
	}

	/** Walks a square spiral out from the world origin, a chunk of it per attempt. */
	private static ChunkPos chunkForAttempt(int attempt) {
		int x = 0;
		int z = 0;
		int stepX = 0;
		int stepZ = -1;
		for (int i = 0; i < attempt; i++) {
			if (x == z || (x < 0 && x == -z) || (x > 0 && x == 1 - z)) {
				final int turned = stepX;
				stepX = -stepZ;
				stepZ = turned;
			}
			x += stepX;
			z += stepZ;
		}
		return new ChunkPos(x * ATTEMPT_SPACING, z * ATTEMPT_SPACING);
	}

	/**
	 * Draws an assembled structure onto a grid, coarsening it until what is visible fits inside the
	 * cell budget. The blocks are got out of the pieces once and thrown onto each grid in turn, since
	 * getting them is what the whole of this costs.
	 */
	private static StructurePreview draw(ServerLevel level, StructureStart start) {
		final BoundingBox bounds = start.getBoundingBox();
		final List<StructurePiece> pieces = start.getPieces();
		final Assembly assembly = collect(level, start, bounds);
		final int resolution = Mth.clamp(ConfigHandler.GENERAL.structurePreviewResolution.get(), 8, StructurePreview.MAX_GRID);
		final int maxCells = ConfigHandler.GENERAL.structurePreviewMaxBlocks.get();

		int step = stepFor(bounds.getXSpan(), bounds.getYSpan(), bounds.getZSpan(), resolution);
		Grid grid = null;
		for (int attempt = 0; attempt < MAX_SHRINK_ATTEMPTS; attempt++) {
			grid = new Grid(bounds, step, resolution);
			grid.fill(assembly);
			if (grid.countVisible() <= maxCells) {
				break;
			}
			// One more block per cell along each axis takes roughly a third off the surface it leaves
			step++;
		}

		return grid.toPreview(bounds, pieces.size(), assembly.outlined.size(), assembly.truncated, maxCells);
	}

	/** Every block of every piece, and the pieces nothing at all could be got out of. */
	private static Assembly collect(ServerLevel level, StructureStart start, BoundingBox bounds) {
		final StructureTemplateManager templateManager = level.getServer().getStructureManager();
		final RecordingLevel recordingLevel = new RecordingLevel(level, bounds, MAX_SOURCE_BLOCKS, level.getSeed());
		final List<StructurePiece> procedural = new ArrayList<StructurePiece>();
		final List<StructurePiece> outlined = new ArrayList<StructurePiece>();

		for (StructurePiece piece : start.getPieces()) {
			if (!collectTemplate(templateManager, piece, recordingLevel)) {
				procedural.add(piece);
			}
		}

		if (!procedural.isEmpty()) {
			buildProcedural(level, start, bounds, procedural, recordingLevel, outlined);
		}

		return new Assembly(recordingLevel.getRecorded(), outlined, recordingLevel.isFull());
	}

	/**
	 * Takes the blocks of one piece out of the template behind it, and answers whether there was one.
	 *
	 * <p>The two kinds of piece that carry a template each say where it stands differently: a
	 * template piece keeps the settings it was placed with, while a jigsaw piece keeps its rotation
	 * and the corner it was put down at. Both are turned into the same pair of a placement and an
	 * origin, and the template is then read through them exactly the way placing it would.
	 *
	 * <p>What is not run is the processors a placement would put the blocks through on their way into
	 * the world, which are what turn a village into a zombie village or weather a piece against the
	 * ground it landed on. Those describe one instance of a structure; a preview is of the structure.
	 */
	private static boolean collectTemplate(StructureTemplateManager templateManager, StructurePiece piece, RecordingLevel recordingLevel) {
		final StructureTemplate template;
		final StructurePlaceSettings settings;
		final BlockPos origin;
		if (piece instanceof TemplateStructurePiece templatePiece) {
			template = templatePiece.template();
			settings = templatePiece.placeSettings();
			origin = templatePiece.templatePosition();
		} else if (piece instanceof PoolElementStructurePiece poolPiece && poolPiece.getElement() instanceof SinglePoolElement singleElement) {
			template = ((SinglePoolElementTemplateAccessor) singleElement).explorerscompass$getTemplate(templateManager);
			// The rotation is the whole of how a jigsaw piece is turned; nothing about one is mirrored
			settings = new StructurePlaceSettings().setRotation(poolPiece.getRotation());
			origin = poolPiece.getPosition();
		} else {
			return false;
		}

		if (template == null) {
			return false;
		}

		final List<StructureTemplate.Palette> palettes = ((StructureTemplatePalettesAccessor) template).explorerscompass$getPalettes();
		if (palettes.isEmpty()) {
			return false;
		}

		// Which palette a placement uses is drawn at random when it is placed, so a preview reads the
		// first: it shows what the structure is rather than what one instance of it turned out as
		for (StructureTemplate.StructureBlockInfo info : palettes.get(0).blocks()) {
			final BlockState state = info.state.mirror(settings.getMirror()).rotate(settings.getRotation());
			// Only what could end up in the preview is taken down. A template records every position in
			// the box it was cut from, air included, and the largest of them are almost nothing else:
			// the bastions and the ancient city are assembled partly out of templates that exist to
			// carve space rather than to fill it, and taking those down would spend the whole of what
			// one structure may be read on air.
			if (!isDrawable(state) && !isComponent(state)) {
				continue;
			}

			final BlockPos pos = StructureTemplate.calculateRelativePosition(settings, info.pos).offset(origin);
			if (!recordingLevel.record(pos, state)) {
				break;
			}
		}
		return true;
	}

	/**
	 * Lets each of the pieces that has no template build itself into the recording level, and notes
	 * the ones that laid no blocks at all, which are left to be outlined.
	 *
	 * <p>The space a piece may build in has to be given the same shape world generation gives it: the
	 * whole structure across, and the whole world tall. What world generation hands a piece is the
	 * chunk being generated, which is a column reaching from the bottom of the world to the top, and
	 * some of the older pieces measure themselves against that. The scattered temples ask how high the
	 * ground is by probing a fixed height inside it and give up on building anything at all when that
	 * probe falls outside — so a piece handed its own bounding box, which is only as tall as the
	 * building, would answer for a swamp hut or a jungle temple with nothing whatsoever.
	 *
	 * <p>All of the pieces are run in one go rather than a chunk at a time. Running one is running
	 * that structure's own code, so each is guarded on its own: a piece that throws, or that lays no
	 * blocks anyway, costs its own detail and nothing else.
	 */
	private static void buildProcedural(ServerLevel level, StructureStart start, BoundingBox bounds, List<StructurePiece> pieces, RecordingLevel recordingLevel, List<StructurePiece> outlined) {
		final ChunkGenerator generator = level.getChunkSource().getGenerator();
		final StructureManager structureManager = level.structureManager();
		final BoundingBox buildableSpace = new BoundingBox(bounds.minX(), level.getMinBuildHeight(), bounds.minZ(), bounds.maxX(), level.getMaxBuildHeight(), bounds.maxZ());
		// Where world generation says a structure stands: the middle of its first piece, at that
		// piece's base. Some pieces measure themselves against it.
		final BoundingBox firstBox = start.getPieces().get(0).getBoundingBox();
		final BlockPos reference = new BlockPos(firstBox.getCenter().getX(), firstBox.minY(), firstBox.getCenter().getZ());

		for (StructurePiece piece : pieces) {
			final BoundingBox box = piece.getBoundingBox();
			final int before = recordingLevel.getRecordedCount();
			try {
				// Seeded off the piece rather than off nothing, so that a preview of the same structure
				// comes out the same way every time it is built
				final RandomSource random = RandomSource.create(level.getSeed() + box.minX() * 31L + box.minZ() * 17L + box.minY());
				piece.postProcess(recordingLevel, structureManager, generator, random, buildableSpace, new ChunkPos(box.getCenter()), reference);
			} catch (Throwable t) {
				ExplorersCompass.LOGGER.debug("A structure piece could not build itself for a preview; it will be outlined instead", t);
			}

			if (recordingLevel.getRecordedCount() == before) {
				// Worth naming: this is the one thing that turns a building into a plain box, and which
				// piece it was is the whole of what anyone would need to look into it
				ExplorersCompass.LOGGER.debug("Preview: " + piece.getClass().getName() + " laid no blocks and will be outlined instead");
				outlined.add(piece);
			}
		}
	}

	/**
	 * Whether a block belongs in the shell: one drawn from a model, which is what the shell is built
	 * out of. Air, the fluids and the markers a template is wired together with all draw as nothing,
	 * and each of them would take a cell that something visible could have had.
	 */
	private static boolean isDrawable(BlockState state) {
		return !state.isAir() && state.getRenderShape() == RenderShape.MODEL && !state.is(Blocks.JIGSAW) && !state.is(Blocks.STRUCTURE_BLOCK);
	}

	/**
	 * Whether a block is one of the ones drawn by a renderer of its own — a chest, a bed, a banner, a
	 * sign, a shulker box. These have nothing but a particle texture to their name as a model, so
	 * leaving one in the shell would put a hole where it stands rather than a chest. They are carried
	 * apart from the shell and built into real block entities by whoever draws them.
	 */
	private static boolean isComponent(BlockState state) {
		return state.getRenderShape() == RenderShape.ENTITYBLOCK_ANIMATED && state.getBlock() instanceof EntityBlock;
	}

	/** How many blocks one cell has to stand for, for a structure of this size to fit the grid. */
	static int stepFor(int spanX, int spanY, int spanZ, int resolution) {
		final int largestSpan = Math.max(spanX, Math.max(spanY, spanZ));
		return divideCeil(largestSpan, resolution);
	}

	private static int divideCeil(int value, int divisor) {
		return Math.max(1, (value + divisor - 1) / divisor);
	}

	/** Everything got out of an assembled structure, ready to be thrown onto a grid of any coarseness. */
	private record Assembly(Long2IntOpenHashMap blocks, List<StructurePiece> outlined, boolean truncated) {
	}

	/**
	 * The grid a structure is drawn onto, as the cells that actually hold something.
	 *
	 * <p>Held as a map from packed cell to block state id rather than as an array over the whole
	 * grid. A preview is meant to be one cell to one block, which makes the grid as large as the
	 * structure, and an array over a structure a few hundred blocks each way would be hundreds of
	 * megabytes of mostly nothing. What is actually filled is a building's walls and roof, which is a
	 * small fraction of the space it stands in, so this grows with the structure rather than with the
	 * cube around it.
	 */
	private static final class Grid {

		private final BoundingBox bounds;
		private final int step;
		private final int sizeX;
		private final int sizeY;
		private final int sizeZ;
		/** The filled cells, packed cell to block state id. */
		private final Int2IntOpenHashMap cells = new Int2IntOpenHashMap();
		/** Where the blocks drawn by a renderer of their own stand, packed cell to block state id. */
		private final Int2IntOpenHashMap components = new Int2IntOpenHashMap();

		private Grid(BoundingBox bounds, int step, int resolution) {
			this.bounds = bounds;
			this.step = step;
			sizeX = Math.min(resolution, divideCeil(bounds.getXSpan(), step));
			sizeY = Math.min(resolution, divideCeil(bounds.getYSpan(), step));
			sizeZ = Math.min(resolution, divideCeil(bounds.getZSpan(), step));
		}

		/** Throws everything an assembly holds onto this grid, outlines last. */
		private void fill(Assembly assembly) {
			for (Long2IntMap.Entry entry : assembly.blocks().long2IntEntrySet()) {
				final BlockState state = Block.stateById(entry.getIntValue());
				final long packed = entry.getLongKey();
				final int blockX = BlockPos.getX(packed);
				final int blockY = BlockPos.getY(packed);
				final int blockZ = BlockPos.getZ(packed);
				if (isDrawable(state)) {
					put(blockX, blockY, blockZ, state);
				} else if (isComponent(state)) {
					putComponent(blockX, blockY, blockZ, entry.getIntValue());
				}
			}

			// Outlines are drawn after every block that is actually known, so that a piece standing
			// inside another does not paint over what was got out of that one
			for (StructurePiece piece : assembly.outlined()) {
				outline(piece.getBoundingBox());
			}
		}

		private int cellOf(int offset, int size) {
			return Mth.clamp(offset / step, 0, size - 1);
		}

		/** The cell a block falls in, packed. */
		private int cellAt(int blockX, int blockY, int blockZ) {
			return StructurePreview.pack(cellOf(blockX - bounds.minX(), sizeX), cellOf(blockY - bounds.minY(), sizeY), cellOf(blockZ - bounds.minZ(), sizeZ));
		}

		private boolean isEmptyAt(int x, int y, int z) {
			// Anything just outside the grid is open air, which is what makes the outermost cells show
			return x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ || !cells.containsKey(StructurePreview.pack(x, y, z));
		}

		/** Notes one of the blocks drawn by a renderer of its own, in whichever cell holds it. */
		private void putComponent(int blockX, int blockY, int blockZ, int stateId) {
			if (components.size() >= MAX_COMPONENTS) {
				return;
			}
			final int cell = cellAt(blockX, blockY, blockZ);
			if (!components.containsKey(cell)) {
				components.put(cell, stateId);
			}
		}

		/** Puts a block in whichever cell holds it, leaving a cell that already holds one alone. */
		private void put(int blockX, int blockY, int blockZ, BlockState state) {
			final int cell = cellAt(blockX, blockY, blockZ);
			if (!cells.containsKey(cell)) {
				cells.put(cell, Block.getId(state));
			}
		}

		/** Fills the faces of a box, which is as much as is known about a piece nothing came out of. */
		private void outline(BoundingBox box) {
			final int minX = cellOf(box.minX() - bounds.minX(), sizeX);
			final int maxX = cellOf(box.maxX() - bounds.minX(), sizeX);
			final int minY = cellOf(box.minY() - bounds.minY(), sizeY);
			final int maxY = cellOf(box.maxY() - bounds.minY(), sizeY);
			final int minZ = cellOf(box.minZ() - bounds.minZ(), sizeZ);
			final int maxZ = cellOf(box.maxZ() - bounds.minZ(), sizeZ);
			final int outlineId = Block.getId(outlineBlock());

			for (int y = minY; y <= maxY; y++) {
				for (int z = minZ; z <= maxZ; z++) {
					for (int x = minX; x <= maxX; x++) {
						final boolean onAFace = x == minX || x == maxX || y == minY || y == maxY || z == minZ || z == maxZ;
						final int cell = StructurePreview.pack(x, y, z);
						if (onAFace && !cells.containsKey(cell)) {
							cells.put(cell, outlineId);
						}
					}
				}
			}
		}

		/** How many cells have at least one open side, which is everything a preview draws. */
		private int countVisible() {
			int visible = 0;
			final IntIterator filled = cells.keySet().iterator();
			while (filled.hasNext()) {
				final int cell = filled.nextInt();
				if (isVisible(StructurePreview.unpackX(cell), StructurePreview.unpackY(cell), StructurePreview.unpackZ(cell))) {
					visible++;
				}
			}
			return visible;
		}

		private boolean isVisible(int x, int y, int z) {
			return isEmptyAt(x - 1, y, z) || isEmptyAt(x + 1, y, z) || isEmptyAt(x, y - 1, z) || isEmptyAt(x, y + 1, z) || isEmptyAt(x, y, z - 1) || isEmptyAt(x, y, z + 1);
		}

		/**
		 * Collects what is visible into the preview.
		 *
		 * <p>The cells are sorted before they are walked, which puts them in the order the packing
		 * sorts in: from the ground up, and along each layer. That order is what lets the positions
		 * travel as the steps between them, and it is also what makes a structure that still does not
		 * fit the budget lose its roof rather than the ground it stands on.
		 */
		private StructurePreview toPreview(BoundingBox structureBounds, int pieces, int outlinedPieces, boolean truncated, int maxCells) {
			final Int2IntOpenHashMap paletteIndices = new Int2IntOpenHashMap();
			// So that a block not yet in the palette answers with something no index ever is
			paletteIndices.defaultReturnValue(-1);
			final IntArrayList palette = new IntArrayList();
			final IntArrayList positions = new IntArrayList();
			final IntArrayList indices = new IntArrayList();

			final int[] ordered = cells.keySet().toIntArray();
			Arrays.sort(ordered);

			boolean overflowed = false;
			for (int cell : ordered) {
				if (!isVisible(StructurePreview.unpackX(cell), StructurePreview.unpackY(cell), StructurePreview.unpackZ(cell))) {
					continue;
				}
				if (positions.size() >= maxCells) {
					overflowed = true;
					break;
				}

				final int stateId = cells.get(cell);
				int paletteIndex = paletteIndices.get(stateId);
				if (paletteIndex < 0) {
					paletteIndex = palette.size();
					paletteIndices.put(stateId, paletteIndex);
					palette.add(stateId);
				}
				positions.add(cell);
				indices.add(paletteIndex);
			}

			final IntArrayList componentPositions = new IntArrayList();
			final IntArrayList componentStates = new IntArrayList();
			for (Int2IntMap.Entry component : components.int2IntEntrySet()) {
				// A cell the shell already fills is one nothing could be seen inside anyway, and drawing
				// a chest into the middle of a wall would put it half inside the blocks around it
				if (!cells.containsKey(component.getIntKey())) {
					componentPositions.add(component.getIntKey());
					componentStates.add(component.getIntValue());
				}
			}

			return new StructurePreview(sizeX, sizeY, sizeZ, step, structureBounds.getXSpan(), structureBounds.getYSpan(), structureBounds.getZSpan(), pieces, outlinedPieces, truncated || overflowed, palette.toIntArray(), positions.toIntArray(), indices.toIntArray(), componentPositions.toIntArray(), componentStates.toIntArray());
		}

	}

}
