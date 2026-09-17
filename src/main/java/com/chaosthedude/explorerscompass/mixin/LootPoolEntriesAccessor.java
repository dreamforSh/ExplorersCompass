package com.chaosthedude.explorerscompass.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.entries.LootPoolEntryContainer;

/**
 * Lets a preview walk the entries of one pool of a loot table.
 *
 * <p><b>What is opened up.</b> The entries a pool chooses among. A preview reads every one of them
 * rather than rolling the pool, so that the items shown for a chest are what may appear, not what one
 * opening happened to roll.
 *
 * <p><b>Why an accessor.</b> The field is private and nothing public lists the entries without
 * running the pool. This reads it and changes no behaviour.
 */
@Mixin(LootPool.class)
public interface LootPoolEntriesAccessor {

	@Accessor("entries")
	List<LootPoolEntryContainer> explorerscompass$getEntries();

}
