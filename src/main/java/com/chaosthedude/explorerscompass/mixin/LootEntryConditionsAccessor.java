package com.chaosthedude.explorerscompass.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.level.storage.loot.entries.LootPoolEntryContainer;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;

/**
 * Lets a preview read the conditions on a loot-table entry.
 *
 * <p><b>What is opened up.</b> The conditions that decide whether an entry is in the roll at all,
 * most often a {@code random_chance}. They are what turn a weight into a probability rather than a
 * share of a pool that always runs.
 *
 * <p><b>Why an accessor.</b> This reads a field and changes no behaviour.
 */
@Mixin(LootPoolEntryContainer.class)
public interface LootEntryConditionsAccessor {

	@Accessor("conditions")
	List<LootItemCondition> explorerscompass$getConditions();

}
