package com.chaosthedude.explorerscompass.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * Lets a preview list every item a loot table may drop, without rolling it.
 *
 * <p><b>What is opened up.</b> The pools a table is made of. Rolling a table answers with one
 * particular haul; a preview wants the set of items that could appear, so it walks the pools instead.
 *
 * <p><b>Why a mixin.</b> Nothing public on a loot table hands the pools over. The methods that run
 * them need a loot context built against a real world and a player, which a preview does not have and
 * which would pick a random subset besides.
 *
 * <p><b>Why an accessor.</b> This reads a field and changes no behaviour.
 */
@Mixin(LootTable.class)
public interface LootTablePoolsAccessor {

	@Accessor("pools")
	List<LootPool> explorerscompass$getPools();

}
