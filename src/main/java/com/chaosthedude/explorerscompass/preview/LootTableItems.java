package com.chaosthedude.explorerscompass.preview;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.chaosthedude.explorerscompass.mixin.CompositeEntryBaseAccessor;
import com.chaosthedude.explorerscompass.mixin.CompositeLootItemConditionAccessor;
import com.chaosthedude.explorerscompass.mixin.LootEntryConditionsAccessor;
import com.chaosthedude.explorerscompass.mixin.LootItemAccessor;
import com.chaosthedude.explorerscompass.mixin.LootPoolEntriesAccessor;
import com.chaosthedude.explorerscompass.mixin.LootPoolSingletonWeightAccessor;
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
import net.minecraft.world.level.storage.loot.entries.AlternativesEntry;
import net.minecraft.world.level.storage.loot.entries.CompositeEntryBase;
import net.minecraft.world.level.storage.loot.entries.EmptyLootItem;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.entries.LootPoolEntryContainer;
import net.minecraft.world.level.storage.loot.entries.LootPoolSingletonContainer;
import net.minecraft.world.level.storage.loot.entries.NestedLootTable;
import net.minecraft.world.level.storage.loot.entries.SequentialEntry;
import net.minecraft.world.level.storage.loot.entries.TagEntry;
import net.minecraft.world.level.storage.loot.predicates.AllOfCondition;
import net.minecraft.world.level.storage.loot.predicates.AnyOfCondition;
import net.minecraft.world.level.storage.loot.predicates.CompositeLootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.InvertedLootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemRandomChanceCondition;
import net.minecraft.world.level.storage.loot.providers.number.BinomialDistributionGenerator;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;
import net.minecraft.world.level.storage.loot.providers.number.NumberProvider;
import net.minecraft.world.level.storage.loot.providers.number.UniformGenerator;

/**
 * The items a loot table may drop, and how likely each one is, without opening it.
 *
 * <p>A preview shows what a chest can hold, not what one particular opening rolled. Walking the
 * table's entries — weights, rolls, and the {@code random_chance} conditions that gate them — is
 * what answers that. Nested tables and item tags are followed; anything a custom entry type from a
 * mod might add is skipped, and the table is still named.
 *
 * <p>The chance stored for an item is the chance it appears at least once when the table is
 * generated: one roll of a pool picks a single entry by weight, several rolls and several pools
 * stack. Luck, enchantment bonuses and conditions that need a player or a block are left out, so a
 * figure is what an unopened chest in the structure would typically do rather than what a particular
 * player would roll.
 *
 * <p>Built once per preview so that a village of many chests sharing a handful of tables does not
 * walk the same table for every chest.
 */
final class LootTableItems {

	/** Far more than any vanilla chest table names; more than this is trimmed so a packet stays small. */
	static final int MAX_ITEMS = 64;
	/** Thousandths of certainty: 1000 is always, 1 is one in a thousand. */
	static final int PERMILLE_ALWAYS = 1000;

	static final class Drops {

		static final Drops EMPTY = new Drops(new int[0], new int[0]);

		final int[] itemIds;
		final int[] permilles;

		Drops(int[] itemIds, int[] permilles) {
			this.itemIds = itemIds;
			this.permilles = permilles;
		}

	}

	private final MinecraftServer server;

	LootTableItems(MinecraftServer server) {
		this.server = server;
	}

	/** The distinct items the given table may drop, commonest first, each with the chance it appears. */
	Drops itemsOf(ResourceKey<LootTable> key) {
		final Map<Item, Double> chances = new HashMap<Item, Double>();
		final Set<ResourceLocation> visited = new HashSet<ResourceLocation>();
		visited.add(key.location());
		collect(server.reloadableRegistries().getLootTable(key), chances, visited);
		return toDrops(chances);
	}

	private void collect(LootTable table, Map<Item, Double> chances, Set<ResourceLocation> visited) {
		if (table == null || table == LootTable.EMPTY) {
			return;
		}
		for (LootPool pool : ((LootTablePoolsAccessor) table).explorerscompass$getPools()) {
			collectPool(pool, chances, visited);
		}
	}

	private void collectPool(LootPool pool, Map<Item, Double> chances, Set<ResourceLocation> visited) {
		final double rolls = expected(pool.getRolls());
		if (rolls <= 0.0D) {
			return;
		}
		final double poolChance = conditions(((LootPoolEntriesAccessor) pool).explorerscompass$getConditions());
		if (poolChance <= 0.0D) {
			return;
		}

		final List<Leaf> leaves = new ArrayList<Leaf>();
		for (LootPoolEntryContainer entry : ((LootPoolEntriesAccessor) pool).explorerscompass$getEntries()) {
			expand(entry, 1.0D, leaves, visited);
		}

		double totalWeight = 0.0D;
		for (Leaf leaf : leaves) {
			totalWeight += leaf.weight * leaf.chance;
		}
		if (totalWeight <= 0.0D) {
			return;
		}

		final Map<Item, Double> perRoll = new HashMap<Item, Double>();
		for (Leaf leaf : leaves) {
			final double selected = (leaf.weight * leaf.chance) / totalWeight;
			if (selected <= 0.0D) {
				continue;
			}
			if (leaf.nestedKey != null || leaf.nestedTable != null) {
				final Map<Item, Double> nested = new HashMap<Item, Double>();
				if (leaf.nestedKey != null) {
					if (visited.add(leaf.nestedKey.location())) {
						collect(server.reloadableRegistries().getLootTable(leaf.nestedKey), nested, visited);
						visited.remove(leaf.nestedKey.location());
					}
				} else {
					collect(leaf.nestedTable, nested, visited);
				}
				for (Map.Entry<Item, Double> drop : nested.entrySet()) {
					perRoll.merge(drop.getKey(), selected * drop.getValue(), Double::sum);
				}
			} else {
				for (Item item : leaf.items) {
					perRoll.merge(item, selected, Double::sum);
				}
			}
		}

		for (Map.Entry<Item, Double> drop : perRoll.entrySet()) {
			final double pRoll = Math.min(1.0D, drop.getValue());
			final double atLeastOnce = 1.0D - Math.pow(1.0D - pRoll, rolls);
			combine(chances, drop.getKey(), poolChance * atLeastOnce);
		}
	}

	private void expand(LootPoolEntryContainer entry, double scale, List<Leaf> leaves, Set<ResourceLocation> visited) {
		final double chance = scale * conditions(((LootEntryConditionsAccessor) entry).explorerscompass$getConditions());
		if (chance <= 0.0D) {
			return;
		}
		if (entry instanceof AlternativesEntry alternatives) {
			double remaining = chance;
			for (LootPoolEntryContainer child : children(alternatives)) {
				final double childChance = conditions(((LootEntryConditionsAccessor) child).explorerscompass$getConditions());
				expand(child, remaining, leaves, visited);
				remaining *= Math.max(0.0D, 1.0D - childChance);
				if (remaining <= 0.0D) {
					return;
				}
			}
			return;
		}
		if (entry instanceof SequentialEntry sequential) {
			double remaining = chance;
			for (LootPoolEntryContainer child : children(sequential)) {
				expand(child, remaining, leaves, visited);
				remaining *= conditions(((LootEntryConditionsAccessor) child).explorerscompass$getConditions());
				if (remaining <= 0.0D) {
					return;
				}
			}
			return;
		}
		if (entry instanceof CompositeEntryBase composite) {
			for (LootPoolEntryContainer child : children(composite)) {
				expand(child, chance, leaves, visited);
			}
			return;
		}
		if (entry instanceof LootItem lootItem) {
			final Item item = ((LootItemAccessor) lootItem).explorerscompass$getItem().value();
			if (item != null && item != Items.AIR) {
				leaves.add(Leaf.items(List.of(item), weight(lootItem), chance));
			} else {
				leaves.add(Leaf.items(List.of(), weight(lootItem), chance));
			}
			return;
		}
		if (entry instanceof TagEntry tagEntry) {
			final List<Item> items = new ArrayList<Item>();
			for (Holder<Item> holder : BuiltInRegistries.ITEM.getTagOrEmpty(((TagEntryAccessor) tagEntry).explorerscompass$getTag())) {
				final Item item = holder.value();
				if (item != null && item != Items.AIR) {
					items.add(item);
				}
			}
			final int weight = weight(tagEntry);
			if (((TagEntryAccessor) tagEntry).explorerscompass$isExpand()) {
				for (Item item : items) {
					leaves.add(Leaf.items(List.of(item), weight, chance));
				}
			} else {
				leaves.add(Leaf.items(items, weight, chance));
			}
			return;
		}
		if (entry instanceof NestedLootTable nested) {
			final Either<ResourceKey<LootTable>, LootTable> contents = ((NestedLootTableAccessor) nested).explorerscompass$getContents();
			contents.ifLeft((key) -> leaves.add(Leaf.nestedKey(key, weight(nested), chance)))
					.ifRight((table) -> leaves.add(Leaf.nestedTable(table, weight(nested), chance)));
			return;
		}
		if (entry instanceof EmptyLootItem empty) {
			leaves.add(Leaf.items(List.of(), weight(empty), chance));
		}
	}

	private static List<LootPoolEntryContainer> children(CompositeEntryBase entry) {
		return ((CompositeEntryBaseAccessor) entry).explorerscompass$getChildren();
	}

	private static int weight(LootPoolEntryContainer entry) {
		if (entry instanceof LootPoolSingletonContainer) {
			return Math.max(0, ((LootPoolSingletonWeightAccessor) entry).explorerscompass$getWeight());
		}
		return 1;
	}

	private static double conditions(List<LootItemCondition> conditions) {
		double chance = 1.0D;
		for (LootItemCondition condition : conditions) {
			chance *= conditionChance(condition);
			if (chance <= 0.0D) {
				return 0.0D;
			}
		}
		return chance;
	}

	private static double conditionChance(LootItemCondition condition) {
		if (condition instanceof LootItemRandomChanceCondition randomChance) {
			return clamp01(expected(randomChance.chance()));
		}
		if (condition instanceof InvertedLootItemCondition inverted) {
			return 1.0D - conditionChance(inverted.term());
		}
		if (condition instanceof AllOfCondition allOf) {
			double chance = 1.0D;
			for (LootItemCondition term : terms(allOf)) {
				chance *= conditionChance(term);
			}
			return chance;
		}
		if (condition instanceof AnyOfCondition anyOf) {
			double fail = 1.0D;
			for (LootItemCondition term : terms(anyOf)) {
				fail *= 1.0D - conditionChance(term);
			}
			return 1.0D - fail;
		}
		// Conditions that need a player, a block or luck are treated as passing: a chest in a
		// structure is generated without those, and hiding the item would be the worse lie.
		return 1.0D;
	}

	private static List<LootItemCondition> terms(CompositeLootItemCondition condition) {
		return ((CompositeLootItemConditionAccessor) condition).explorerscompass$getTerms();
	}

	private static double expected(NumberProvider provider) {
		if (provider instanceof ConstantValue constant) {
			return constant.value();
		}
		if (provider instanceof UniformGenerator uniform) {
			return (expected(uniform.min()) + expected(uniform.max())) * 0.5D;
		}
		if (provider instanceof BinomialDistributionGenerator binomial) {
			return expected(binomial.n()) * expected(binomial.p());
		}
		return 1.0D;
	}

	private static void combine(Map<Item, Double> chances, Item item, double chance) {
		if (item == null || item == Items.AIR || chance <= 0.0D) {
			return;
		}
		final double clamped = clamp01(chance);
		chances.merge(item, clamped, (left, right) -> 1.0D - (1.0D - left) * (1.0D - right));
	}

	private static double clamp01(double value) {
		if (value <= 0.0D) {
			return 0.0D;
		}
		if (value >= 1.0D) {
			return 1.0D;
		}
		return value;
	}

	private static Drops toDrops(Map<Item, Double> chances) {
		if (chances.isEmpty()) {
			return Drops.EMPTY;
		}
		final List<Map.Entry<Item, Double>> ordered = new ArrayList<Map.Entry<Item, Double>>(chances.entrySet());
		ordered.sort(Comparator.<Map.Entry<Item, Double>>comparingDouble(Map.Entry::getValue).reversed());
		final int count = Math.min(MAX_ITEMS, ordered.size());
		final int[] itemIds = new int[count];
		final int[] permilles = new int[count];
		for (int i = 0; i < count; i++) {
			final Map.Entry<Item, Double> drop = ordered.get(i);
			itemIds[i] = Item.getId(drop.getKey());
			int permille = (int) Math.round(drop.getValue() * PERMILLE_ALWAYS);
			if (drop.getValue() > 0.0D && permille == 0) {
				permille = 1;
			}
			permilles[i] = Math.min(PERMILLE_ALWAYS, permille);
		}
		return new Drops(itemIds, permilles);
	}

	private record Leaf(List<Item> items, ResourceKey<LootTable> nestedKey, LootTable nestedTable, int weight, double chance) {

		static Leaf items(List<Item> items, int weight, double chance) {
			return new Leaf(items, null, null, weight, chance);
		}

		static Leaf nestedKey(ResourceKey<LootTable> key, int weight, double chance) {
			return new Leaf(List.of(), key, null, weight, chance);
		}

		static Leaf nestedTable(LootTable table, int weight, double chance) {
			return new Leaf(List.of(), null, table, weight, chance);
		}

	}

}
