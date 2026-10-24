package com.chaosthedude.explorerscompass.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.mojang.datafixers.util.Pair;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

/**
 * Walks the grid search over placement grids of the test's own making and holds what it settles on
 * against the nearest location there actually is, found by looking at every cell that could hold it.
 */
class GridStructureSearchWorkerTest {

	private static final ResourceLocation KEY = ResourceLocation.fromNamespaceAndPath("test", "structure");

	/** Far more than any of these searches look at, so that only the radius ends them. */
	private static final int MAX_SAMPLES = 1000000;

	private static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(60L);

	/** Spacing and separation of the placements walked here, in chunks, the village's among them. */
	private static final int[][] PLACEMENTS = { { 34, 8 }, { 32, 8 }, { 20, 11 }, { 12, 4 }, { 80, 20 } };

	@AfterEach
	void dropSharedState() {
		// Starting a search registers its manager with the scheduler, and a search allowed its own
		// threads starts the shared ones
		SearchScheduler.shutdown();
		SearchExecutor.shutdown();
	}

	/** A context that records what the search reported, instead of writing it to a compass. */
	private static final class RecordingContext extends SearchContext {

		private BlockPos locatedPos;
		private boolean notFound;
		private int notFoundRadius;

		private RecordingContext(BlockPos start, int maxRadius) {
			super(null, null, null, start, new ArrayList<BlockPos>(), false, false, maxRadius, false);
		}

		@Override
		public boolean holdsCompass() {
			return true;
		}

		@Override
		void reportRadius(int radius) {
		}

		@Override
		void reportLocated(BlockPos pos, ResourceLocation key, int totalSamples) {
			locatedPos = pos;
		}

		@Override
		void reportNotFound(int radius, int totalSamples) {
			notFound = true;
			notFoundRadius = radius;
		}

	}

	/**
	 * A placement grid of the test's own making. Every cell of it stands for one chunk somewhere in its
	 * first so many chunks each way, chosen from the cell alone the way a random spread placement
	 * chooses one, and what world generation and chunk storage say about that chunk is decided from the
	 * cell as well.
	 */
	private static final class FakeWorld {

		private final long seed;
		private final int spacing;
		private final int spread;
		private final Vec3i locateOffset;

		// What world generation says is in a cell, and what storage says
		private final Predicate<ChunkPos> predicted;
		private final Predicate<ChunkPos> stored;

		private FakeWorld(long seed, int spacing, int separation, Vec3i locateOffset, Predicate<ChunkPos> predicted, Predicate<ChunkPos> stored) {
			this.seed = seed;
			this.spacing = spacing;
			this.spread = spacing - separation;
			this.locateOffset = locateOffset;
			this.predicted = predicted;
			this.stored = stored;
		}

		/**
		 * A world in which world generation predicts a structure for so many of the cells, and storage
		 * agrees about so many of those — the rest stand for chunks generated under other settings.
		 * Storage also holds a structure in so many cells world generation knows nothing about, which a
		 * search walking its own threads has no way of seeing.
		 */
		private static FakeWorld random(long seed, int spacing, int separation, Vec3i locateOffset, double predictedChance, double storedChance, double unpredictedStoredChance) {
			final FakeWorld[] world = new FakeWorld[1];
			world[0] = new FakeWorld(seed, spacing, separation, locateOffset,
					(chunkPos) -> world[0].roll(chunkPos, 1) < predictedChance,
					(chunkPos) -> world[0].roll(chunkPos, 1) < predictedChance ? world[0].roll(chunkPos, 2) < storedChance : world[0].roll(chunkPos, 3) < unpredictedStoredChance);
			return world[0];
		}

		/** The one chunk of the cell the given chunk falls in that a structure there would stand in. */
		private ChunkPos candidate(int chunkX, int chunkZ) {
			final int cellX = Math.floorDiv(chunkX, spacing);
			final int cellZ = Math.floorDiv(chunkZ, spacing);
			final Random random = cellRandom(cellX, cellZ, 0);
			return new ChunkPos(cellX * spacing + random.nextInt(spread), cellZ * spacing + random.nextInt(spread));
		}

		private double roll(ChunkPos chunkPos, int salt) {
			return cellRandom(Math.floorDiv(chunkPos.x, spacing), Math.floorDiv(chunkPos.z, spacing), salt).nextDouble();
		}

		private Random cellRandom(int cellX, int cellZ, int salt) {
			return new Random(seed ^ (cellX * 341873128712L + cellZ * 132897987541L) ^ (salt * 0x9E3779B97F4A7C15L));
		}

		private BlockPos locatePos(ChunkPos chunkPos) {
			return new BlockPos(chunkPos.getMinBlockX() + locateOffset.getX(), 0, chunkPos.getMinBlockZ() + locateOffset.getZ());
		}

		/**
		 * How far out, squared, the nearest location within the radius that holds what is asked for
		 * lies, or -1 when none does. Looks at every cell that could hold one.
		 */
		private long nearestSqr(BlockPos start, int maxRadius, Predicate<ChunkPos> holds) {
			final int cells = maxRadius / SectionPos.sectionToBlockCoord(spacing) + 2;
			final int startCellX = Math.floorDiv(SectionPos.blockToSectionCoord(start.getX()), spacing);
			final int startCellZ = Math.floorDiv(SectionPos.blockToSectionCoord(start.getZ()), spacing);
			long nearest = -1L;
			for (int cellX = startCellX - cells; cellX <= startCellX + cells; cellX++) {
				for (int cellZ = startCellZ - cells; cellZ <= startCellZ + cells; cellZ++) {
					final ChunkPos chunkPos = candidate(cellX * spacing, cellZ * spacing);
					final BlockPos locatePos = locatePos(chunkPos);
					final long distanceSqr = distanceSqr(start, locatePos);
					if (distanceSqr <= (long) maxRadius * maxRadius && (nearest < 0L || distanceSqr < nearest) && holds.test(chunkPos)) {
						nearest = distanceSqr;
					}
				}
			}
			return nearest;
		}

		/** What a search walking its own threads can find: what world generation predicts and storage agrees about. */
		private boolean predictedAndStored(ChunkPos chunkPos) {
			return predicted.test(chunkPos) && stored.test(chunkPos);
		}

	}

	/**
	 * The grid search over a world of the test's own making. Storage is only ever asked from the thread
	 * the test runs the search on, which stands in for the server thread, and the worker notes it if
	 * it is asked from anywhere else.
	 */
	private static final class FakeWorker extends GridStructureSearchWorker<StructurePlacement> {

		private final FakeWorld world;
		private final Thread serverThread = Thread.currentThread();
		private volatile boolean storageAskedOffThread;

		private FakeWorker(SearchContext context, FakeWorld world, boolean async) {
			super(context, null, List.of(), world.spacing, world.locateOffset, new Setup(MAX_SAMPLES, world.seed, null, true, async));
			this.world = world;
		}

		@Override
		protected ChunkPos candidateChunk(int chunkX, int chunkZ) {
			return world.candidate(chunkX, chunkZ);
		}

		@Override
		protected BlockPos getLocatePos(ChunkPos chunkPos) {
			return world.locatePos(chunkPos);
		}

		@Override
		protected boolean canPlaceAt(ChunkPos chunkPos) {
			return true;
		}

		@Override
		protected Pair<BlockPos, Structure> predictStructureGeneratingAt(ChunkPos chunkPos) {
			return world.predicted.test(chunkPos) ? Pair.<BlockPos, Structure>of(world.locatePos(chunkPos), null) : null;
		}

		@Override
		protected Pair<BlockPos, Structure> confirmStructureAt(ChunkPos chunkPos, Structure predicted, int predictedY) {
			return readStorage(chunkPos);
		}

		@Override
		protected Pair<BlockPos, Structure> getStructureGeneratingAt(ChunkPos chunkPos) {
			return readStorage(chunkPos);
		}

		private Pair<BlockPos, Structure> readStorage(ChunkPos chunkPos) {
			if (Thread.currentThread() != serverThread) {
				storageAskedOffThread = true;
			}
			return world.stored.test(chunkPos) ? Pair.<BlockPos, Structure>of(world.locatePos(chunkPos), null) : null;
		}

		@Override
		protected void found(BlockPos pos, Structure structure) {
			found(pos, KEY);
		}

		@Override
		protected String getName() {
			return "FakeGridWorker";
		}

	}

	/** Runs a search to the end, the way the server thread would, and answers with what it reported. */
	private static RecordingContext search(FakeWorld world, BlockPos start, int maxRadius, boolean async) throws InterruptedException {
		final RecordingContext context = new RecordingContext(start, maxRadius);
		final FakeWorker worker = new FakeWorker(context, world, async);
		final SearchWorkerManager manager = new SearchWorkerManager();
		manager.createWorkers(context, List.of(worker));
		assertTrue(manager.start());

		final long deadline = System.nanoTime() + TIMEOUT_NANOS;
		while (manager.hasWork()) {
			if (!manager.doWork()) {
				if (System.nanoTime() - deadline > 0L) {
					throw new AssertionError("the search never finished");
				}
				// Its threads are walking, and there is nothing for the server thread to do until they stop
				Thread.sleep(1L);
			}
		}

		assertFalse(worker.storageAskedOffThread, "chunk storage was asked from off the server thread");
		assertTrue(context.locatedPos != null || context.notFound, "the search ended without reporting anything");
		return context;
	}

	private static long distanceSqr(BlockPos start, BlockPos pos) {
		final long x = pos.getX() - start.getX();
		final long z = pos.getZ() - start.getZ();
		return x * x + z * z;
	}

	private static BlockPos randomStart(Random random) {
		return new BlockPos(random.nextInt(200000) - 100000, 64, random.nextInt(200000) - 100000);
	}

	/** Radius enough for a few cells of the widest placement, small enough to walk quickly. */
	private static int radiusFor(int spacing) {
		return Math.max(2500, 4 * SectionPos.sectionToBlockCoord(spacing));
	}

	private static void assertNearest(String what, BlockPos start, long nearestSqr, RecordingContext context) {
		if (nearestSqr < 0L) {
			assertNull(context.locatedPos, what + ": located " + context.locatedPos + " where nothing is");
			return;
		}
		assertNotNull(context.locatedPos, what + ": located nothing, with the nearest " + Math.sqrt((double) nearestSqr) + " blocks away");
		assertEquals(Math.sqrt((double) nearestSqr), Math.sqrt((double) distanceSqr(start, context.locatedPos)), 0.0, what + ": settled on " + context.locatedPos + " rather than the nearest");
	}

	@Test
	void theSearchSettlesOnTheNearestStructureWhereverInItsCellItLies() throws InterruptedException {
		final Random random = new Random(1L);
		for (int[] placement : PLACEMENTS) {
			for (int trial = 0; trial < 40; trial++) {
				final double chance = trial % 2 == 0 ? 1.0 : 0.3;
				final Vec3i locateOffset = trial % 4 < 2 ? Vec3i.ZERO : new Vec3i(9, 0, -7);
				final FakeWorld world = FakeWorld.random(random.nextLong(), placement[0], placement[1], locateOffset, 0.0, 0.0, chance);
				final BlockPos start = randomStart(random);
				final int maxRadius = radiusFor(placement[0]);

				// A cell the walk has yet to reach can stand for a location nearer than one it has already
				// reached, since where in its cell a location falls is as arbitrary as where in its own cell
				// the search started. Stopping on the first band that covers a find leaves those out.
				assertNearest("spacing " + placement[0] + "/" + placement[1] + " from " + start, start, world.nearestSqr(start, maxRadius, world.stored), search(world, start, maxRadius, false));
			}
		}
	}

	@Test
	void aSearchOnItsOwnThreadsSettlesOnTheNearestStructureStorageAgreesAbout() throws InterruptedException {
		final Random random = new Random(2L);
		for (int[] placement : PLACEMENTS) {
			for (int trial = 0; trial < 12; trial++) {
				// About half of what world generation predicts is not what storage holds, which is roughly
				// how often it was wrong about the nether fortress before it drew structures the way the
				// generator does — and still what a world generated under other settings can look like
				final FakeWorld world = FakeWorld.random(random.nextLong(), placement[0], placement[1], trial % 2 == 0 ? Vec3i.ZERO : new Vec3i(-4, 0, 11), 0.6, 0.4, 0.2);
				final BlockPos start = randomStart(random);
				final int maxRadius = radiusFor(placement[0]);

				assertNearest("spacing " + placement[0] + "/" + placement[1] + " from " + start, start, world.nearestSqr(start, maxRadius, world::predictedAndStored), search(world, start, maxRadius, true));
			}
		}
	}

	@Test
	void storageTurningDownTheNearestPredictionsDoesNotEndTheSearch() throws InterruptedException {
		final BlockPos start = new BlockPos(39999, 64, -32857);
		final int maxRadius = 4000;
		// World generation predicts a structure in every cell, and storage only agrees about the ones a
		// long way out: everything nearer was generated under other settings
		final FakeWorld[] world = new FakeWorld[1];
		world[0] = new FakeWorld(7L, 27, 4, Vec3i.ZERO, (chunkPos) -> true, (chunkPos) -> distanceSqr(start, world[0].locatePos(chunkPos)) >= 1500L * 1500L);

		final RecordingContext context = search(world[0], start, maxRadius, true);

		// Storage turning down what the walk stopped at is no reason to believe there is nothing beyond
		// it, so the walk has to carry on from there
		assertNearest("from " + start, start, world[0].nearestSqr(start, maxRadius, world[0]::predictedAndStored), context);
	}

	@Test
	void aSearchStorageAgreesWithNowhereHasCoveredTheWholeRadius() throws InterruptedException {
		final BlockPos start = new BlockPos(1234, 64, -5678);
		final int maxRadius = 3000;
		final FakeWorld world = new FakeWorld(11L, 27, 4, Vec3i.ZERO, (chunkPos) -> true, (chunkPos) -> false);

		for (boolean async : new boolean[] { false, true }) {
			final RecordingContext context = search(world, start, maxRadius, async);

			// What it reports having covered is what a search for a further instance goes by, and the
			// compass shows it: one that gave up at the first location storage turned down has not
			// covered the radius, and must not say it has
			assertTrue(context.notFound, "located " + context.locatedPos + " where nothing is");
			assertEquals(maxRadius, context.notFoundRadius, "async " + async);
		}
	}

	@Test
	void noCellTheWalkHasNotReachedHoldsALocationInsideTheRadiusItHasCovered() {
		final Random random = new Random(3L);
		for (int trial = 0; trial < 200; trial++) {
			final int strideChunks = 1 + random.nextInt(40);
			final BlockPos start = randomStart(random);
			final Vec3i locateOffset = new Vec3i(random.nextInt(33) - 16, 0, random.nextInt(33) - 16);
			final int cellBlocks = SectionPos.sectionToBlockCoord(strideChunks);
			final int cellSpread = GridStructureSearchWorker.cellSpread(start, strideChunks, locateOffset);
			final int startCellX = Math.floorDiv(SectionPos.blockToSectionCoord(start.getX()), strideChunks);
			final int startCellZ = Math.floorDiv(SectionPos.blockToSectionCoord(start.getZ()), strideChunks);

			final DiscWalker walker = new DiscWalker();
			final Set<Long> visited = new HashSet<Long>();
			final int bands = 6;
			while (walker.getCoveredLength() <= bands) {
				visited.add(ChunkPos.asLong(walker.getX(), walker.getZ()));
				walker.advance();

				final int covered = GridStructureSearchWorker.coveredRadius(walker.getCoveredLength(), cellBlocks, cellSpread);
				for (int x = -bands - 3; x <= bands + 3; x++) {
					for (int z = -bands - 3; z <= bands + 3; z++) {
						if (visited.contains(ChunkPos.asLong(x, z))) {
							continue;
						}
						// The nearest the location this cell stands for could possibly be: any chunk of the
						// placement's cell, reported from its corner
						final long dx = nearestAlong(start.getX(), (startCellX + x) * strideChunks, strideChunks, locateOffset.getX());
						final long dz = nearestAlong(start.getZ(), (startCellZ + z) * strideChunks, strideChunks, locateOffset.getZ());
						assertTrue(dx * dx + dz * dz >= (long) covered * covered, "stride " + strideChunks + " from " + start + ": cell " + x + "," + z + " is not walked yet and could be nearer than the " + covered + " covered");
					}
				}
			}
		}
	}

	/** The nearest a location reported from any chunk of a cell can lie to the start, along one axis. */
	private static long nearestAlong(int startBlock, int cellStartChunk, int strideChunks, int locateOffset) {
		long nearest = Long.MAX_VALUE;
		for (int chunk = cellStartChunk; chunk < cellStartChunk + strideChunks; chunk++) {
			nearest = Math.min(nearest, Math.abs((long) SectionPos.sectionToBlockCoord(chunk) + locateOffset - startBlock));
		}
		return nearest;
	}

}
