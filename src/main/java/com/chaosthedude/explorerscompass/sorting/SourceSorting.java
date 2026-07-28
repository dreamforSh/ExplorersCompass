package com.chaosthedude.explorerscompass.sorting;

import com.chaosthedude.explorerscompass.util.SearchTarget;
import com.chaosthedude.explorerscompass.util.StructureUtils;

import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class SourceSorting implements ISorting {

	@Override
	public Comparable<?> getValue(SearchTarget searchTarget, ResourceLocation key) {
		return StructureUtils.getPrettySourceName(key);
	}

	@Override
	public ISorting next() {
		return new DimensionSorting();
	}

	@Override
	public String getLocalizedName() {
		return I18n.get("string.explorerscompass.source");
	}

}
