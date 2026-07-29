package com.chaosthedude.explorerscompass.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
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
import com.google.common.collect.Multimaps;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * The biome counterpart of {@link StructureUtils}: which biomes the compass may search for, where
 * each of them generates, and which group each belongs to.
 *
 * <p>Biomes carry no equivalent of a structure set, so they are grouped by the tags that say what
 * kind of biome they are. See {@link #computeBiomeKeysToGroupKeys} for how one of them is picked.
 */
public class BiomeUtils {

	/** Tags naming a kind of biome are the ones a biome can be grouped by. */
	private static final String GROUP_TAG_PREFIX = "is_";

	/**
	 * Tags that say which dimension a biome belongs to rather than what kind of biome it is. A
	 * biome falls back to one of them only when it carries no tag that says more than that.
	 */
	private static final Set<String> DIMENSION_TAG_PATHS = Set.of("is_overworld", "is_nether", "is_end");

	/**
	 * The temperature below which whatever falls in a biome falls as snow. This is the same threshold
	 * the game itself reckons by, which it only shifts well above the heights a biome is travelled to
	 * at.
	 */
	private static final float SNOW_TEMPERATURE = 0.15F;

	// Compiled forms of the configured blacklist globs, rebuilt when the config changes. Only ever
	// touched from the server thread.
	private static List<String> cachedBlacklist;
	private static List<Pattern> cachedBlacklistPatterns;

	// The biome data the compass syncs and searches is derived from the worldgen registries and the
	// chunk generators, both of which are fixed for the lifetime of a server, so it is computed once
	// and reused: rebuilding it walks the possible biomes of every dimension and every tag of every
	// biome. The blacklist is part of the key because its config file can be edited while the server
	// runs. Only ever touched from the server thread.
	private static MinecraftServer cachedServer;
	private static List<String> cachedSyncBlacklist;
	private static List<ResourceLocation> cachedAllowedBiomeKeys;
	private static ListMultimap<ResourceLocation, ResourceLocation> cachedDimensionKeys;
	private static Map<ResourceLocation, ResourceLocation> cachedBiomeKeysToGroupKeys;
	private static int cachedDataVersion;

	private BiomeUtils() {
	}

	/**
	 * The keys of every biome the compass may search for. Unlike structures, only the biomes that
	 * some dimension can actually generate are listed: a biome source names the biomes it produces
	 * outright, so a biome missing from all of them is not missing data but a biome that cannot be
	 * found. The returned list is shared and must not be modified.
	 */
	public static List<ResourceLocation> getAllowedBiomeKeys(ServerLevel level) {
		refreshCachedBiomeData(level);
		return cachedAllowedBiomeKeys;
	}

	/**
	 * The dimensions each allowed biome generates in. The returned multimap is shared and must not
	 * be modified.
	 */
	public static ListMultimap<ResourceLocation, ResourceLocation> getGeneratingDimensionsForAllowedBiomes(ServerLevel level) {
		refreshCachedBiomeData(level);
		return cachedDimensionKeys;
	}

	/**
	 * Maps the key of every allowed biome to the key of the group it belongs to. The returned map is
	 * shared and must not be modified.
	 */
	public static Map<ResourceLocation, ResourceLocation> getBiomeKeysToGroupKeys(ServerLevel level) {
		refreshCachedBiomeData(level);
		return cachedBiomeKeysToGroupKeys;
	}

	/** Keys of every allowed biome that belongs to the given group. */
	public static List<ResourceLocation> getBiomeKeysForGroupKey(ServerLevel level, ResourceLocation groupKey) {
		final List<ResourceLocation> biomeKeys = new ArrayList<ResourceLocation>();
		for (Map.Entry<ResourceLocation, ResourceLocation> entry : getBiomeKeysToGroupKeys(level).entrySet()) {
			if (entry.getValue().equals(groupKey)) {
				biomeKeys.add(entry.getKey());
			}
		}
		return biomeKeys;
	}

	/**
	 * Identifies the current contents of the cached biome data. A client that was already sent data
	 * of this version has the current list and does not need it re-sent.
	 */
	public static int getBiomeDataVersion(ServerLevel level) {
		refreshCachedBiomeData(level);
		return cachedDataVersion;
	}

	/**
	 * Drops the cached biome data. Called when the server stops: what is cached here is derived from
	 * that server's registries and chunk generators, and holding on to it would keep the whole server
	 * alive for as long as the game runs.
	 */
	public static void invalidateCache() {
		cachedServer = null;
		cachedSyncBlacklist = null;
		cachedAllowedBiomeKeys = null;
		cachedDimensionKeys = null;
		cachedBiomeKeysToGroupKeys = null;
		cachedBlacklist = null;
		cachedBlacklistPatterns = null;
	}

	/** The biome the given key names in this world, or null when it names none. */
	public static Holder<Biome> getHolderForKey(ServerLevel level, ResourceLocation key) {
		return getBiomeRegistry(level).getHolder(ResourceKey.create(Registries.BIOME, key)).orElse(null);
	}

	public static boolean biomeIsBlacklisted(ResourceLocation biomeKey) {
		if (biomeKey == null) {
			return false;
		}

		final String name = biomeKey.toString();
		for (Pattern pattern : getBlacklistPatterns()) {
			if (pattern.matcher(name).matches()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Recomputes the cached biome data when the server, or the blacklist it was built under, has
	 * changed since the last time.
	 */
	private static void refreshCachedBiomeData(ServerLevel level) {
		final MinecraftServer server = level.getServer();
		final List<String> blacklist = ConfigHandler.GENERAL.biomeBlacklist.get();
		if (server == cachedServer && blacklist.equals(cachedSyncBlacklist)) {
			return;
		}

		final Map<ResourceLocation, List<ResourceLocation>> dimensionsPerBiome = computeDimensionsPerBiome(level);
		final Registry<Biome> registry = getBiomeRegistry(level);
		final List<ResourceLocation> allowedKeys = new ArrayList<ResourceLocation>();
		final ListMultimap<ResourceLocation, ResourceLocation> dimensionKeys = ArrayListMultimap.create();
		// Walked in registry order rather than in the order the dimensions happened to name them, so
		// that the list a client is sent is the same one every time it is rebuilt
		for (Biome biome : registry) {
			final ResourceLocation biomeKey = registry.getKey(biome);
			final List<ResourceLocation> dimensions = biomeKey == null ? null : dimensionsPerBiome.get(biomeKey);
			if (dimensions != null && !biomeIsBlacklisted(biomeKey)) {
				allowedKeys.add(biomeKey);
				dimensionKeys.putAll(biomeKey, dimensions);
			}
		}

		cachedAllowedBiomeKeys = Collections.unmodifiableList(allowedKeys);
		cachedDimensionKeys = Multimaps.unmodifiableListMultimap(dimensionKeys);
		cachedBiomeKeysToGroupKeys = Collections.unmodifiableMap(computeBiomeKeysToGroupKeys(level, allowedKeys));
		cachedServer = server;
		cachedSyncBlacklist = new ArrayList<String>(blacklist);
		cachedDataVersion++;
	}

	private static Map<ResourceLocation, List<ResourceLocation>> computeDimensionsPerBiome(ServerLevel level) {
		final Map<ResourceLocation, List<ResourceLocation>> dimensionsPerBiome = new LinkedHashMap<ResourceLocation, List<ResourceLocation>>();
		for (Map.Entry<ResourceLocation, Set<Holder<Biome>>> entry : StructureUtils.getBiomesPerDimension(level).entrySet()) {
			for (Holder<Biome> holder : entry.getValue()) {
				final Optional<ResourceKey<Biome>> biomeKey = holder.unwrapKey();
				if (biomeKey.isPresent()) {
					dimensionsPerBiome.computeIfAbsent(biomeKey.get().location(), (key) -> new ArrayList<ResourceLocation>()).add(entry.getKey());
				}
			}
		}
		return dimensionsPerBiome;
	}

	/**
	 * Groups the biomes by the most specific tag each of them carries that names a kind of biome.
	 *
	 * <p>Which tag that is, is decided by how many of the listed biomes carry it: a tag two biomes
	 * share says far more about them than one two hundred share. This needs to know nothing about
	 * the tags themselves, so a pack that tags its biomes in its own namespace is grouped as well as
	 * vanilla is, and only the tags that say which dimension a biome belongs to have to be held back
	 * by name, since they are the largest tags of all and would otherwise never be picked.
	 */
	private static Map<ResourceLocation, ResourceLocation> computeBiomeKeysToGroupKeys(ServerLevel level, List<ResourceLocation> allowedBiomeKeys) {
		final Map<ResourceLocation, List<ResourceLocation>> groupTags = new HashMap<ResourceLocation, List<ResourceLocation>>();
		final Map<ResourceLocation, Integer> tagSizes = new HashMap<ResourceLocation, Integer>();
		for (ResourceLocation biomeKey : allowedBiomeKeys) {
			final List<ResourceLocation> tags = getGroupTags(level, biomeKey);
			groupTags.put(biomeKey, tags);
			for (ResourceLocation tag : tags) {
				tagSizes.merge(tag, 1, Integer::sum);
			}
		}

		// Smallest tag first, and ties broken by name so that a biome is grouped the same way every
		// time the data is rebuilt
		final Comparator<ResourceLocation> mostSpecificFirst = Comparator.comparingInt((ResourceLocation tag) -> tagSizes.getOrDefault(tag, 0)).thenComparing(ResourceLocation::toString);
		final Map<ResourceLocation, ResourceLocation> biomeKeysToGroupKeys = new HashMap<ResourceLocation, ResourceLocation>();
		for (ResourceLocation biomeKey : allowedBiomeKeys) {
			final List<ResourceLocation> tags = groupTags.get(biomeKey);
			ResourceLocation groupKey = tags.stream().filter((tag) -> !DIMENSION_TAG_PATHS.contains(tag.getPath())).min(mostSpecificFirst).orElse(null);
			if (groupKey == null) {
				groupKey = tags.stream().min(mostSpecificFirst).orElse(StructureUtils.NO_TYPE_KEY);
			}
			biomeKeysToGroupKeys.put(biomeKey, groupKey);
		}
		return biomeKeysToGroupKeys;
	}

	/** The tags of the given biome that name a kind of biome rather than something it can hold. */
	private static List<ResourceLocation> getGroupTags(ServerLevel level, ResourceLocation biomeKey) {
		final Holder<Biome> holder = getHolderForKey(level, biomeKey);
		if (holder == null) {
			return List.of();
		}
		return holder.tags().map(TagKey::location).filter((tag) -> tag.getPath().startsWith(GROUP_TAG_PREFIX)).toList();
	}

	@OnlyIn(Dist.CLIENT)
	public static String getPrettyBiomeName(ResourceLocation key) {
		if (key == null) {
			return "";
		}
		if (ConfigHandler.CLIENT.translateBiomeNames.get()) {
			final String translationKey = Util.makeDescriptionId("biome", key);
			final String name = I18n.get(translationKey);
			if (!name.equals(translationKey)) {
				return name;
			}
		}
		return StructureUtils.getBasicStructureName(key);
	}

	/**
	 * Display name of a group of biomes derived from its key alone. Unlike
	 * {@link #getPrettyGroupName} this consults no translations, so it is also safe on the server.
	 */
	public static String getBasicGroupName(ResourceLocation groupKey) {
		if (groupKey == null) {
			return "";
		}
		// The "is_" these tags are named with says nothing once one of them is being read as a group
		final String path = groupKey.getPath().startsWith(GROUP_TAG_PREFIX) ? groupKey.getPath().substring(GROUP_TAG_PREFIX.length()) : groupKey.getPath();
		return WordUtils.capitalize(path.replace('_', ' '));
	}

	/**
	 * Display name of a group of biomes: the tag it is named after, read as the kind of biome it
	 * stands for rather than as the tag it is.
	 */
	@OnlyIn(Dist.CLIENT)
	public static String getPrettyGroupName(ResourceLocation groupKey) {
		if (StructureUtils.hasNoGroup(groupKey)) {
			return "";
		}
		final String translationKey = Util.makeDescriptionId("biomegroup", groupKey);
		final String name = I18n.get(translationKey);
		if (!name.equals(translationKey)) {
			return name;
		}
		return getBasicGroupName(groupKey);
	}

	/**
	 * How warm the given biome is, as the number the game's own worldgen reckons it by. Empty when the
	 * client is in no world, or is in one holding no such biome, which happens while the server is
	 * replacing the list a screen is showing.
	 */
	@OnlyIn(Dist.CLIENT)
	public static String getTemperature(ResourceLocation biomeKey) {
		final Biome biome = getClientBiome(biomeKey);
		return biome == null ? "" : String.format("%.2f", biome.getModifiedClimateSettings().temperature());
	}

	/**
	 * What falls in the given biome, which is what its temperature amounts to for whoever is standing
	 * in it. Worked out from the biome's own temperature rather than from a position in it, since a
	 * biome being picked out of a list has no position yet.
	 */
	@OnlyIn(Dist.CLIENT)
	public static String getPrecipitation(ResourceLocation biomeKey) {
		final Biome biome = getClientBiome(biomeKey);
		if (biome == null) {
			return "";
		}

		final Biome.ClimateSettings climate = biome.getModifiedClimateSettings();
		if (!climate.hasPrecipitation()) {
			return I18n.get("string.explorerscompass.precipitation.none");
		}
		return I18n.get(climate.temperature() < SNOW_TEMPERATURE ? "string.explorerscompass.precipitation.snow" : "string.explorerscompass.precipitation.rain");
	}

	/**
	 * The biome the given key names in the world the client is in. The biomes are part of what a
	 * server sends on joining it, so this answers away from single player as well.
	 */
	@OnlyIn(Dist.CLIENT)
	private static Biome getClientBiome(ResourceLocation biomeKey) {
		final ClientLevel level = Minecraft.getInstance().level;
		if (level == null || biomeKey == null) {
			return null;
		}
		return level.registryAccess().registryOrThrow(Registries.BIOME).get(biomeKey);
	}

	private static Registry<Biome> getBiomeRegistry(ServerLevel level) {
		return level.registryAccess().registryOrThrow(Registries.BIOME);
	}

	/**
	 * Compiles the configured blacklist globs, reusing the result until the config changes.
	 * Compiling a pattern costs far more than matching one, and the blacklist is matched against
	 * every biome in the level every time the biome list is built.
	 */
	private static List<Pattern> getBlacklistPatterns() {
		final List<String> blacklist = ConfigHandler.GENERAL.biomeBlacklist.get();
		if (!blacklist.equals(cachedBlacklist)) {
			final List<Pattern> patterns = new ArrayList<Pattern>(blacklist.size());
			for (String glob : blacklist) {
				try {
					patterns.add(Pattern.compile(StructureUtils.convertToRegex(glob)));
				} catch (PatternSyntaxException e) {
					ExplorersCompass.LOGGER.warn("Ignoring biome blacklist entry " + glob + ": " + e.getMessage());
				}
			}
			cachedBlacklist = new ArrayList<String>(blacklist);
			cachedBlacklistPatterns = patterns;
		}
		return cachedBlacklistPatterns;
	}

}
