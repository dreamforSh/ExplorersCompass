package com.chaosthedude.explorerscompass.sorting;

import com.chaosthedude.explorerscompass.util.SearchTarget;
import com.chaosthedude.explorerscompass.util.StructureUtils;

import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class DimensionSorting implements ISorting {

	@Override
	public Comparable<?> getValue(SearchTarget searchTarget, ResourceLocation key) {
		return StructureUtils.dimensionKeysToString(searchTarget.getDimensionKeys(key));
	}

	@Override
	public ISorting next() {
		return new GroupSorting();
	}

	@Override
	public String getLocalizedName() {
		return I18n.get("string.explorerscompass.dimension");
	}

}
