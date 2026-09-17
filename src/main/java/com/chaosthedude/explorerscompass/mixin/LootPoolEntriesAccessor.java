package com.chaosthedude.explorerscompass.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.entries.LootPoolEntryContainer;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;

/**
 * Lets a preview walk the entries of one pool of a loot table, and the conditions the pool itself
 * is rolled under.
 *
 * <p><b>What is opened up.</b> The entries a pool chooses among, and the conditions that decide
 * whether the pool runs at all. A preview reads every entry rather than rolling the pool, so that
 * the items shown for a chest are what may appear, not what one opening happened to roll.
 *
 * <p><b>Why an accessor.</b> The fields are private and nothing public lists them without running
 * the pool. This reads them and changes no behaviour.
 */
@Mixin(LootPool.class)
public interface LootPoolEntriesAccessor {

	@Accessor("entries")
	List<LootPoolEntryContainer> explorerscompass$getEntries();

	@Accessor("conditions")
	List<LootItemCondition> explorerscompass$getConditions();

}
