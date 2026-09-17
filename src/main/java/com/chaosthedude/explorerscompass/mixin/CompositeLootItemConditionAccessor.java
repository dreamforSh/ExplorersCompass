package com.chaosthedude.explorerscompass.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.level.storage.loot.predicates.CompositeLootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;

/**
 * Lets a preview walk the terms of an all-of or any-of loot condition.
 *
 * <p><b>What is opened up.</b> The child conditions. Combining their chances is how a nested
 * {@code random_chance} still contributes to the probability shown on a chest.
 *
 * <p><b>Why an accessor.</b> This reads a field and changes no behaviour.
 */
@Mixin(CompositeLootItemCondition.class)
public interface CompositeLootItemConditionAccessor {

	@Accessor("terms")
	List<LootItemCondition> explorerscompass$getTerms();

}
