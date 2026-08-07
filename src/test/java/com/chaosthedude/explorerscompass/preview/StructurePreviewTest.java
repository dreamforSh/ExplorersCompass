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
		final int[][] positions = { { 0, 0, 0 }, { 1023, 1023, 1023 }, { 1023, 0, 0 }, { 0, 1023, 0 }, { 0, 0, 1023 }, { 17, 5, 40 } };
		final StructurePreview preview = previewOf(positions);

		for (int cell = 0; cell < positions.length; cell++) {
			assertEquals(positions[cell][0], preview.getCellX(cell), "cell " + cell + " came back at another x");
			assertEquals(positions[cell][1], preview.getCellY(cell), "cell " + cell + " came back at another y");
			assertEquals(positions[cell][2], preview.getCellZ(cell), "cell " + cell + " came back at another z");
		}
	}

	@Test
	void everyAxisReachesTheLargestGridAPreviewIsBuiltOn() {
		// A preview is one cell to one block, so the packing has to hold a structure as large as one
		// can be built on rather than a downsampled stand-in for it
		final int last = StructurePreview.MAX_GRID - 1;
		final int packed = StructurePreview.pack(last, last, last);
		assertTrue(packed > 0, "the largest position a grid holds does not pack into a positive number");
		assertEquals(last, StructurePreview.unpackX(packed));
		assertEquals(last, StructurePreview.unpackY(packed));
		assertEquals(last, StructurePreview.unpackZ(packed));
	}

	@Test
	void theAxesArePackedSoThatCollectingOrderRuns() {
		// Positions travel as the step from one to the next, which is only smaller than the position
		// itself while the cells are collected in ascending order. They are collected height first,
		// then depth, then width, so that is the order the packing has to sort in.
		int previous = Integer.MIN_VALUE;
		for (int y = 0; y < 4; y++) {
			for (int z = 0; z < 4; z++) {
				for (int x = 0; x < 4; x++) {
					final int packed = StructurePreview.pack(x, y, z);
					assertTrue(packed > previous, "packing does not ascend at " + x + "," + y + "," + z);
					previous = packed;
				}
			}
		}
	}

	@Test
	void whatArrivesIsWhatWasSent() {
		final int[][] positions = { { 1, 2, 3 }, { 12, 8, 200 }, { 900, 90, 12 } };
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
			assertEquals(sent.getCellPosition(cell), received.getCellPosition(cell), "cell " + cell + " arrived elsewhere");
			assertEquals(sent.getCellPaletteIndex(cell), received.getCellPaletteIndex(cell));
		}
	}

	@Test
	void theBlocksWithRenderersOfTheirOwnTravelToo() {
		// Sent apart from the shell, since they are drawn apart from it
		final int[] componentPositions = { StructurePreview.pack(3, 1, 4), StructurePreview.pack(9, 12, 2) };
		final int[] componentStates = { 41, 77 };
		final StructurePreview sent = new StructurePreview(64, 64, 64, 1, 64, 64, 64, 2, 0, false,
				new int[] { 7 }, new int[] { StructurePreview.pack(0, 0, 0) }, new int[] { 0 },
				componentPositions, componentStates);

		final FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		sent.write(buf);
		final StructurePreview received = StructurePreview.read(buf);

		assertEquals(0, buf.readableBytes());
		assertEquals(2, received.getComponentCount());
		assertEquals(3, received.getComponentX(0));
		assertEquals(1, received.getComponentY(0));
		assertEquals(4, received.getComponentZ(0));
		assertEquals(9, received.getComponentX(1));
		assertEquals(12, received.getComponentY(1));
		assertEquals(2, received.getComponentZ(1));
	}

	@Test
	void aStructureThatFitsTheGridIsShownBlockForBlock() {
		// Nothing is worth shrinking until it is larger than the grid it has to fit on
		assertEquals(1, StructurePreviewBuilder.stepFor(7, 5, 8, 48));
		assertEquals(1, StructurePreviewBuilder.stepFor(48, 48, 48, 48));
	}

	@Test
	void nothingTheGameItselfAddsIsEverShrunkToFitTheGrid() {
		// The largest thing the game generates spans a few hundred blocks across: an ancient city
		// reaches a little over two hundred, a mineshaft is held to eighty each way from where it
		// started. The grid is an order of magnitude past any of that, so every one of them is shown
		// one cell to one block, and only a structure from a mod could ever need shrinking.
		assertEquals(1, StructurePreviewBuilder.stepFor(256, 128, 256, StructurePreview.MAX_GRID));
		assertEquals(1, StructurePreviewBuilder.stepFor(512, 384, 512, StructurePreview.MAX_GRID));
		assertEquals(1, StructurePreviewBuilder.stepFor(StructurePreview.MAX_GRID, 64, 64, StructurePreview.MAX_GRID));
		// And past the grid it does shrink, rather than packing two cells into one position
		assertTrue(StructurePreviewBuilder.stepFor(StructurePreview.MAX_GRID + 1, 64, 64, StructurePreview.MAX_GRID) > 1);
	}

	@Test
	void aStructureLargerThanTheGridIsShrunkUntilItFits() {
		// The largest side is what has to fit, since one step is used along every axis
		assertEquals(2, StructurePreviewBuilder.stepFor(49, 10, 10, 48));
		assertEquals(2, StructurePreviewBuilder.stepFor(10, 10, 96, 48));
		assertEquals(3, StructurePreviewBuilder.stepFor(97, 20, 30, 48));
		// However coarse it has to be, the whole structure ends up inside the grid
		assertTrue(divideCeil(1000, StructurePreviewBuilder.stepFor(1000, 200, 400, 48)) <= 48);
		// And a grid that is allowed to be larger shrinks the structure less
		assertTrue(StructurePreviewBuilder.stepFor(1000, 200, 400, 192) < StructurePreviewBuilder.stepFor(1000, 200, 400, 48));
	}

	@Test
	void aPreviewSaysWhenNoneOfItCouldBeRead() {
		assertTrue(preview(4, 4).isOutlineOnly(), "every piece was outlined, which is worth saying");
		assertFalse(preview(4, 1).isOutlineOnly(), "only some of the pieces were outlined");
		assertFalse(preview(4, 0).isOutlineOnly(), "every piece was read block for block");
		// A structure that assembled into nothing at all has nothing to say either way
		assertFalse(preview(0, 0).isOutlineOnly());
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
		return new StructurePreview(1024, 1024, 1024, 2, 2048, 1024, 1536, 3, 1, false, new int[] { 7 }, packed, paletteIndices, new int[0], new int[0]);
	}

	private static StructurePreview preview(int pieces, int outlinedPieces) {
		return new StructurePreview(4, 4, 4, 1, 4, 4, 4, pieces, outlinedPieces, false, new int[0], new int[0], new int[0], new int[0], new int[0]);
	}

}
