package com.chaosthedude.explorerscompass.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.chaosthedude.explorerscompass.items.ExplorersCompassItem;

import net.minecraft.core.BlockPos;

class LocatedBiomePatchesTest {

	private static final BlockPos START = new BlockPos(0, 64, 0);
	private static final int SPACING = 32;
	private static final int SURFACE_QUART_Y = 16;

	/** A biome that fills everything between the given corners, and nothing else. */
	private static LocatedBiomePatches.BiomeProbe rectangle(int minX, int minZ, int maxX, int maxZ) {
		return (blockX, blockZ, quartY) -> blockX >= minX && blockX <= maxX && blockZ >= minZ && blockZ <= maxZ;
	}

	private static LocatedBiomePatches patchesOf(LocatedBiomePatches.BiomeProbe probe, BlockPos... located) {
		final SearchContext context = new SearchContext(null, null, null, START,
				new ArrayList<BlockPos>(List.of(located)), false, true, 10000, false);
		return new LocatedBiomePatches(context, probe, SPACING, SURFACE_QUART_Y, 0, 24);
	}

	/** A location the surface layer found, whose own height it could not tell. */
	private static BlockPos atSurface(int x, int z) {
		return new BlockPos(x, ExplorersCompassItem.UNKNOWN_Y, z);
	}

	@Test
	void aFreshSearchHasNothingToPassOver() {
		final LocatedBiomePatches patches = patchesOf(rectangle(-1600, -1600, 1600, 1600));

		// Nothing was located before it, so there is nothing to walk and nothing to wait for
		assertTrue(patches.isBuilt());
		assertEquals(0, patches.getCellCount());
		assertFalse(patches.contains(0, 0));
	}

	@Test
	void thePassedOverRegionReachesAsFarAsTheBiomeDoes() {
		final LocatedBiomePatches patches = patchesOf(rectangle(-1600, -1600, 1600, 1600), atSurface(0, 0));
		patches.build();

		assertTrue(patches.contains(0, 0));
		// The whole of it, rather than the ground right around what was found. This is what keeps a
		// search for a further instance from answering with the next sample along, still inside the
		// ocean it was told to look past.
		assertTrue(patches.contains(1600, 1600), "the far corner of the region was not passed over");
		assertTrue(patches.contains(-1600, 0), "the far edge of the region was not passed over");
		assertFalse(patches.contains(1632, 0), "the ground beyond the region was passed over");
	}

	@Test
	void anotherRegionOfTheSameBiomeIsStillWorthAnswering() {
		// Two patches of one biome with ground between them, which is exactly what a search for a
		// further instance is being asked for
		final LocatedBiomePatches.BiomeProbe twoRegions = (blockX, blockZ, quartY) ->
				rectangle(-320, -320, 320, 320).isTarget(blockX, blockZ, quartY)
						|| rectangle(1280, -320, 1920, 320).isTarget(blockX, blockZ, quartY);
		final LocatedBiomePatches patches = patchesOf(twoRegions, atSurface(0, 0));
		patches.build();

		assertTrue(patches.contains(320, 0));
		assertFalse(patches.contains(1280, 0), "the other region of the same biome was passed over too");
		assertFalse(patches.contains(1600, 0), "the other region of the same biome was passed over too");
	}

	@Test
	void everyRegionAlreadyFoundIsPassedOver() {
		final LocatedBiomePatches.BiomeProbe twoRegions = (blockX, blockZ, quartY) ->
				rectangle(-320, -320, 320, 320).isTarget(blockX, blockZ, quartY)
						|| rectangle(1280, -320, 1920, 320).isTarget(blockX, blockZ, quartY);
		// Pressing for a further instance twice has both of them to look past
		final LocatedBiomePatches patches = patchesOf(twoRegions, atSurface(0, 0), atSurface(1600, 0));
		patches.build();

		assertTrue(patches.contains(320, 0));
		assertTrue(patches.contains(1280, 0));
		assertTrue(patches.contains(1920, 320));
	}

	@Test
	void aRegionUndergroundIsWalkedAtItsOwnHeight() {
		// The biome being looked for reaches the surface in one place and fills the caves in another,
		// which is what the two layers of a biome search are there to tell apart
		// The two lie next to each other on the grid, so walking one looks at the ground the other
		// begins on — which is the whole point: what one height says about a location must not stand
		// for what another height says about it
		final LocatedBiomePatches.BiomeProbe byHeight = (blockX, blockZ, quartY) -> quartY == SURFACE_QUART_Y
				? rectangle(-320, -320, 320, 320).isTarget(blockX, blockZ, quartY)
				: rectangle(352, -320, 960, 320).isTarget(blockX, blockZ, quartY);
		final LocatedBiomePatches patches = patchesOf(byHeight, atSurface(0, 0), new BlockPos(640, 16, 0));
		patches.build();

		assertTrue(patches.contains(0, 0), "the region found at the surface was not passed over");
		assertTrue(patches.contains(320, 0), "the region found at the surface was not passed over in full");
		assertTrue(patches.contains(640, 0), "the region found underground was not passed over");
		assertTrue(patches.contains(352, 0), "the region found underground was not passed over in full");
		assertTrue(patches.contains(960, 320), "the region found underground was not passed over in full");
	}

	@Test
	void aLocationBetweenTwoSamplesStillFindsItsRegion() {
		// The search that found this one started somewhere else, so it does not fall on this grid
		final LocatedBiomePatches patches = patchesOf(rectangle(-320, -320, 320, 320), atSurface(13, -7));
		patches.build();

		assertTrue(patches.contains(0, 0));
		assertTrue(patches.contains(320, 320));
	}

	@Test
	void aLocationThatIsNoLongerTheBiomeLeavesNothingToPassOver() {
		// Somewhere the biome being looked for is not, which a compass carried into another world or a
		// changed data pack can leave behind
		final LocatedBiomePatches patches = patchesOf(rectangle(-320, -320, 320, 320), atSurface(9600, 9600));
		patches.build();

		assertTrue(patches.isBuilt());
		assertEquals(0, patches.getCellCount());
		assertFalse(patches.contains(0, 0));
	}

	@Test
	void aRegionWithNoEndIsGivenOneAndReported() {
		// A biome that never stops must not have a search spend the whole of itself walking it
		final LocatedBiomePatches patches = patchesOf((blockX, blockZ, quartY) -> true, atSurface(0, 0));
		patches.build();

		assertTrue(patches.isBuilt());
		assertTrue(patches.getCellCount() > 0);
		assertTrue(patches.getCellCount() <= 20000,
				"walking a region with no end was not held to what it may cover: " + patches.getCellCount());
	}

	@Test
	void walkingARegionInSlicesReachesTheSameGround() {
		final LocatedBiomePatches atOnce = patchesOf(rectangle(-960, -960, 960, 960), atSurface(0, 0));
		atOnce.build();

		// What a search that is not allowed threads of its own does instead, a turn at a time
		final LocatedBiomePatches inSlices = patchesOf(rectangle(-960, -960, 960, 960), atSurface(0, 0));
		int turns = 0;
		while (!inSlices.advance(LocatedBiomePatches.CELLS_PER_TURN)) {
			if (++turns > 100000) {
				throw new AssertionError("walking the region never finished");
			}
		}

		assertTrue(inSlices.isBuilt());
		assertEquals(atOnce.getCellCount(), inSlices.getCellCount());
		assertTrue(inSlices.contains(960, 960));
		assertFalse(inSlices.contains(992, 0));
	}

}
