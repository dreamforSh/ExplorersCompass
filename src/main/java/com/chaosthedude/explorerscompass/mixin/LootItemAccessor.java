package com.chaosthedude.explorerscompass.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.core.Holder;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.storage.loot.entries.LootItem;

/**
 * Lets a preview read which item a loot-table entry names.
 *
 * <p><b>What is opened up.</b> The item held by a {@code minecraft:item} entry. That is the common
 * case in chest tables, and there is no public getter for it.
 *
 * <p><b>Why an accessor.</b> This reads a field and changes no behaviour.
 */
@Mixin(LootItem.class)
public interface LootItemAccessor {

	@Accessor("item")
	Holder<Item> explorerscompass$getItem();

}
