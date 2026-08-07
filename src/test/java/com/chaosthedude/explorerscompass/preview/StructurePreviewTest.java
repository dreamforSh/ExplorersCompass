package com.chaosthedude.explorerscompass.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Holds a preview to what it says about itself, on both sides of the wire.
 *
 * <p>A preview is built on one side of a connection and drawn on the other, and nothing in between
 * checks that what arrived means what was sent. A cell that unpacks to the wrong position puts a
 * block somewhere it never stood, which looks like a structure rather than like a fault.
 */
class StructurePreviewTest {

	@Test
	void aCellIsReadBackAtThePositionItWasWrittenAt() {
		// The corners of the largest grid a preview is ever built on, where a packing that has run out
		// of room shows up first
		final int[][] positions = { { 0, 0, 0 }, { 63, 63, 63 }, { 63, 0, 0 }, { 0, 63, 0 }, { 0, 0, 63 }, { 17, 5, 40 } };
		final StructurePreview preview = previewOf(positions);

		for (int cell = 0; cell < positions.length; cell++) {
			assertEquals(positions[cell][0], preview.getCellX(cell), "cell " + cell + " came back at another x");
			assertEquals(positions[cell][1], preview.getCellY(cell), "cell " + cell + " came back at another y");
			assertEquals(positions[cell][2], preview.getCellZ(cell), "cell " + cell + " came back at another z");
		}
	}

	@Test
	void whatArrivesIsWhatWasSent() {
		final int[][] positions = { { 1, 2, 3 }, { 63, 0, 12 }, { 8, 8, 8 } };
		final StructurePreview sent = previewOf(positions);

		final FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		sent.write(buf);
		final StructurePreview received = StructurePreview.read(buf);

		assertEquals(0, buf.readableBytes(), "the preview was not read back to the end of what it wrote");
		assertEquals(sent.getGridX(), received.getGridX());
		assertEquals(sent.getGridY(), received.getGridY());
		assertEquals(sent.getGridZ(), received.getGridZ());
		assertEquals(sent.getStep(), received.getStep());
		assertEquals(sent.getBlockX(), received.getBlockX());
		assertEquals(sent.getBlockY(), received.getBlockY());
		assertEquals(sent.getBlockZ(), received.getBlockZ());
		assertEquals(sent.getPieces(), received.getPieces());
		assertEquals(sent.getOutlinedPieces(), received.getOutlinedPieces());
		assertEquals(sent.isTruncated(), received.isTruncated());
		assertEquals(sent.getPaletteSize(), received.getPaletteSize());
		assertEquals(sent.getCellCount(), received.getCellCount());
		for (int cell = 0; cell < positions.length; cell++) {
			assertEquals(sent.getCellX(cell), received.getCellX(cell));
			assertEquals(sent.getCellY(cell), received.getCellY(cell));
			assertEquals(sent.getCellZ(cell), received.getCellZ(cell));
		}
	}

	@Test
	void aStructureThatFitsTheGridIsShownBlockForBlock() {
		// Nothing is worth shrinking until it is larger than the grid it has to fit on
		assertEquals(1, StructurePreviewBuilder.stepFor(7, 5, 8, 48));
		assertEquals(1, StructurePreviewBuilder.stepFor(48, 48, 48, 48));
	}

	@Test
	void aStructureLargerThanTheGridIsShrunkUntilItFits() {
		// The largest side is what has to fit, since one step is used along every axis
		assertEquals(2, StructurePreviewBuilder.stepFor(49, 10, 10, 48));
		assertEquals(2, StructurePreviewBuilder.stepFor(10, 10, 96, 48));
		assertEquals(3, StructurePreviewBuilder.stepFor(97, 20, 30, 48));
		// However coarse it has to be, the whole structure ends up inside the grid
		assertTrue(divideCeil(1000, StructurePreviewBuilder.stepFor(1000, 200, 400, 48)) <= 48);
	}

	@Test
	void aPreviewSaysWhenNoneOfItCouldBeRead() {
		assertTrue(preview(4, 4, 0, false).isOutlineOnly(), "every piece was outlined, which is worth saying");
		assertFalse(preview(4, 1, 0, false).isOutlineOnly(), "only some of the pieces were outlined");
		assertFalse(preview(4, 0, 0, false).isOutlineOnly(), "every piece was read block for block");
		// A structure that assembled into nothing at all has nothing to say either way
		assertFalse(preview(0, 0, 0, false).isOutlineOnly());
	}

	private static int divideCeil(int value, int divisor) {
		return (value + divisor - 1) / divisor;
	}

	/** A preview holding one cell at each of the given positions, all of the same block. */
	private static StructurePreview previewOf(int[][] positions) {
		final int[] packed = new int[positions.length];
		final int[] paletteIndices = new int[positions.length];
		for (int cell = 0; cell < positions.length; cell++) {
			packed[cell] = StructurePreview.pack(positions[cell][0], positions[cell][1], positions[cell][2]);
		}
		return new StructurePreview(64, 64, 64, 2, 128, 64, 96, 3, 1, false, new int[] { 7 }, packed, paletteIndices);
	}

	private static StructurePreview preview(int pieces, int outlinedPieces, int cells, boolean truncated) {
		return new StructurePreview(4, 4, 4, 1, 4, 4, 4, pieces, outlinedPieces, truncated, new int[0], new int[cells], new int[cells]);
	}

}
