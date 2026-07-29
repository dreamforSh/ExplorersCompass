package com.chaosthedude.explorerscompass.sorting;

import com.chaosthedude.explorerscompass.util.SearchTarget;
import com.chaosthedude.explorerscompass.util.StructureUtils;

import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class GroupSorting implements ISorting {

	@Override
	public Comparable<?> getValue(SearchTarget searchTarget, ResourceLocation key) {
		// A row being sorted may briefly not be in the map, if the server synced a new list while the
		// GUI was open and the list has not been rebuilt yet
		final ResourceLocation groupKey = searchTarget.getGroupKey(key);
		return groupKey != null ? groupKey.toString() : StructureUtils.NO_TYPE_KEY.toString();
	}

	@Override
	public ISorting next() {
		return new NameSorting();
	}

	@Override
	public String getLocalizedName() {
		return I18n.get("string.explorerscompass.group");
	}

}
