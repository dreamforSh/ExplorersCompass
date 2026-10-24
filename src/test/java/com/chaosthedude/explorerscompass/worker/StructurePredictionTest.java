package com.chaosthedude.explorerscompass.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;

/**
 * Holds the draw a prediction makes over the structures of a set against the one the chunk generator
 * makes. Which structure a chunk ends up holding follows from that draw, so a prediction that draws
 * any other way is wrong about every chunk two structures of the set could both stand in.
 */
class StructurePredictionTest {

	private static final long SEED = 0L;

	/** How the nether fortress and the bastion share their set. */
	private static final Map<String, Integer> NETHER_COMPLEXES = Map.of("fortress", 2, "bastion", 3);
	private static final List<String> NETHER_COMPLEXES_ORDER = List.of("fortress", "bastion");

	private static final int CHUNKS = 4000;

	/** What the chunk generator seeds its draw for a chunk with. */
	private static WorldgenRandom chunkRandom(int chunkX, int chunkZ) {
		final WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
		random.setLargeFeatureSeed(SEED, chunkX, chunkZ);
		return random;
	}

	/**
	 * The draw as {@code ChunkGenerator.createStructures} makes it, written out the way it is there,
	 * with whether each entry can generate standing in for trying to generate it.
	 */
	private static String generatorDraw(List<String> list, Map<String, Integer> weights, Set<String> generates, WorldgenRandom worldgenrandom) {
		final ArrayList<String> arraylist = new ArrayList<String>(list.size());
		arraylist.addAll(list);
		int i = 0;
		for (String entry : arraylist) {
			i += weights.get(entry);
		}

		while (!arraylist.isEmpty()) {
			int j = worldgenrandom.nextInt(i);
			int k = 0;
			for (String entry : arraylist) {
				j -= weights.get(entry);
				if (j < 0) {
					break;
				}
				k++;
			}

			final String entry = arraylist.get(k);
			if (generates.contains(entry)) {
				return entry;
			}
			arraylist.remove(k);
			i -= weights.get(entry);
		}
		return null;
	}

	private static String predictionDraw(List<String> list, Map<String, Integer> weights, Set<String> generates, WorldgenRandom random) {
		return StructurePrediction.firstDrawn(list, weights::get, random, (entry) -> generates.contains(entry) ? entry : null);
	}

	@Test
	void eitherStructureOfASetCanTakeAChunkTheyCouldBothStandIn() {
		int fortresses = 0;
		for (int chunk = 0; chunk < CHUNKS; chunk++) {
			final String drawn = predictionDraw(NETHER_COMPLEXES_ORDER, NETHER_COMPLEXES, NETHER_COMPLEXES.keySet(), chunkRandom(chunk * 27, -chunk * 13));
			if ("fortress".equals(drawn)) {
				fortresses++;
			} else {
				assertEquals("bastion", drawn);
			}
		}

		// Taking whichever structure is listed first, or any structure that can stand there, would
		// answer fortress for every one of these chunks, where the generator puts a bastion in three of
		// every five
		final double share = (double) fortresses / CHUNKS;
		assertTrue(share > 0.35 && share < 0.45, "fortresses took " + share + " of the chunks, where their weight is 2 of 5");
	}

	@Test
	void aStructureThatCannotStandInAChunkLeavesItToTheNextOneDrawn() {
		for (int chunk = 0; chunk < CHUNKS; chunk++) {
			final List<String> tried = new ArrayList<String>();
			final String drawn = StructurePrediction.firstDrawn(NETHER_COMPLEXES_ORDER, NETHER_COMPLEXES::get, chunkRandom(chunk, chunk * 7), (entry) -> {
				tried.add(entry);
				return "fortress".equals(entry) ? entry : null;
			});

			assertEquals("fortress", drawn);
			// The bastion is only ever tried once, and only before the fortress
			assertTrue(tried.size() <= 2 && tried.get(tried.size() - 1).equals("fortress"), "tried " + tried);
		}
	}

	@Test
	void aChunkNoStructureOfTheSetCanStandInHoldsNone() {
		final List<String> tried = new ArrayList<String>();
		final String drawn = StructurePrediction.firstDrawn(NETHER_COMPLEXES_ORDER, NETHER_COMPLEXES::get, chunkRandom(3, 5), (entry) -> {
			tried.add(entry);
			return null;
		});

		assertNull(drawn);
		assertEquals(new HashSet<String>(NETHER_COMPLEXES_ORDER), new HashSet<String>(tried));
		assertEquals(NETHER_COMPLEXES_ORDER.size(), tried.size());
	}

	@Test
	void theDrawIsTheOneTheChunkGeneratorMakes() {
		final Random random = new Random(4L);
		final List<String> names = List.of("a", "b", "c", "d", "e");
		for (int trial = 0; trial < CHUNKS; trial++) {
			// A set of a few structures of their own weights, some of which can stand in the chunk
			final int size = 2 + random.nextInt(names.size() - 1);
			final List<String> set = names.subList(0, size);
			final Map<String, Integer> weights = new HashMap<String, Integer>();
			final Set<String> generates = new HashSet<String>();
			for (String name : set) {
				weights.put(name, 1 + random.nextInt(20));
				if (random.nextInt(3) > 0) {
					generates.add(name);
				}
			}
			final int chunkX = random.nextInt(200000) - 100000;
			final int chunkZ = random.nextInt(200000) - 100000;

			assertEquals(generatorDraw(set, weights, generates, chunkRandom(chunkX, chunkZ)), predictionDraw(set, weights, generates, chunkRandom(chunkX, chunkZ)), "set " + weights + " generating " + generates + " in chunk " + chunkX + "," + chunkZ);
		}
	}

}
