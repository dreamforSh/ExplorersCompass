package com.chaosthedude.explorerscompass.worker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

import com.mojang.datafixers.util.Pair;

import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/**
 * Works out where a structure would generate, from the world seed and the generator's noise alone.
 *
 * <p>This asks world generation the question chunk storage otherwise answers out of the chunks it
 * has already written. {@link Structure#findGenerationPoint} decides where in a chunk a structure
 * would stand, and the biome where it lands decides whether it may stand there at all; those are the
 * two conditions the chunk generator itself applies before it creates a structure start, so an
 * answer here is the answer storage would come to give for a chunk nobody has generated yet.
 *
 * <p>A structure set holding several structures only ever puts one of them in a chunk. The chunk
 * generator draws them in an order of its own, weighted and seeded from the chunk, and the first one
 * that can stand there is the one it generates, whether a search is looking for it or not. Nether
 * fortresses and bastions share a set and most of their biomes, so asking about the fortress alone
 * would answer yes for about every other chunk that ends up holding a bastion. The draw is therefore
 * made here the same way, over every structure of the set.
 *
 * <p>None of it reads or writes any part of the world, and the game runs all of it on its own
 * worldgen threads while generating chunks, so a search may use it off the server thread. What a
 * search may not do off the server thread is ask {@code StructureCheck} instead: the caches it
 * answers from are ordinary maps, and writing to them from anywhere else would corrupt them.
 *
 * <p>An answer can still differ from what a chunk holds, for a chunk that was generated under
 * settings that have since changed. A search therefore only decides where to keep looking by this,
 * and puts the location it settles on to storage before the compass is pointed at it.
 */
final class StructurePrediction {

	/** One structure of a set, how heavily it is drawn, and the biomes it may generate in. */
	private record Member(Structure structure, int weight, Predicate<Holder<Biome>> validBiome) {
	}

	private final RegistryAccess registryAccess;
	private final ServerLevel level;
	private final ChunkGenerator generator;
	private final BiomeSource biomeSource;
	private final RandomState randomState;
	private final Climate.Sampler sampler;
	private final StructureTemplateManager templateManager;
	private final long seed;

	/**
	 * Every set the placement being searched puts in the world, with every structure each of them
	 * holds — the ones being searched for and the ones that can take a chunk from them alike. The
	 * biomes each may generate in are resolved once here rather than asked of the structure every
	 * time: the set of biomes a structure names is a list, so answering from it walks the whole list,
	 * and this is asked at least once for every location a search samples.
	 */
	private final List<List<Member>> sets;

	/** The structures being searched for, which are the only ones worth answering with. */
	private final Set<Structure> targets;

	StructurePrediction(ServerLevel level, StructurePlacement placement, List<Structure> structures) {
		this.level = level;
		registryAccess = level.registryAccess();
		generator = level.getChunkSource().getGenerator();
		biomeSource = generator.getBiomeSource();
		randomState = level.getChunkSource().randomState();
		sampler = randomState.sampler();
		templateManager = level.getServer().getStructureManager();
		// What the chunk generator seeds structure generation with, which a mod giving a dimension a
		// seed of its own can tell apart from the level's
		seed = level.getChunkSource().getGeneratorState().getLevelSeed();

		targets = Collections.newSetFromMap(new IdentityHashMap<Structure, Boolean>());
		targets.addAll(structures);

		final Map<Structure, Predicate<Holder<Biome>>> validBiomes = new IdentityHashMap<Structure, Predicate<Holder<Biome>>>();
		sets = new ArrayList<List<Member>>();
		// The search was split up by placement, and each set has a placement of its own, so the sets
		// this one search walks are the ones holding that very placement
		for (Holder<StructureSet> holder : level.getChunkSource().getGeneratorState().possibleStructureSets()) {
			if (!holder.isBound() || holder.value().placement() != placement) {
				continue;
			}

			final List<Member> members = new ArrayList<Member>();
			for (StructureSet.StructureSelectionEntry entry : holder.value().structures()) {
				if (entry.structure().isBound()) {
					final Structure structure = entry.structure().value();
					members.add(new Member(structure, entry.weight(), validBiomes.computeIfAbsent(structure, StructurePrediction::validBiomePredicate)));
				}
			}
			if (!members.isEmpty()) {
				sets.add(members);
			}
		}

		if (sets.isEmpty()) {
			// No set of the generator's holds this placement, so there is no draw to make: each structure
			// is asked about on its own, which is all that could be done before
			for (Structure structure : structures) {
				sets.add(List.of(new Member(structure, 1, validBiomes.computeIfAbsent(structure, StructurePrediction::validBiomePredicate))));
			}
		}
	}

	/**
	 * The structure being searched for that world generation would put in the given chunk, and the
	 * position it would generate at, or null when it would put none of them there. A chunk the draw
	 * gives to a structure nobody is searching for answers null as well: that one is what the chunk
	 * holds, so none of the others can be.
	 */
	Pair<Structure, BlockPos> structureAt(ChunkPos chunkPos) {
		for (List<Member> set : sets) {
			final Pair<Structure, BlockPos> generated;
			if (set.size() == 1) {
				// The generator makes no draw for a set of one, so there is no random source to seed either
				generated = attempt(set.get(0), chunkPos);
			} else {
				final WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
				random.setLargeFeatureSeed(seed, chunkPos.x, chunkPos.z);
				generated = firstDrawn(set, Member::weight, random, (member) -> attempt(member, chunkPos));
			}

			if (generated != null && targets.contains(generated.getFirst())) {
				return generated;
			}
		}
		return null;
	}

	/**
	 * Draws entries the way the chunk generator draws the structures of a set for a chunk, and answers
	 * with what the first one that can generate there comes to. Each draw is weighted by what is left,
	 * and an entry that cannot generate is dropped before the next one, so every entry is tried at most
	 * once. Answers null when none of them can.
	 *
	 * <p>Written to make the same calls on the random source in the same order as
	 * {@code ChunkGenerator.createStructures}, since which entry comes out depends on every one of
	 * them: seeded the same way, it draws the same entries.
	 */
	static <E, R> R firstDrawn(List<E> entries, ToIntFunction<E> weight, RandomSource random, Function<E, R> attempt) {
		final List<E> remaining = new ArrayList<E>(entries);
		int total = 0;
		for (E entry : remaining) {
			total += weight.applyAsInt(entry);
		}

		while (!remaining.isEmpty()) {
			int drawn = random.nextInt(total);
			int index = 0;
			for (E entry : remaining) {
				drawn -= weight.applyAsInt(entry);
				if (drawn < 0) {
					break;
				}
				index++;
			}

			final E entry = remaining.get(index);
			final R result = attempt.apply(entry);
			if (result != null) {
				return result;
			}

			remaining.remove(index);
			total -= weight.applyAsInt(entry);
		}
		return null;
	}

	/**
	 * The position a structure would generate at in the given chunk, paired with it, or null when it
	 * would not generate there.
	 */
	private Pair<Structure, BlockPos> attempt(Member member, ChunkPos chunkPos) {
		final Predicate<Holder<Biome>> validBiome = member.validBiome();
		final Structure.GenerationContext context = new Structure.GenerationContext(registryAccess, generator, biomeSource, randomState, templateManager, seed, chunkPos, level, validBiome);
		final Optional<Structure.GenerationStub> stub = member.structure().findGenerationPoint(context);
		if (stub.isEmpty()) {
			return null;
		}

		// Which biome decides this is the one where the structure ended up standing rather than the one
		// the chunk is named after, and a structure settles wherever in its chunk the ground suits it
		final BlockPos pos = stub.get().position();
		return validBiome.test(biomeSource.getNoiseBiome(QuartPos.fromBlock(pos.getX()), QuartPos.fromBlock(pos.getY()), QuartPos.fromBlock(pos.getZ()), sampler)) ? Pair.of(member.structure(), pos) : null;
	}

	/**
	 * Whether a biome is one the given structure may generate in. The biomes themselves are compared
	 * rather than the holders naming them: the holder a biome source hands back while sampling need
	 * not be the one a structure listed, and comparing holders would take every sample for a miss.
	 */
	private static Predicate<Holder<Biome>> validBiomePredicate(Structure structure) {
		final Set<Biome> biomes = new ReferenceOpenHashSet<Biome>();
		for (Holder<Biome> holder : structure.biomes()) {
			// A data pack can name a biome that failed to load, and reading the value of such a holder
			// throws instead of answering with nothing
			if (holder.isBound()) {
				biomes.add(holder.value());
			}
		}
		return (holder) -> holder.isBound() && biomes.contains(holder.value());
	}

}
