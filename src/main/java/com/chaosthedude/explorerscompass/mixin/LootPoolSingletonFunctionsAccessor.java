package com.chaosthedude.explorerscompass.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.level.storage.loot.entries.LootPoolSingletonContainer;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;

/**
 * Lets a preview read the functions a loot-table entry runs on what it drops.
 *
 * <p><b>What is opened up.</b> The functions of a singleton entry. How many of an item a roll
 * puts in the chest, and whether it comes enchanted or as one potion rather than another, are all
 * said by these rather than by the entry itself, and there is no public getter for them.
 *
 * <p><b>Why an accessor.</b> This reads a field and changes no behaviour.
 */
@Mixin(LootPoolSingletonContainer.class)
public interface LootPoolSingletonFunctionsAccessor {

	@Accessor("functions")
	List<LootItemFunction> explorerscompass$getFunctions();

}
