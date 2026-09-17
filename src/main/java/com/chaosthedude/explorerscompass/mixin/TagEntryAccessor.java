package com.chaosthedude.explorerscompass.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.storage.loot.entries.TagEntry;

/**
 * Lets a preview expand a loot-table entry that names an item tag.
 *
 * <p><b>What is opened up.</b> The tag the entry points at, and whether it expands into one roll per
 * item. Walking every item in it is how a preview shows the whole of a tag drop rather than one
 * member rolled at random; expansion is what decides whether those items share a chance or compete.
 *
 * <p><b>Why an accessor.</b> This reads a field and changes no behaviour.
 */
@Mixin(TagEntry.class)
public interface TagEntryAccessor {

	@Accessor("tag")
	TagKey<Item> explorerscompass$getTag();

	@Accessor("expand")
	boolean explorerscompass$isExpand();

}
