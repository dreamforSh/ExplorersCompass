package com.chaosthedude.explorerscompass.worker;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.Structure;
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

	private final RegistryAccess registryAccess;
	private final ServerLevel level;
	private final ChunkGenerator generator;
	private final BiomeSource biomeSource;
	private final RandomState randomState;
	private final Climate.Sampler sampler;
	private final StructureTemplateManager templateManager;
	private final long seed;

	/**
	 * What counts as a biome each structure may generate in. Resolved once here rather than asked of
	 * the structure every time: the set of biomes a structure names is a list, so answering from it
	 * walks the whole list, and this is asked at least once for every location a search samples.
	 */
	private final Map<Structure, Predicate<Holder<Biome>>> validBiomes;

	StructurePrediction(ServerLevel level, List<Structure> structures) {
		this.level = level;
		registryAccess = level.registryAccess();
		generator = level.getChunkSource().getGenerator();
		biomeSource = generator.getBiomeSource();
		randomState = level.getChunkSource().randomState();
		sampler = randomState.sampler();
		templateManager = level.getServer().getStructureManager();
		seed = level.getSeed();

		validBiomes = new IdentityHashMap<Structure, Predicate<Holder<Biome>>>();
		for (Structure structure : structures) {
			validBiomes.put(structure, validBiomePredicate(structure));
		}
	}

	/**
	 * The position the given structure would generate at in the given chunk, or null when it would
	 * not generate there.
	 */
	BlockPos generationPointAt(Structure structure, ChunkPos chunkPos) {
		final Predicate<Holder<Biome>> validBiome = validBiomes.get(structure);
		if (validBiome == null) {
			return null;
		}

		final Structure.GenerationContext context = new Structure.GenerationContext(registryAccess, generator, biomeSource, randomState, templateManager, seed, chunkPos, level, validBiome);
		final Optional<Structure.GenerationStub> stub = structure.findGenerationPoint(context);
		if (stub.isEmpty()) {
			return null;
		}

		// Which biome decides this is the one where the structure ended up standing rather than the one
		// the chunk is named after, and a structure settles wherever in its chunk the ground suits it
		final BlockPos pos = stub.get().position();
		return validBiome.test(biomeSource.getNoiseBiome(QuartPos.fromBlock(pos.getX()), QuartPos.fromBlock(pos.getY()), QuartPos.fromBlock(pos.getZ()), sampler)) ? pos : null;
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
