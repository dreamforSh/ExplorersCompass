package com.chaosthedude.explorerscompass.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.apache.commons.lang3.text.WordUtils;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;

import net.minecraft.Util;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureSet.StructureSelectionEntry;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModList;

public class StructureUtils {

	/** Group of a structure that does not belong to any structure set. */
	public static final ResourceLocation NO_TYPE_KEY = new ResourceLocation(ExplorersCompass.MODID, "none");

	private static final ResourceLocation STRONGHOLD_KEY = new ResourceLocation("minecraft", "stronghold");
	private static final ResourceLocation OVERWORLD_KEY = new ResourceLocation("minecraft", "overworld");

	private static final String REGEX_METACHARACTERS = "\\.[]{}()+^$|";

	// Compiled forms of the configured blacklist globs, rebuilt when the config changes. Only ever
	// touched from the server thread.
	private static List<String> cachedBlacklist;
	private static List<Pattern> cachedBlacklistPatterns;

	/**
	 * Maps the key of every structure in the level to the key of the structure set it belongs to, or
	 * to {@link #NO_TYPE_KEY} when it belongs to none. Walks the structure sets once and inverts
	 * them, rather than scanning all of them again for every structure the way
	 * {@link #getTypeForStructure} has to.
	 */
	public static Map<ResourceLocation, ResourceLocation> getStructureKeysToTypeKeys(ServerLevel level) {
		final Registry<Structure> structureRegistry = getStructureRegistry(level);
		final Registry<StructureSet> setRegistry = getStructureSetRegistry(level);
		final Map<ResourceLocation, ResourceLocation> structureKeysToTypeKeys = new HashMap<ResourceLocation, ResourceLocation>();

		for (StructureSet set : setRegistry) {
			final ResourceLocation setKey = setRegistry.getKey(set);
			for (StructureSelectionEntry entry : set.structures()) {
				// A structure set from a data pack can reference a structure that failed to load, and reading
				// the value of such a holder throws instead of returning null
				if (!entry.structure().isBound()) {
					continue;
				}

				final ResourceLocation structureKey = structureRegistry.getKey(entry.structure().value());
				if (structureKey != null && setKey != null) {
					// The first set that lists a structure wins, matching getTypeForStructure
					structureKeysToTypeKeys.putIfAbsent(structureKey, setKey);
				}
			}
		}

		for (Structure structure : structureRegistry) {
			final ResourceLocation structureKey = structureRegistry.getKey(structure);
			if (structureKey != null) {
				structureKeysToTypeKeys.putIfAbsent(structureKey, NO_TYPE_KEY);
			}
		}

		return structureKeysToTypeKeys;
	}

	/** Keys of every structure that belongs to the given structure set. */
	public static List<ResourceLocation> getStructureKeysForTypeKey(ServerLevel level, ResourceLocation typeKey) {
		final List<ResourceLocation> structureKeys = new ArrayList<ResourceLocation>();
		for (Map.Entry<ResourceLocation, ResourceLocation> entry : getStructureKeysToTypeKeys(level).entrySet()) {
			if (entry.getValue().equals(typeKey)) {
				structureKeys.add(entry.getKey());
			}
		}
		return structureKeys;
	}

	/**
	 * Returns the key of the structure set the given structure belongs to, or {@link #NO_TYPE_KEY}
	 * when it belongs to none. Prefer {@link #getStructureKeysToTypeKeys} when resolving more than a
	 * few structures.
	 */
	public static ResourceLocation getTypeForStructure(ServerLevel level, Structure structure) {
		Registry<StructureSet> registry = getStructureSetRegistry(level);
		for (StructureSet set : registry) {
			for (StructureSelectionEntry entry : set.structures()) {
				// A structure set from a data pack can reference a structure that failed to load, and reading
				// the value of such a holder throws instead of returning null
				if (entry.structure().isBound() && entry.structure().value().equals(structure)) {
					return registry.getKey(set);
				}
			}
		}
		return NO_TYPE_KEY;
	}

	public static ResourceLocation getKeyForStructure(ServerLevel level, Structure structure) {
		return getStructureRegistry(level).getKey(structure);
	}

	public static Structure getStructureForKey(ServerLevel level, ResourceLocation key) {
		return getStructureRegistry(level).get(key);
	}
	
	public static Holder<Structure> getHolderForStructure(ServerLevel level, Structure structure) {
		Optional<ResourceKey<Structure>> optional = getStructureRegistry(level).getResourceKey(structure);
		if (optional.isPresent()) {
			return getStructureRegistry(level).getHolderOrThrow(optional.get());
		}
		return null;
	}

	public static List<ResourceLocation> getAllowedStructureKeys(ServerLevel level) {
		final List<ResourceLocation> structures = new ArrayList<ResourceLocation>();
		for (Structure structure : getStructureRegistry(level)) {
			final ResourceLocation structureKey = getKeyForStructure(level, structure);
			if (structureKey != null && !structureIsBlacklisted(level, structure)) {
				structures.add(structureKey);
			}
		}
		return structures;
	}

	public static boolean structureIsBlacklisted(ServerLevel level, Structure structure) {
		final ResourceLocation structureKey = getKeyForStructure(level, structure);
		if (structureKey == null) {
			return false;
		}

		final String name = structureKey.toString();
		for (Pattern pattern : getBlacklistPatterns()) {
			if (pattern.matcher(name).matches()) {
				return true;
			}
		}
		return false;
	}

	public static List<ResourceLocation> getGeneratingDimensionKeys(ServerLevel serverLevel, Structure structure) {
		return getGeneratingDimensionKeys(serverLevel, structure, getBiomesPerDimension(serverLevel));
	}

	public static ListMultimap<ResourceLocation, ResourceLocation> getGeneratingDimensionsForAllowedStructures(ServerLevel serverLevel) {
		// Collect the biomes of each dimension once, instead of once per structure
		final Map<ResourceLocation, Set<Holder<Biome>>> biomesPerDimension = getBiomesPerDimension(serverLevel);
		final ListMultimap<ResourceLocation, ResourceLocation> dimensionsForAllowedStructures = ArrayListMultimap.create();
		for (ResourceLocation structureKey : getAllowedStructureKeys(serverLevel)) {
			final Structure structure = getStructureForKey(serverLevel, structureKey);
			if (structure != null) {
				dimensionsForAllowedStructures.putAll(structureKey, getGeneratingDimensionKeys(serverLevel, structure, biomesPerDimension));
			}
		}
		return dimensionsForAllowedStructures;
	}

	private static Map<ResourceLocation, Set<Holder<Biome>>> getBiomesPerDimension(ServerLevel serverLevel) {
		final Map<ResourceLocation, Set<Holder<Biome>>> biomesPerDimension = new LinkedHashMap<ResourceLocation, Set<Holder<Biome>>>();
		for (ServerLevel level : serverLevel.getServer().getAllLevels()) {
			final ChunkGenerator chunkGenerator = level.getChunkSource().getGenerator();
			biomesPerDimension.put(level.dimension().location(), chunkGenerator.getBiomeSource().possibleBiomes());
		}
		return biomesPerDimension;
	}

	private static List<ResourceLocation> getGeneratingDimensionKeys(ServerLevel serverLevel, Structure structure, Map<ResourceLocation, Set<Holder<Biome>>> biomesPerDimension) {
		final List<ResourceLocation> dimensions = new ArrayList<ResourceLocation>();
		for (Map.Entry<ResourceLocation, Set<Holder<Biome>>> entry : biomesPerDimension.entrySet()) {
			for (Holder<Biome> biome : structure.biomes()) {
				if (entry.getValue().contains(biome)) {
					dimensions.add(entry.getKey());
					break;
				}
			}
		}
		// Fix empty dimensions for stronghold
		if (dimensions.isEmpty() && STRONGHOLD_KEY.equals(getKeyForStructure(serverLevel, structure))) {
			dimensions.add(OVERWORLD_KEY);
		}
		return dimensions;
	}

	public static int getHorizontalDistanceToLocation(Player player, int x, int z) {
		return getHorizontalDistanceToLocation(player.blockPosition(), x, z);
	}

	/**
	 * Horizontal distance in blocks between a position and a location. Called several times for
	 * every location a search samples, so it avoids allocating a position for the location, and
	 * works in doubles: at the radii this can be configured for, the squared distance no longer fits
	 * in a float without losing thousands of blocks of precision.
	 */
	public static int getHorizontalDistanceToLocation(BlockPos startPos, int x, int z) {
		return (int) Math.sqrt(getHorizontalDistanceSqrToLocation(startPos, x, z));
	}

	/** Squared horizontal distance in blocks, for comparing distances without taking a square root. */
	public static long getHorizontalDistanceSqrToLocation(BlockPos startPos, int x, int z) {
		final long distanceX = x - startPos.getX();
		final long distanceZ = z - startPos.getZ();
		return distanceX * distanceX + distanceZ * distanceZ;
	}

	@OnlyIn(Dist.CLIENT)
	public static String getPrettyStructureName(ResourceLocation key) {
		if (key == null) {
			return "";
		}
		String name = key.toString();
		if (ConfigHandler.CLIENT.translateStructureNames.get()) {
			name = I18n.get(Util.makeDescriptionId("structure", key));
		}
		if (name.equals(Util.makeDescriptionId("structure", key)) || !ConfigHandler.CLIENT.translateStructureNames.get()) {
			name = key.toString();
			if (name.contains(":")) {
				name = name.substring(name.indexOf(":") + 1);
			}
			name = WordUtils.capitalize(name.replace('_', ' '));
		}
		return name;
	}

	@OnlyIn(Dist.CLIENT)
	public static String getPrettyStructureSource(ResourceLocation key) {
		if (key == null) {
			return "";
		}
		String registryEntry = key.toString();
		String modid = registryEntry.substring(0, registryEntry.indexOf(":"));
		if (modid.equals("minecraft")) {
			return "Minecraft";
		}
		Optional<? extends ModContainer> sourceContainer = ModList.get().getModContainerById(modid);
		if (sourceContainer.isPresent()) {
			return sourceContainer.get().getModInfo().getDisplayName();
		}
		return modid;
	}

	@OnlyIn(Dist.CLIENT)
	public static String dimensionKeysToString(List<ResourceLocation> dimensions) {
		Set<String> dimensionNames = new HashSet<String>();
		dimensions.forEach((key) -> dimensionNames.add(getDimensionName(key)));
		return String.join(", ", dimensionNames);
	}

	@OnlyIn(Dist.CLIENT)
	private static String getDimensionName(ResourceLocation dimensionKey) {
		String name = I18n.get(Util.makeDescriptionId("dimension", dimensionKey));
		if (name.equals(Util.makeDescriptionId("dimension", dimensionKey))) {
			name = dimensionKey.toString();
			if (name.contains(":")) {
				name = name.substring(name.indexOf(":") + 1);
			}
			name = WordUtils.capitalize(name.replace('_', ' '));
		}
		return name;
	}

	private static Registry<Structure> getStructureRegistry(ServerLevel level) {
		return level.registryAccess().ownedRegistryOrThrow(Registry.STRUCTURE_REGISTRY);
	}

	private static Registry<StructureSet> getStructureSetRegistry(ServerLevel level) {
		return level.registryAccess().ownedRegistryOrThrow(Registry.STRUCTURE_SET_REGISTRY);
	}

	/**
	 * Compiles the configured blacklist globs, reusing the result until the config changes.
	 * Compiling a pattern costs far more than matching one, and the blacklist is matched against
	 * every structure in the level every time the structure list is built.
	 */
	private static List<Pattern> getBlacklistPatterns() {
		final List<String> blacklist = ConfigHandler.GENERAL.structureBlacklist.get();
		if (!blacklist.equals(cachedBlacklist)) {
			final List<Pattern> patterns = new ArrayList<Pattern>(blacklist.size());
			for (String glob : blacklist) {
				try {
					patterns.add(Pattern.compile(convertToRegex(glob)));
				} catch (PatternSyntaxException e) {
					ExplorersCompass.LOGGER.warn("Ignoring blacklist entry " + glob + ": " + e.getMessage());
				}
			}
			cachedBlacklist = new ArrayList<String>(blacklist);
			cachedBlacklistPatterns = patterns;
		}
		return cachedBlacklistPatterns;
	}

	/**
	 * Translates a glob, in which {@code *} matches any number of characters and {@code ?} matches
	 * exactly one, into an equivalent regular expression. Every other character is matched
	 * literally.
	 */
	private static String convertToRegex(String glob) {
		final StringBuilder regex = new StringBuilder(glob.length() + 2).append('^');
		for (int i = 0; i < glob.length(); i++) {
			final char c = glob.charAt(i);
			if (c == '*') {
				regex.append(".*");
			} else if (c == '?') {
				regex.append('.');
			} else {
				if (REGEX_METACHARACTERS.indexOf(c) >= 0) {
					regex.append('\\');
				}
				regex.append(c);
			}
		}
		return regex.append('$').toString();
	}

}