package com.chaosthedude.explorerscompass.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.core.Holder;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.level.storage.loot.functions.SetPotionFunction;

/**
 * Lets a preview read which potion a {@code set_potion} function fills a bottle with.
 *
 * <p><b>What is opened up.</b> The potion. A bottle in a loot table is the plain item with the
 * potion put in by this function, so without it every potion a trial chamber rewards would show
 * as a bottle of water.
 *
 * <p><b>Why an accessor.</b> This reads a field and changes no behaviour.
 */
@Mixin(SetPotionFunction.class)
public interface SetPotionFunctionAccessor {

	@Accessor("potion")
	Holder<Potion> explorerscompass$getPotion();

}
