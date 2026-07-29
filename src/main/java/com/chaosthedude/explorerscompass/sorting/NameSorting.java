package com.chaosthedude.explorerscompass.sorting;

import com.chaosthedude.explorerscompass.util.SearchTarget;

import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class NameSorting implements ISorting {

	@Override
	public Comparable<?> getValue(SearchTarget searchTarget, ResourceLocation key) {
		return searchTarget.getPrettyName(key);
	}

	@Override
	public ISorting next() {
		return new SourceSorting();
	}

	@Override
	public String getLocalizedName() {
		return I18n.get("string.explorerscompass.name");
	}

}
