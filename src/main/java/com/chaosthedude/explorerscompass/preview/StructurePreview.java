package com.chaosthedude.explorerscompass.preview;

import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What a structure looks like, small enough to send to a client and to draw in one frame.
 *
 * <p>A structure is assembled once on the server and thrown onto a grid of cells, each of which
 * holds whichever block fell into it. Cells with a block on every side of them are dropped, since
 * nothing inside a building is visible from outside it, and what is left is the shell that gets
 * drawn. A preview is therefore a fixed amount of data no matter how large the structure it stands
 * for is: a village and an igloo both arrive as a shell of at most so many cells.
 *
 * <p>Positions are grid cells rather than blocks. One cell is {@link #getStep()} blocks along each
 * axis, which is 1 for anything that fits the grid outright and more for anything that has to be
 * shrunk to fit. Blocks are carried as ids into the block state registry, which both sides of a
 * connection agree on for as long as they are connected.
 *
 * <p>The chests, beds, banners and signs are carried apart from the rest. They are drawn by a
 * renderer of their own rather than from a block model, so they can neither be built into the
 * model the rest of the shell becomes nor left in it as holes: see {@link #getComponentCount}.
 */
public class StructurePreview {

	/**
	 * The largest grid a preview is ever built on. Ten bits an axis, which is what leaves all three
	 * inside a positive int, and which is larger than any structure that has to be shrunk to fit it:
	 * a preview is meant to be one cell to one block, and only something over a thousand blocks
	 * across is too large for that to be possible at all.
	 */
	public static final int MAX_GRID = 1024;

	private static final int AXIS_BITS = 10;
	private static final int AXIS_MASK = (1 << AXIS_BITS) - 1;

	/** Far more of any of these than a preview built here holds; more than this is a malformed packet. */
	private static final int MAX_CELLS = 1 << 20;
	private static final int MAX_PALETTE = 1 << 14;
	private static final int MAX_COMPONENTS = 1 << 12;

	private final int gridX;
	private final int gridY;
	private final int gridZ;
	/** How many blocks along each axis one cell stands for. */
	private final int step;
	/** How large the structure itself is, in blocks. */
	private final int blockX;
	private final int blockY;
	private final int blockZ;
	private final int pieces;
	/**
	 * How many of the pieces could only be outlined. A piece that neither carries a template nor
	 * builds itself into anything has only the space it occupies to show for it.
	 */
	private final int outlinedPieces;
	/** Whether the structure had more visible cells than a preview may carry. */
	private final boolean truncated;
	/** The distinct blocks that appear, as ids into the block state registry. */
	private final int[] palette;
	/** Cell positions, packed by {@link #pack}, in ascending order. */
	private final int[] positions;
	/** Which entry of the palette stands at each position, parallel to it. */
	private final int[] paletteIndices;
	/** Where the blocks drawn by a renderer of their own stand, packed the same way. */
	private final int[] componentPositions;
	/** What each of them is, as an id into the block state registry, parallel to it. */
	private final int[] componentStates;

	StructurePreview(int gridX, int gridY, int gridZ, int step, int blockX, int blockY, int blockZ, int pieces, int outlinedPieces, boolean truncated, int[] palette, int[] positions, int[] paletteIndices, int[] componentPositions, int[] componentStates) {
		this.gridX = gridX;
		this.gridY = gridY;
		this.gridZ = gridZ;
		this.step = step;
		this.blockX = blockX;
		this.blockY = blockY;
		this.blockZ = blockZ;
		this.pieces = pieces;
		this.outlinedPieces = outlinedPieces;
		this.truncated = truncated;
		this.palette = palette;
		this.positions = positions;
		this.paletteIndices = paletteIndices;
		this.componentPositions = componentPositions;
		this.componentStates = componentStates;
	}

	/**
	 * Packs a cell position into one number. Height comes first and width last, which is the order
	 * the cells are collected in, so that a preview's positions come out ascending and can be sent as
	 * the steps between them rather than outright.
	 */
	public static int pack(int x, int y, int z) {
		return (y << (AXIS_BITS * 2)) | (z << AXIS_BITS) | x;
	}

	public int getGridX() {
		return gridX;
	}

	public int getGridY() {
		return gridY;
	}

	public int getGridZ() {
		return gridZ;
	}

	/** How many blocks along each axis one cell of this preview stands for. */
	public int getStep() {
		return step;
	}

	public int getBlockX() {
		return blockX;
	}

	public int getBlockY() {
		return blockY;
	}

	public int getBlockZ() {
		return blockZ;
	}

	public int getPieces() {
		return pieces;
	}

	/** How many pieces could only be shown as the space they occupy. */
	public int getOutlinedPieces() {
		return outlinedPieces;
	}

	/**
	 * Whether nothing about this structure could be read block by block, so that everything drawn is
	 * the shape of its pieces rather than what it is built out of.
	 */
	public boolean isOutlineOnly() {
		return pieces > 0 && outlinedPieces == pieces;
	}

	public boolean isTruncated() {
		return truncated;
	}

	/** How many cells this preview draws. */
	public int getCellCount() {
		return positions.length;
	}

	/** How many distinct blocks appear in it. */
	public int getPaletteSize() {
		return palette.length;
	}

	public int getCellX(int index) {
		return unpackX(positions[index]);
	}

	public int getCellY(int index) {
		return unpackY(positions[index]);
	}

	public int getCellZ(int index) {
		return unpackZ(positions[index]);
	}

	/** The packed position of a cell, for looking up whether a neighbouring one is filled. */
	public int getCellPosition(int index) {
		return positions[index];
	}

	/**
	 * The block standing in the given cell. A block the receiving side does not know resolves to air
	 * and draws as nothing, which is what an id no longer in the registry would mean anyway.
	 */
	public BlockState getCellState(int index) {
		return Block.stateById(palette[paletteIndices[index]]);
	}

	/** Which entry of the palette stands in the given cell, for colouring a cell without resolving it. */
	public int getCellPaletteIndex(int index) {
		return paletteIndices[index];
	}

	/** The block behind the given palette entry. */
	public BlockState getPaletteState(int paletteIndex) {
		return Block.stateById(palette[paletteIndex]);
	}

	/**
	 * How many of the blocks that are drawn by a renderer of their own — chests, beds, banners,
	 * signs, shulker boxes — this preview carries.
	 */
	public int getComponentCount() {
		return componentPositions.length;
	}

	public int getComponentX(int index) {
		return unpackX(componentPositions[index]);
	}

	public int getComponentY(int index) {
		return unpackY(componentPositions[index]);
	}

	public int getComponentZ(int index) {
		return unpackZ(componentPositions[index]);
	}

	public int getComponentPosition(int index) {
		return componentPositions[index];
	}

	public BlockState getComponentState(int index) {
		return Block.stateById(componentStates[index]);
	}

	static int unpackX(int packed) {
		return packed & AXIS_MASK;
	}

	static int unpackY(int packed) {
		return (packed >>> (AXIS_BITS * 2)) & AXIS_MASK;
	}

	static int unpackZ(int packed) {
		return (packed >>> AXIS_BITS) & AXIS_MASK;
	}

	public void write(FriendlyByteBuf buf) {
		buf.writeVarInt(gridX);
		buf.writeVarInt(gridY);
		buf.writeVarInt(gridZ);
		buf.writeVarInt(step);
		buf.writeVarInt(blockX);
		buf.writeVarInt(blockY);
		buf.writeVarInt(blockZ);
		buf.writeVarInt(pieces);
		buf.writeVarInt(outlinedPieces);
		buf.writeBoolean(truncated);

		buf.writeVarInt(palette.length);
		for (int stateId : palette) {
			buf.writeVarInt(stateId);
		}

		// The step from one cell to the next rather than the cell itself: the cells are collected in
		// ascending order, and the steps between neighbours in a shell are small enough to spend one
		// byte on where a position would spend three
		buf.writeVarInt(positions.length);
		int previous = 0;
		for (int i = 0; i < positions.length; i++) {
			buf.writeVarInt(positions[i] - previous);
			previous = positions[i];
			buf.writeVarInt(paletteIndices[i]);
		}

		buf.writeVarInt(componentPositions.length);
		for (int i = 0; i < componentPositions.length; i++) {
			buf.writeVarInt(componentPositions[i]);
			buf.writeVarInt(componentStates[i]);
		}
	}

	public static StructurePreview read(FriendlyByteBuf buf) {
		final int gridX = buf.readVarInt();
		final int gridY = buf.readVarInt();
		final int gridZ = buf.readVarInt();
		final int step = buf.readVarInt();
		final int blockX = buf.readVarInt();
		final int blockY = buf.readVarInt();
		final int blockZ = buf.readVarInt();
		final int pieces = buf.readVarInt();
		final int outlinedPieces = buf.readVarInt();
		final boolean truncated = buf.readBoolean();

		final int paletteSize = buf.readVarInt();
		if (paletteSize < 0 || paletteSize > MAX_PALETTE) {
			throw new DecoderException("Structure preview carries a palette of " + paletteSize + " blocks");
		}
		final int[] palette = new int[paletteSize];
		for (int i = 0; i < paletteSize; i++) {
			palette[i] = buf.readVarInt();
		}

		final int cellCount = buf.readVarInt();
		if (cellCount < 0 || cellCount > MAX_CELLS) {
			throw new DecoderException("Structure preview carries " + cellCount + " cells");
		}
		final int[] positions = new int[cellCount];
		final int[] paletteIndices = new int[cellCount];
		int previous = 0;
		for (int i = 0; i < cellCount; i++) {
			previous += buf.readVarInt();
			positions[i] = previous;
			final int paletteIndex = buf.readVarInt();
			if (paletteIndex < 0 || paletteIndex >= paletteSize) {
				throw new DecoderException("Structure preview names palette entry " + paletteIndex + " of " + paletteSize);
			}
			paletteIndices[i] = paletteIndex;
		}

		final int componentCount = buf.readVarInt();
		if (componentCount < 0 || componentCount > MAX_COMPONENTS) {
			throw new DecoderException("Structure preview carries " + componentCount + " components");
		}
		final int[] componentPositions = new int[componentCount];
		final int[] componentStates = new int[componentCount];
		for (int i = 0; i < componentCount; i++) {
			componentPositions[i] = buf.readVarInt();
			componentStates[i] = buf.readVarInt();
		}

		return new StructurePreview(gridX, gridY, gridZ, step, blockX, blockY, blockZ, pieces, outlinedPieces, truncated, palette, positions, paletteIndices, componentPositions, componentStates);
	}

}
