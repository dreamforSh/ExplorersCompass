package com.chaosthedude.explorerscompass.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.level.storage.loot.entries.CompositeEntryBase;
import net.minecraft.world.level.storage.loot.entries.LootPoolEntryContainer;

/**
 * Lets a preview walk the children of an alternatives, group or sequence entry.
 *
 * <p><b>What is opened up.</b> The child entries. A preview reads all of them, since it is listing
 * what may appear rather than picking one branch the way a roll would.
 *
 * <p><b>Why an accessor.</b> The field is protected and this class is not a subclass of the entry.
 * Reading it changes no behaviour.
 */
@Mixin(CompositeEntryBase.class)
public interface CompositeEntryBaseAccessor {

	@Accessor("children")
	List<LootPoolEntryContainer> explorerscompass$getChildren();

}
