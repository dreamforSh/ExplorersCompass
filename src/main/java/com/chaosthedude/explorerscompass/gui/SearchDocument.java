package com.chaosthedude.explorerscompass.gui;

import java.util.List;
import java.util.Locale;

import com.chaosthedude.explorerscompass.util.BiomeUtils;
import com.chaosthedude.explorerscompass.util.SearchTarget;
import com.chaosthedude.explorerscompass.util.StructureUtils;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * The searchable and displayable text of one structure or biome, resolved once when the list is
 * built rather than again for every query and rendered frame.
 */
@OnlyIn(Dist.CLIENT)
final class SearchDocument {

	private static final String SEPARATOR = "\n";

	private final String displayName;
	private final String sourceName;
	private final String groupName;
	private final String dimensions;
	/** How warm this is and what falls on it, both of which only a biome has. Empty otherwise. */
	private final String temperature;
	private final String precipitation;
	private final List<ResourceLocation> dimensionKeys;
	private final String allSearchText;
	private final String idSearchText;
	private final String nameSearchText;
	private final String sourceSearchText;
	private final String groupSearchText;
	private final String dimensionSearchText;

	SearchDocument(SearchTarget searchTarget, ResourceLocation key) {
		displayName = searchTarget.getPrettyName(key);
		sourceName = StructureUtils.getPrettySourceName(key);

		final ResourceLocation groupKey = searchTarget.getGroupKey(key);
		groupName = searchTarget.getPrettyGroupName(groupKey);
		dimensionKeys = List.copyOf(searchTarget.getDimensionKeys(key));
		dimensions = StructureUtils.dimensionKeysToString(dimensionKeys);
		temperature = searchTarget == SearchTarget.BIOME ? BiomeUtils.getTemperature(key) : "";
		precipitation = searchTarget == SearchTarget.BIOME ? BiomeUtils.getPrecipitation(key) : "";

		idSearchText = combine(key.toString(), key.getPath());
		nameSearchText = combine(displayName, searchTarget.getBasicName(key));
		sourceSearchText = combine(key.getNamespace(), sourceName);
		groupSearchText = groupKey == null
				? normalize(groupName)
				: combine(groupKey.toString(), groupKey.getPath(), groupName);

		final StringBuilder dimensionText = new StringBuilder();
		for (ResourceLocation dimensionKey : dimensionKeys) {
			append(dimensionText, dimensionKey.toString());
			append(dimensionText, dimensionKey.getPath());
			append(dimensionText, StructureUtils.getDimensionName(dimensionKey));
		}
		dimensionSearchText = normalize(dimensionText.toString());
		allSearchText = combine(idSearchText, nameSearchText, sourceSearchText,
				groupSearchText, dimensionSearchText);
	}

	/** Builds a document from already resolved text for query parser tests. */
	SearchDocument(String id, String name, String source, String group, String dimension) {
		final ResourceLocation key = ResourceLocation.tryParse(id);
		displayName = name;
		sourceName = source;
		groupName = group;
		dimensions = dimension;
		temperature = "";
		precipitation = "";
		dimensionKeys = List.of();
		idSearchText = combine(id, key == null ? "" : key.getPath());
		nameSearchText = normalize(name);
		sourceSearchText = normalize(source);
		groupSearchText = normalize(group);
		dimensionSearchText = normalize(dimension);
		allSearchText = combine(idSearchText, nameSearchText, sourceSearchText,
				groupSearchText, dimensionSearchText);
	}

	String getDisplayName() {
		return displayName;
	}

	String getSourceName() {
		return sourceName;
	}

	String getGroupName() {
		return groupName;
	}

	String getDimensions() {
		return dimensions;
	}

	String getTemperature() {
		return temperature;
	}

	String getPrecipitation() {
		return precipitation;
	}

	List<ResourceLocation> getDimensionKeys() {
		return dimensionKeys;
	}

	boolean contains(SearchQuery.Field field, String value) {
		return switch (field) {
			case ANY -> allSearchText.contains(value);
			case ID -> idSearchText.contains(value);
			case NAME -> nameSearchText.contains(value);
			case SOURCE -> sourceSearchText.contains(value);
			case GROUP -> groupSearchText.contains(value);
			case DIMENSION -> dimensionSearchText.contains(value);
		};
	}

	static String normalize(String value) {
		return value.toLowerCase(Locale.ROOT);
	}

	private static String combine(String... values) {
		final StringBuilder combined = new StringBuilder();
		for (String value : values) {
			append(combined, value);
		}
		return normalize(combined.toString());
	}

	private static void append(StringBuilder target, String value) {
		if (value == null || value.isEmpty()) {
			return;
		}
		if (target.length() > 0) {
			target.append(SEPARATOR);
		}
		target.append(value);
	}

}
