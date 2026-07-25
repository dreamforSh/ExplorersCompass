package com.chaosthedude.explorerscompass.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.util.StructureUtils;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import net.minecraft.ResourceLocationException;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * Custom structure groups, so that a pack can bundle structures into groups of its own instead of
 * relying on the structure sets they happen to belong to.
 *
 * <p>Read from {@code config/explorerscompass/groups.json}, which holds an array of entries. Each
 * entry names a group and lists the structures it contains, in which {@code *} matches any number
 * of characters and {@code ?} matches exactly one:
 *
 * <pre>
 * [{"group": "explorerscompass:all_villages", "name": "All Villages", "structures": ["minecraft:village_*", "repurposed_structures:village_*"]}]
 * </pre>
 *
 * <p>The first entry that matches a structure wins, and structures no entry matches keep the
 * structure set they belong to. Using the key of an existing structure set extends that group.
 * The optional {@code name} is what the group displays as; without one, groups display the way
 * structures do: translated as {@code structure.<namespace>.<path>} when a translation exists,
 * and prettified from the path otherwise.
 */
public class StructureGroupsConfig {

	private static final String DIRECTORY_NAME = ExplorersCompass.MODID;
	private static final String FILE_NAME = "groups.json";
	private static final String GROUP_FIELD = "group";
	private static final String NAME_FIELD = "name";
	private static final String STRUCTURES_FIELD = "structures";

	// Entry order decides which group wins when several match, so this is a list, not a map
	private static List<GroupEntry> groups = new ArrayList<GroupEntry>();

	// Display names for the groups that configured one, synced to clients alongside the groups
	private static Map<ResourceLocation, String> groupNames = Map.of();

	/**
	 * Reads the file, creating an empty one if it does not exist yet so that it can be found and
	 * edited.
	 */
	public static void load() {
		final Path path = FMLPaths.CONFIGDIR.get().resolve(DIRECTORY_NAME).resolve(FILE_NAME);
		final List<GroupEntry> entries = new ArrayList<GroupEntry>();
		try {
			if (!Files.exists(path)) {
				Files.createDirectories(path.getParent());
				try (Writer writer = Files.newBufferedWriter(path)) {
					writer.write("[]\n");
				}
			} else {
				try (Reader reader = Files.newBufferedReader(path)) {
					readEntries(JsonParser.parseReader(reader), entries);
				}
			}
		} catch (IOException | JsonParseException e) {
			ExplorersCompass.LOGGER.error("Failed to read " + path + ", custom structure groups will not be applied", e);
			return;
		}

		groups = entries;
		final Map<ResourceLocation, String> names = new HashMap<ResourceLocation, String>();
		for (GroupEntry entry : entries) {
			if (entry.name != null) {
				// The first entry for a group wins, matching the matching order
				names.putIfAbsent(entry.groupKey, entry.name);
			}
		}
		groupNames = Collections.unmodifiableMap(names);
		if (!entries.isEmpty()) {
			ExplorersCompass.LOGGER.info("Loaded " + entries.size() + " custom structure groups");
		}
	}

	/** Display names for the groups that configured one. */
	public static Map<ResourceLocation, String> getGroupNames() {
		return groupNames;
	}

	private static void readEntries(JsonElement root, List<GroupEntry> entries) {
		if (!root.isJsonArray()) {
			throw new JsonParseException("Expected an array of entries");
		}

		final JsonArray array = root.getAsJsonArray();
		for (JsonElement element : array) {
			if (!element.isJsonObject()) {
				ExplorersCompass.LOGGER.warn("Ignoring custom group entry that is not an object: " + element);
				continue;
			}

			final JsonObject entry = element.getAsJsonObject();
			if (!entry.has(GROUP_FIELD) || !entry.get(GROUP_FIELD).isJsonPrimitive() || !entry.has(STRUCTURES_FIELD) || !entry.get(STRUCTURES_FIELD).isJsonArray()) {
				ExplorersCompass.LOGGER.warn("Ignoring custom group entry without a group name and a structure list: " + entry);
				continue;
			}

			final ResourceLocation groupKey;
			try {
				groupKey = new ResourceLocation(entry.get(GROUP_FIELD).getAsString());
			} catch (ResourceLocationException e) {
				ExplorersCompass.LOGGER.warn("Ignoring custom group with malformed name " + entry.get(GROUP_FIELD) + ": " + e.getMessage());
				continue;
			}

			String name = null;
			if (entry.has(NAME_FIELD)) {
				if (entry.get(NAME_FIELD).isJsonPrimitive() && entry.getAsJsonPrimitive(NAME_FIELD).isString()) {
					name = entry.get(NAME_FIELD).getAsString();
				} else {
					ExplorersCompass.LOGGER.warn("Ignoring name of group " + groupKey + " that is not a string: " + entry.get(NAME_FIELD));
				}
			}

			final List<Pattern> patterns = new ArrayList<Pattern>();
			for (JsonElement structure : entry.get(STRUCTURES_FIELD).getAsJsonArray()) {
				if (!structure.isJsonPrimitive() || !structure.getAsJsonPrimitive().isString()) {
					ExplorersCompass.LOGGER.warn("Ignoring structure entry of group " + groupKey + " that is not a string: " + structure);
					continue;
				}
				final String glob = structure.getAsString();
				try {
					patterns.add(Pattern.compile(StructureUtils.convertToRegex(glob)));
				} catch (PatternSyntaxException e) {
					ExplorersCompass.LOGGER.warn("Ignoring structure entry " + glob + " of group " + groupKey + ": " + e.getMessage());
				}
			}
			entries.add(new GroupEntry(groupKey, name, patterns));
		}
	}

	/** The custom group configured for the given structure, or null when no entry matches it. */
	public static ResourceLocation getGroupForStructure(ResourceLocation structureKey) {
		if (structureKey == null) {
			return null;
		}
		final String name = structureKey.toString();
		for (GroupEntry entry : groups) {
			for (Pattern pattern : entry.patterns) {
				if (pattern.matcher(name).matches()) {
					return entry.groupKey;
				}
			}
		}
		return null;
	}

	private static class GroupEntry {

		private final ResourceLocation groupKey;
		private final String name;
		private final List<Pattern> patterns;

		private GroupEntry(ResourceLocation groupKey, String name, List<Pattern> patterns) {
			this.groupKey = groupKey;
			this.name = name;
			this.patterns = patterns;
		}

	}

}
