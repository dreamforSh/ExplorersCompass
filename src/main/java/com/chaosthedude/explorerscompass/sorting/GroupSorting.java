package com.chaosthedude.explorerscompass.sorting;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.util.StructureUtils;

import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class GroupSorting implements ISorting {

	@Override
	public int compare(ResourceLocation key1, ResourceLocation key2) {
		return getTypeKey(key1).compareTo(getTypeKey(key2));
	}

	@Override
	public Object getValue(ResourceLocation key) {
		// Falls back like compare does, and never returns null: the sort caches these values and
		// compares them directly
		return getTypeKey(key);
	}

	@Override
	public ISorting next() {
		return new NameSorting();
	}

	@Override
	public String getLocalizedName() {
		return I18n.get("string.explorerscompass.group");
	}

	// A structure being sorted may briefly not be in the map, if the server synced a new structure
	// list while the GUI was open and the list has not been rebuilt yet
	private ResourceLocation getTypeKey(ResourceLocation key) {
		ResourceLocation typeKey = ExplorersCompass.structureKeysToTypeKeys.get(key);
		return typeKey != null ? typeKey : StructureUtils.NO_TYPE_KEY;
	}

}
