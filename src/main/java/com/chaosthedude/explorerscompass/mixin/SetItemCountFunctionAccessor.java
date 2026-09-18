package com.chaosthedude.explorerscompass.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.level.storage.loot.functions.SetItemCountFunction;
import net.minecraft.world.level.storage.loot.providers.number.NumberProvider;

/**
 * Lets a preview read how many of an item a {@code set_count} function puts in the stack.
 *
 * <p><b>What is opened up.</b> The number the function draws the count from, and whether it sets
 * the count or adds to it. Running the function would need a loot context and would answer with
 * one roll; a preview wants the range every roll draws from.
 *
 * <p><b>Why an accessor.</b> This reads two fields and changes no behaviour.
 */
@Mixin(SetItemCountFunction.class)
public interface SetItemCountFunctionAccessor {

	@Accessor("value")
	NumberProvider explorerscompass$getValue();

	@Accessor("add")
	boolean explorerscompass$isAdd();

}
