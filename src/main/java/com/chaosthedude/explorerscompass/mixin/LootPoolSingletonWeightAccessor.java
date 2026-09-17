package com.chaosthedude.explorerscompass.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.level.storage.loot.entries.LootPoolSingletonContainer;

/**
 * Lets a preview read the weight a loot-table entry is rolled with.
 *
 * <p><b>What is opened up.</b> The weight of a singleton entry. The chance an item appears follows
 * from that weight against the others in the pool, and there is no public getter for it.
 *
 * <p><b>Why an accessor.</b> This reads a field and changes no behaviour.
 */
@Mixin(LootPoolSingletonContainer.class)
public interface LootPoolSingletonWeightAccessor {

	@Accessor("weight")
	int explorerscompass$getWeight();

}
