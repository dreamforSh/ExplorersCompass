package com.chaosthedude.explorerscompass.util;

import java.util.List;

import com.chaosthedude.explorerscompass.ExplorersCompass;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * What the compass looks for. Structures and biomes are listed, filtered, searched and reported in
 * the same way, and differ only in where the data behind them comes from and in how their names
 * resolve, so everything that works with either asks the target rather than deciding for itself.
 */
public enum SearchTarget {

	STRUCTURE(0), BIOME(1);

	private final int id;

	SearchTarget(int id) {
		this.id = id;
	}

	public int getID() {
		return id;
	}

	/** The target with the given id, falling back to structures for an id no target carries. */
	public static SearchTarget fromID(int id) {
		for (SearchTarget target : values()) {
			if (target.getID() == id) {
				return target;
			}
		}

		// A compass written before biomes could be searched for records no target at all, and back
		// then everything a compass ever pointed at was a structure
		return STRUCTURE;
	}

	public SearchTarget next() {
		return this == STRUCTURE ? BIOME : STRUCTURE;
	}

	/**
	 * Display name derived from the key alone. Unlike {@link #getPrettyName} this consults no
	 * translations, so it is also safe to use on the server.
	 */
	public String getBasicName(ResourceLocation key) {
		// Both kinds of key prettify the same way, and this is the mod's one place that does it
		return StructureUtils.getBasicStructureName(key);
	}

	/**
	 * Display name of a group derived from its key alone. Unlike {@link #getPrettyGroupName} this
	 * consults no translations, so it is also safe to use on the server.
	 */
	public String getBasicGroupName(ResourceLocation groupKey) {
		return this == BIOME ? BiomeUtils.getBasicGroupName(groupKey) : StructureUtils.getBasicStructureName(groupKey);
	}

	@OnlyIn(Dist.CLIENT)
	public String getPrettyName(ResourceLocation key) {
		return this == BIOME ? BiomeUtils.getPrettyBiomeName(key) : StructureUtils.getPrettyStructureName(key);
	}

	/** Display name of a group of this kind of target. */
	@OnlyIn(Dist.CLIENT)
	public String getPrettyGroupName(ResourceLocation groupKey) {
		return this == BIOME ? BiomeUtils.getPrettyGroupName(groupKey) : StructureUtils.getPrettyGroupName(groupKey);
	}

	/** The keys the compass may search for, as the server last synced them. */
	@OnlyIn(Dist.CLIENT)
	public List<ResourceLocation> getAllowedKeys() {
		return this == BIOME ? ExplorersCompass.allowedBiomeKeys : ExplorersCompass.allowedStructureKeys;
	}

	/**
	 * The group the given key belongs to, or null when it belongs to none. That also covers a key the
	 * synced list does not hold at all, which happens while the server is replacing the list the
	 * screen is showing: neither one has a group to name or to search for, and answering with the key
	 * that stands in for having no group would leave every caller checking for it separately.
	 */
	@OnlyIn(Dist.CLIENT)
	public ResourceLocation getGroupKey(ResourceLocation key) {
		final ResourceLocation groupKey = this == BIOME ? ExplorersCompass.biomeKeysToGroupKeys.get(key) : ExplorersCompass.structureKeysToTypeKeys.get(key);
		return StructureUtils.hasNoGroup(groupKey) ? null : groupKey;
	}

	/** The dimensions the given key generates in, empty when they could not be determined. */
	@OnlyIn(Dist.CLIENT)
	public List<ResourceLocation> getDimensionKeys(ResourceLocation key) {
		return this == BIOME ? ExplorersCompass.dimensionKeysForAllowedBiomeKeys.get(key) : ExplorersCompass.dimensionKeysForAllowedStructureKeys.get(key);
	}

	/** What this kind of target is called where the screens name it. */
	public String getNameTranslationKey() {
		return this == BIOME ? "string.explorerscompass.biomes" : "string.explorerscompass.structures";
	}

	/** The title of the screen that picks one of these. */
	public String getSelectTranslationKey() {
		return this == BIOME ? "string.explorerscompass.selectBiome" : "string.explorerscompass.selectStructure";
	}

	/** What the list says when the filters have left nothing in it. */
	public String getNoneMatchTranslationKey() {
		return this == BIOME ? "string.explorerscompass.noBiomesMatch" : "string.explorerscompass.noStructuresMatch";
	}

}
