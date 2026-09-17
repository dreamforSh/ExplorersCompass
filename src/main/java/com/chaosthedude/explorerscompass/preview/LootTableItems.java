package com.chaosthedude.explorerscompass.preview;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import com.chaosthedude.explorerscompass.mixin.CompositeEntryBaseAccessor;
import com.chaosthedude.explorerscompass.mixin.LootItemAccessor;
import com.chaosthedude.explorerscompass.mixin.LootPoolEntriesAccessor;
import com.chaosthedude.explorerscompass.mixin.LootTablePoolsAccessor;
import com.chaosthedude.explorerscompass.mixin.NestedLootTableAccessor;
import com.chaosthedude.explorerscompass.mixin.TagEntryAccessor;
import com.mojang.datafixers.util.Either;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.CompositeEntryBase;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.entries.LootPoolEntryContainer;
import net.minecraft.world.level.storage.loot.entries.NestedLootTable;
import net.minecraft.world.level.storage.loot.entries.TagEntry;

/**
 * The items a loot table may drop, without opening it.
 *
 * <p>A preview shows what a chest can hold, not what one particular opening rolled. Walking the
 * table's entries — and ignoring the conditions and functions that decide a roll — is what answers
 * that. Nested tables and item tags are followed; anything a custom entry type from a mod might add
 * is skipped, and the table is still named.
 *
 * <p>Built once per preview so that a village of many chests sharing a handful of tables does not
 * walk the same table for every chest.
 */
final class LootTableItems {

	/** Far more than any vanilla chest table names; more than this is trimmed so a packet stays small. */
	static final int MAX_ITEMS = 64;

	private final MinecraftServer server;

	LootTableItems(MinecraftServer server) {
		this.server = server;
	}

	/** The distinct items the given table may drop, as ids into the item registry, in the order they were met. */
	int[] itemsOf(ResourceKey<LootTable> key) {
		final LinkedHashSet<Item> items = new LinkedHashSet<Item>();
		final Set<ResourceLocation> visited = new HashSet<ResourceLocation>();
		visited.add(key.location());
		collect(server.reloadableRegistries().getLootTable(key), items, visited);
		return toIds(items);
	}

	private void collect(LootTable table, LinkedHashSet<Item> items, Set<ResourceLocation> visited) {
		if (table == null || table == LootTable.EMPTY || items.size() >= MAX_ITEMS) {
			return;
		}
		for (LootPool pool : ((LootTablePoolsAccessor) table).explorerscompass$getPools()) {
			for (LootPoolEntryContainer entry : ((LootPoolEntriesAccessor) pool).explorerscompass$getEntries()) {
				collectEntry(entry, items, visited);
				if (items.size() >= MAX_ITEMS) {
					return;
				}
			}
		}
	}

	private void collectEntry(LootPoolEntryContainer entry, LinkedHashSet<Item> items, Set<ResourceLocation> visited) {
		if (items.size() >= MAX_ITEMS) {
			return;
		}
		if (entry instanceof LootItem lootItem) {
			add(((LootItemAccessor) lootItem).explorerscompass$getItem().value(), items);
			return;
		}
		if (entry instanceof TagEntry tagEntry) {
			for (Holder<Item> holder : BuiltInRegistries.ITEM.getTagOrEmpty(((TagEntryAccessor) tagEntry).explorerscompass$getTag())) {
				add(holder.value(), items);
				if (items.size() >= MAX_ITEMS) {
					return;
				}
			}
			return;
		}
		if (entry instanceof NestedLootTable nested) {
			final Either<ResourceKey<LootTable>, LootTable> contents = ((NestedLootTableAccessor) nested).explorerscompass$getContents();
			contents.ifLeft((key) -> {
				if (visited.add(key.location())) {
					collect(server.reloadableRegistries().getLootTable(key), items, visited);
				}
			}).ifRight((table) -> collect(table, items, visited));
			return;
		}
		if (entry instanceof CompositeEntryBase) {
			for (LootPoolEntryContainer child : ((CompositeEntryBaseAccessor) entry).explorerscompass$getChildren()) {
				collectEntry(child, items, visited);
				if (items.size() >= MAX_ITEMS) {
					return;
				}
			}
		}
	}

	private static void add(Item item, LinkedHashSet<Item> items) {
		if (item != null && item != Items.AIR && items.size() < MAX_ITEMS) {
			items.add(item);
		}
	}

	private static int[] toIds(LinkedHashSet<Item> items) {
		final int[] ids = new int[items.size()];
		int index = 0;
		for (Item item : items) {
			ids[index++] = Item.getId(item);
		}
		return ids;
	}

}
