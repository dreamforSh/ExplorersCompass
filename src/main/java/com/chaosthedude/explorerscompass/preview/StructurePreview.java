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
 * for is: a village and an igloo both arrive as at most a few thousand cells.
 *
 * <p>Positions are grid cells rather than blocks. One cell is {@link #getStep()} blocks along each
 * axis, which is 1 for anything that fits the grid outright and more for anything that has to be
 * shrunk to fit. Blocks are carried as ids into the block state registry, which both sides of a
 * connection agree on for as long as they are connected.
 */
public class StructurePreview {

	/** The largest grid a preview is ever built on, so that a cell position fits in 18 bits. */
	public static final int MAX_GRID = 64;

	/** Far more of either than any preview built here holds; more than this is a malformed packet. */
	private static final int MAX_CELLS = 1 << 17;
	private static final int MAX_PALETTE = 1 << 14;

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
	 * How many of the pieces could only be outlined. A piece that builds itself block by block as it
	 * generates has no template to read, so all a preview can show of one is the space it occupies.
	 */
	private final int outlinedPieces;
	/** Whether the structure had more visible cells than a preview may carry. */
	private final boolean truncated;
	/** The distinct blocks that appear, as ids into the block state registry. */
	private final int[] palette;
	/** Cell positions, packed by {@link #pack}. */
	private final int[] positions;
	/** Which entry of the palette stands at each position, parallel to it. */
	private final int[] paletteIndices;

	StructurePreview(int gridX, int gridY, int gridZ, int step, int blockX, int blockY, int blockZ, int pieces, int outlinedPieces, boolean truncated, int[] palette, int[] positions, int[] paletteIndices) {
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
	}

	/** Packs a cell position into one number. Each axis is under {@link #MAX_GRID}, so six bits fit it. */
	static int pack(int x, int y, int z) {
		return (x << 12) | (y << 6) | z;
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
	 * the shape of its pieces rather than what it is built out of. Worth saying outright, since an
	 * outline looks like a structure made of one material rather than like a structure nothing is
	 * known about.
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
		return (positions[index] >> 12) & 63;
	}

	public int getCellY(int index) {
		return (positions[index] >> 6) & 63;
	}

	public int getCellZ(int index) {
		return positions[index] & 63;
	}

	/**
	 * The block standing in the given cell. A block the receiving side does not know resolves to air
	 * and draws as nothing, which is what an id no longer in the registry would mean anyway.
	 */
	public BlockState getCellState(int index) {
		return Block.stateById(palette[paletteIndices[index]]);
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

		buf.writeVarInt(positions.length);
		for (int i = 0; i < positions.length; i++) {
			buf.writeVarInt(positions[i]);
			buf.writeVarInt(paletteIndices[i]);
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
		for (int i = 0; i < cellCount; i++) {
			positions[i] = buf.readVarInt();
			final int paletteIndex = buf.readVarInt();
			if (paletteIndex < 0 || paletteIndex >= paletteSize) {
				throw new DecoderException("Structure preview names palette entry " + paletteIndex + " of " + paletteSize);
			}
			paletteIndices[i] = paletteIndex;
		}

		return new StructurePreview(gridX, gridY, gridZ, step, blockX, blockY, blockZ, pieces, outlinedPieces, truncated, palette, positions, paletteIndices);
	}

}
