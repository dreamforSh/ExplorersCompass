package com.chaosthedude.explorerscompass.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import com.mojang.datafixers.util.Either;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.NestedLootTable;

/**
 * Lets a preview follow a loot table that names another table.
 *
 * <p><b>What is opened up.</b> The nested table, either as a registry key or as a table written
 * inline. Chest tables reuse village and archaeology tables this way, and walking them is what keeps
 * those items on the preview.
 *
 * <p><b>Why an accessor.</b> This reads a field and changes no behaviour.
 */
@Mixin(NestedLootTable.class)
public interface NestedLootTableAccessor {

	@Accessor("contents")
	Either<ResourceKey<LootTable>, LootTable> explorerscompass$getContents();

}
