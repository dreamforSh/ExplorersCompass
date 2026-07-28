package com.chaosthedude.explorerscompass.sorting;

import com.chaosthedude.explorerscompass.util.SearchTarget;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public interface ISorting {

	/**
	 * What the list is ordered by. The values are computed once per row and compared against each
	 * other, so this must never return null and must return the same kind of value every time.
	 */
	public Comparable<?> getValue(SearchTarget searchTarget, ResourceLocation key);

	public ISorting next();

	public String getLocalizedName();

}
