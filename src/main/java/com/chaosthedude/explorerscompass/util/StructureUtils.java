package com.chaosthedude.explorerscompass.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.apache.commons.lang3.text.WordUtils;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.chaosthedude.explorerscompass.config.StructureGroupsConfig;
import com.chaosthedude.explorerscompass.items.ExplorersCompassItem;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimaps;

import net.minecraft.Util;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
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

	// The structure data the compass syncs and searches is derived from the worldgen registries,
	// which are fixed for the lifetime of a server, so it is computed once and reused: rebuilding
	// it walks every structure set and matches every structure against the blacklist and the custom
	// groups, which is far too much to repeat on every use of the compass. The blacklist is part of
	// the key because its config file can be edited while the server runs. Only ever touched from
	// the server thread.
	private static MinecraftServer cachedServer;
	private static List<String> cachedSyncBlacklist;
	private static List<ResourceLocation> cachedAllowedStructureKeys;
	private static ListMultimap<ResourceLocation, ResourceLocation> cachedDimensionKeys;
	private static Map<ResourceLocation, ResourceLocation> cachedStructureKeysToTypeKeys;
	private static int cachedDataVersion;

	private StructureUtils() {
	}

	/**
	 * Maps the key of every structure in the level to the key of the group it belongs to: the group
	 * configured for it in {@code groups.json} when there is one, otherwise the structure set it
	 * belongs to, or {@link #NO_TYPE_KEY} when it belongs to neither. The returned map is shared
	 * and must not be modified.
	 */
	public static Map<ResourceLocation, ResourceLocation> getStructureKeysToTypeKeys(ServerLevel level) {
		refreshCachedStructureData(level);
		return cachedStructureKeysToTypeKeys;
	}

	private static Map<ResourceLocation, ResourceLocation> computeStructureKeysToTypeKeys(ServerLevel level) {
		final Registry<Structure> structureRegistry = getStructureRegistry(level);
		final Registry<StructureSet> setRegistry = getStructureSetRegistry(level);
		final Map<ResourceLocation, ResourceLocation> structureKeysToTypeKeys = new HashMap<ResourceLocation, ResourceLocation>();

		// Groups configured in groups.json take priority over the structure sets
		for (Structure structure : structureRegistry) {
			final ResourceLocation structureKey = structureRegistry.getKey(structure);
			final ResourceLocation customGroupKey = StructureGroupsConfig.getGroupForStructure(structureKey);
			if (structureKey != null && customGroupKey != null) {
				structureKeysToTypeKeys.put(structureKey, customGroupKey);
			}
		}

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
					// The first set that lists a structure wins
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

	/** The keys of every structure the compass may search for. The returned list is shared and must not be modified. */
	public static List<ResourceLocation> getAllowedStructureKeys(ServerLevel level) {
		refreshCachedStructureData(level);
		return cachedAllowedStructureKeys;
	}

	private static List<ResourceLocation> computeAllowedStructureKeys(ServerLevel level) {
		final List<ResourceLocation> structures = new ArrayList<ResourceLocation>();
		for (Structure structure : getStructureRegistry(level)) {
			final ResourceLocation structureKey = getKeyForStructure(level, structure);
			if (structureKey != null && !structureIsBlacklisted(level, structure)) {
				structures.add(structureKey);
			}
		}
		return structures;
	}

	/**
	 * Recomputes the cached structure data when the server, or the blacklist it was built under,
	 * has changed since the last time.
	 */
	private static void refreshCachedStructureData(ServerLevel level) {
		final MinecraftServer server = level.getServer();
		final List<String> blacklist = ConfigHandler.GENERAL.structureBlacklist.get();
		if (server == cachedServer && blacklist.equals(cachedSyncBlacklist)) {
			return;
		}

		final List<ResourceLocation> allowedKeys = computeAllowedStructureKeys(level);
		cachedAllowedStructureKeys = Collections.unmodifiableList(allowedKeys);
		cachedDimensionKeys = Multimaps.unmodifiableListMultimap(computeGeneratingDimensionsForAllowedStructures(level, allowedKeys));
		cachedStructureKeysToTypeKeys = Collections.unmodifiableMap(computeStructureKeysToTypeKeys(level));
		cachedServer = server;
		cachedSyncBlacklist = new ArrayList<String>(blacklist);
		cachedDataVersion++;
	}

	/**
	 * Drops the cached structure data. Called when the server stops: what is cached here is derived
	 * from that server's registries, and holding on to it would keep the whole server alive for as
	 * long as the game runs.
	 */
	public static void invalidateCache() {
		cachedServer = null;
		cachedSyncBlacklist = null;
		cachedAllowedStructureKeys = null;
		cachedDimensionKeys = null;
		cachedStructureKeysToTypeKeys = null;
		cachedBlacklist = null;
		cachedBlacklistPatterns = null;
	}

	/**
	 * Identifies the current contents of the cached structure data. A client that was already sent
	 * data of this version has the current list and does not need it re-sent.
	 */
	public static int getStructureDataVersion(ServerLevel level) {
		refreshCachedStructureData(level);
		return cachedDataVersion;
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

	/**
	 * The dimensions each allowed structure can generate in. The returned multimap is shared and
	 * must not be modified.
	 */
	public static ListMultimap<ResourceLocation, ResourceLocation> getGeneratingDimensionsForAllowedStructures(ServerLevel serverLevel) {
		refreshCachedStructureData(serverLevel);
		return cachedDimensionKeys;
	}

	private static ListMultimap<ResourceLocation, ResourceLocation> computeGeneratingDimensionsForAllowedStructures(ServerLevel serverLevel, List<ResourceLocation> allowedStructureKeys) {
		// Collect the biomes of each dimension once, instead of once per structure
		final Map<ResourceLocation, Set<Holder<Biome>>> biomesPerDimension = getBiomesPerDimension(serverLevel);
		final ListMultimap<ResourceLocation, ResourceLocation> dimensionsForAllowedStructures = ArrayListMultimap.create();
		for (ResourceLocation structureKey : allowedStructureKeys) {
			final Structure structure = getStructureForKey(serverLevel, structureKey);
			if (structure != null) {
				dimensionsForAllowedStructures.putAll(structureKey, getGeneratingDimensionKeys(serverLevel, structure, biomesPerDimension));
			}
		}
		return dimensionsForAllowedStructures;
	}

	/**
	 * The biomes each dimension of this server can generate, in the order the server walks its
	 * levels. Shared with {@link BiomeUtils}, which builds the biome list out of the same data.
	 */
	static Map<ResourceLocation, Set<Holder<Biome>>> getBiomesPerDimension(ServerLevel serverLevel) {
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

	/**
	 * The way a located place is written out. A search that could not tell the height leaves it out
	 * rather than writing one that would be wrong, and every readout of a location has to make that
	 * same choice, so they all make it here rather than each spelling it out again.
	 */
	public static String formatCoordinates(int x, int y, int z) {
		return y != ExplorersCompassItem.UNKNOWN_Y ? x + ", " + y + ", " + z : x + ", " + z;
	}

	@OnlyIn(Dist.CLIENT)
	public static String getPrettyStructureName(ResourceLocation key) {
		if (key == null) {
			return "";
		}
		if (ConfigHandler.CLIENT.translateStructureNames.get()) {
			final String translationKey = Util.makeDescriptionId("structure", key);
			final String name = I18n.get(translationKey);
			if (!name.equals(translationKey)) {
				return name;
			}
		}
		return getBasicStructureName(key);
	}

	/**
	 * Display name derived from the key alone. Unlike {@link #getPrettyStructureName} this does not
	 * consult translations, so it is also safe to use on the server.
	 */
	public static String getBasicStructureName(ResourceLocation key) {
		if (key == null) {
			return "";
		}
		return WordUtils.capitalize(key.getPath().replace('_', ' '));
	}

	/**
	 * Whether the given key stands for belonging to no group at all rather than for a group. Both
	 * lists record that the same way: whatever belongs to no group of its own is recorded under
	 * {@link #NO_TYPE_KEY} and sent to the client under it, so there is one thing to ask about it
	 * rather than a null in one place and a key standing in for one in another.
	 */
	public static boolean hasNoGroup(ResourceLocation groupKey) {
		return groupKey == null || groupKey.equals(NO_TYPE_KEY);
	}

	/**
	 * Display name of a group: the name its {@code groups.json} entry configured, when there is
	 * one, and whatever the group key displays as otherwise. Belonging to no group has no name:
	 * prettifying the key that stands for it reads as a group actually called "None", in English
	 * whatever the language, so it is left to whoever displays this to say nothing at all instead.
	 */
	@OnlyIn(Dist.CLIENT)
	public static String getPrettyGroupName(ResourceLocation typeKey) {
		if (hasNoGroup(typeKey)) {
			return "";
		}
		final String customName = ExplorersCompass.groupNames.get(typeKey);
		if (customName != null) {
			return customName;
		}
		return getPrettyStructureName(typeKey);
	}

	/**
	 * Display name of whatever a key came from: the mod's own name where it is known, and its id
	 * otherwise. This reads the namespace alone, so it names the source of a biome as well as of a
	 * structure.
	 */
	@OnlyIn(Dist.CLIENT)
	public static String getPrettySourceName(ResourceLocation key) {
		if (key == null) {
			return "";
		}
		final String modid = key.getNamespace();
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
		// Linked, so that the dimensions always display in the order the server sent them in
		Set<String> dimensionNames = new LinkedHashSet<String>();
		dimensions.forEach((key) -> dimensionNames.add(getDimensionName(key)));
		return String.join(", ", dimensionNames);
	}

	@OnlyIn(Dist.CLIENT)
	public static String getDimensionName(ResourceLocation dimensionKey) {
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
		return level.registryAccess().registryOrThrow(Registries.STRUCTURE);
	}

	private static Registry<StructureSet> getStructureSetRegistry(ServerLevel level) {
		return level.registryAccess().registryOrThrow(Registries.STRUCTURE_SET);
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
	public static String convertToRegex(String glob) {
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