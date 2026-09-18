package com.chaosthedude.explorerscompass.preview;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.chaosthedude.explorerscompass.mixin.CompositeEntryBaseAccessor;
import com.chaosthedude.explorerscompass.mixin.CompositeLootItemConditionAccessor;
import com.chaosthedude.explorerscompass.mixin.LootEntryConditionsAccessor;
import com.chaosthedude.explorerscompass.mixin.LootItemAccessor;
import com.chaosthedude.explorerscompass.mixin.LootPoolEntriesAccessor;
import com.chaosthedude.explorerscompass.mixin.LootPoolSingletonFunctionsAccessor;
import com.chaosthedude.explorerscompass.mixin.LootPoolSingletonWeightAccessor;
import com.chaosthedude.explorerscompass.mixin.LootTablePoolsAccessor;
import com.chaosthedude.explorerscompass.mixin.NestedLootTableAccessor;
import com.chaosthedude.explorerscompass.mixin.SetItemCountFunctionAccessor;
import com.chaosthedude.explorerscompass.mixin.SetPotionFunctionAccessor;
import com.chaosthedude.explorerscompass.mixin.TagEntryAccessor;
import com.mojang.datafixers.util.Either;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.AlternativesEntry;
import net.minecraft.world.level.storage.loot.entries.CompositeEntryBase;
import net.minecraft.world.level.storage.loot.entries.DynamicLoot;
import net.minecraft.world.level.storage.loot.entries.EmptyLootItem;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.entries.LootPoolEntryContainer;
import net.minecraft.world.level.storage.loot.entries.LootPoolSingletonContainer;
import net.minecraft.world.level.storage.loot.entries.NestedLootTable;
import net.minecraft.world.level.storage.loot.entries.SequentialEntry;
import net.minecraft.world.level.storage.loot.entries.TagEntry;
import net.minecraft.world.level.storage.loot.functions.EnchantRandomlyFunction;
import net.minecraft.world.level.storage.loot.functions.EnchantWithLevelsFunction;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;
import net.minecraft.world.level.storage.loot.functions.SetEnchantmentsFunction;
import net.minecraft.world.level.storage.loot.functions.SetItemCountFunction;
import net.minecraft.world.level.storage.loot.functions.SetPotionFunction;
import net.minecraft.world.level.storage.loot.predicates.AllOfCondition;
import net.minecraft.world.level.storage.loot.predicates.AnyOfCondition;
import net.minecraft.world.level.storage.loot.predicates.CompositeLootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.InvertedLootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemRandomChanceCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemRandomChanceWithEnchantedBonusCondition;
import net.minecraft.world.level.storage.loot.providers.number.BinomialDistributionGenerator;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;
import net.minecraft.world.level.storage.loot.providers.number.NumberProvider;
import net.minecraft.world.level.storage.loot.providers.number.UniformGenerator;

/**
 * The items a loot table may drop, how likely each one is, and how many of it come at once, without
 * opening it.
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
 * <p>The functions an entry runs on what it drops are read for the three things a player would
 * want to know before walking there: how many of the item one roll puts in the stack
 * ({@code set_count}), whether it comes enchanted ({@code enchant_randomly},
 * {@code enchant_with_levels}, {@code set_enchantments}), and which potion a bottle holds
 * ({@code set_potion}). A potion is kept apart from the other potions of the same table, since a
 * bottle of regeneration and a bottle of swiftness are two different things to find. Functions on
 * the pool or the table itself are not read: in the game's own tables those only ever name a
 * potion or an instrument for a pool of one entry, which the entry says as well.
 *
 * <p>Built once per preview, and remembers each table it has walked, so that a village of many
 * chests sharing a handful of tables does not walk the same table for every chest.
 */
final class LootTableItems {

	/** Far more than any vanilla chest table names; more than this is trimmed so a packet stays small. */
	static final int MAX_ITEMS = 64;
	/** Thousandths of certainty: 1000 is always, 1 is one in a thousand. */
	static final int PERMILLE_ALWAYS = 1000;
	/** The most of an item a preview ever says a roll puts down: no stack is larger, and a bigger number is a slip in the table. */
	static final int MAX_COUNT = 99;
	/** Set in a drop's flags when the item comes enchanted. */
	static final int FLAG_ENCHANTED = 1;
	/** A drop's potion when it is not a potion at all. */
	static final int NO_POTION = -1;

	static final class Drops {

		static final Drops EMPTY = new Drops(new int[0], new int[0], new int[0], new int[0], new int[0], new int[0]);

		final int[] itemIds;
		final int[] permilles;
		/** The fewest and the most of the item one roll puts in the stack, parallel to the items. */
		final int[] minCounts;
		final int[] maxCounts;
		/** How the item comes, as bits: see {@link #FLAG_ENCHANTED}. */
		final int[] flags;
		/** Which potion a bottle holds, as an id into the potion registry, or {@link #NO_POTION}. */
		final int[] potions;

		Drops(int[] itemIds, int[] permilles, int[] minCounts, int[] maxCounts, int[] flags, int[] potions) {
			this.itemIds = itemIds;
			this.permilles = permilles;
			this.minCounts = minCounts;
			this.maxCounts = maxCounts;
			this.flags = flags;
			this.potions = potions;
		}

		/** Plain drops of the given items: one each, certain, unenchanted, and not potions. */
		static Drops certain(int[] itemIds) {
			final int[] permilles = new int[itemIds.length];
			final int[] ones = new int[itemIds.length];
			final int[] potions = new int[itemIds.length];
			for (int i = 0; i < itemIds.length; i++) {
				permilles[i] = PERMILLE_ALWAYS;
				ones[i] = 1;
				potions[i] = NO_POTION;
			}
			return new Drops(itemIds, permilles, ones, ones, new int[itemIds.length], potions);
		}

	}

	/** The group of a leaf that is not one of a set of alternatives. */
	private static final int NO_GROUP = -1;

	/** Answers with the table behind a key, or with {@link LootTable#EMPTY} when there is none. */
	private final Function<ResourceKey<LootTable>, LootTable> resolver;
	private final Map<ResourceKey<LootTable>, Drops> walked = new HashMap<ResourceKey<LootTable>, Drops>();
	/** Hands each set of alternatives a group of its own as the tables are walked. */
	private int nextGroup;

	/**
	 * Walks tables the way they were given, rather than through a server. What a server adds is the
	 * lookup, so this is what a test hands tables to directly.
	 */
	LootTableItems(Function<ResourceKey<LootTable>, LootTable> resolver) {
		this.resolver = resolver;
	}

	/** Walks the tables the given server has loaded. */
	static LootTableItems of(MinecraftServer server) {
		return new LootTableItems((key) -> server.reloadableRegistries().getLootTable(key));
	}

	/** The distinct items the given table may drop, commonest first, each with the chance it appears. */
	Drops itemsOf(ResourceKey<LootTable> key) {
		final Drops known = walked.get(key);
		if (known != null) {
			return known;
		}
		final Map<Variant, Tally> chances = new HashMap<Variant, Tally>();
		final Set<ResourceLocation> visited = new HashSet<ResourceLocation>();
		visited.add(key.location());
		collect(resolve(key), chances, visited);
		final Drops drops = toDrops(chances);
		walked.put(key, drops);
		return drops;
	}

	private LootTable resolve(ResourceKey<LootTable> key) {
		try {
			final LootTable table = resolver.apply(key);
			return table == null ? LootTable.EMPTY : table;
		} catch (RuntimeException e) {
			// A table that cannot be looked up drops nothing, which is also what it does in the world
			return LootTable.EMPTY;
		}
	}

	private void collect(LootTable table, Map<Variant, Tally> chances, Set<ResourceLocation> visited) {
		if (table == null || table == LootTable.EMPTY) {
			return;
		}
		for (LootPool pool : ((LootTablePoolsAccessor) table).explorerscompass$getPools()) {
			collectPool(pool, chances, visited);
		}
	}

	private void collectPool(LootPool pool, Map<Variant, Tally> chances, Set<ResourceLocation> visited) {
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
			expand(entry, 1.0D, leaves, visited, NO_GROUP);
		}

		// How much weight the entries put up between them on an average roll, each counted for as
		// much of the time as its conditions let it in; and how much of that each set of
		// alternatives puts up, since only one of a set is ever in at once
		double expectedWeight = 0.0D;
		final Map<Integer, Double> expectedGroupWeight = new HashMap<Integer, Double>();
		for (Leaf leaf : leaves) {
			final double weighted = leaf.weight * leaf.chance;
			expectedWeight += weighted;
			if (leaf.group != NO_GROUP) {
				expectedGroupWeight.merge(leaf.group, weighted, Double::sum);
			}
		}
		if (expectedWeight <= 0.0D) {
			return;
		}

		final Map<Variant, Tally> perRoll = new HashMap<Variant, Tally>();
		for (Leaf leaf : leaves) {
			// A roll picks this entry when its conditions let it in and the weights then fall its way.
			// Given that it is in, it competes with its whole weight against what the others are
			// expected to put up — so an entry that is the only one in its pool and passes half the
			// time is picked half the time, rather than every time as a plain share of the weights
			// would say. The others in its own set of alternatives are not among them: when it is
			// in, none of them is.
			final double ownSet = leaf.group == NO_GROUP ? leaf.weight * leaf.chance : expectedGroupWeight.get(leaf.group);
			final double competing = expectedWeight - ownSet + leaf.weight;
			final double selected = competing <= 0.0D ? 0.0D : leaf.chance * leaf.weight / competing;
			if (selected <= 0.0D) {
				continue;
			}
			if (leaf.nestedKey != null || leaf.nestedTable != null) {
				final Map<Variant, Tally> nested = new HashMap<Variant, Tally>();
				if (leaf.nestedKey != null) {
					if (visited.add(leaf.nestedKey.location())) {
						collect(resolve(leaf.nestedKey), nested, visited);
						visited.remove(leaf.nestedKey.location());
					}
				} else {
					collect(leaf.nestedTable, nested, visited);
				}
				// What the nested table says about its items stands; the one thing the entry naming
				// it can add is to enchant whatever comes out
				for (Map.Entry<Variant, Tally> drop : nested.entrySet()) {
					final Tally inner = drop.getValue();
					perRoll.computeIfAbsent(drop.getKey(), (variant) -> new Tally()).add(selected * inner.chance, inner.minCount, inner.maxCount, inner.enchanted || leaf.modifiers.enchanted());
				}
			} else {
				for (Item item : leaf.items) {
					perRoll.computeIfAbsent(new Variant(item, leaf.modifiers.potion()), (variant) -> new Tally()).add(selected, leaf.modifiers.minCount(), leaf.modifiers.maxCount(), leaf.modifiers.enchanted());
				}
			}
		}

		for (Map.Entry<Variant, Tally> drop : perRoll.entrySet()) {
			final Tally tally = drop.getValue();
			final double pRoll = Math.min(1.0D, tally.chance);
			final double atLeastOnce = 1.0D - Math.pow(1.0D - pRoll, rolls);
			combine(chances, drop.getKey(), poolChance * atLeastOnce, tally);
		}
	}

	/**
	 * Flattens an entry into the leaves it can put forward on a roll, each with the chance it is
	 * put forward at all. Leaves under one {@code alternatives} entry are all given the same group,
	 * since a roll only ever sees one of them; a group of {@link #NO_GROUP} is a leaf that stands on
	 * its own.
	 */
	private void expand(LootPoolEntryContainer entry, double scale, List<Leaf> leaves, Set<ResourceLocation> visited, int group) {
		final double chance = scale * conditions(((LootEntryConditionsAccessor) entry).explorerscompass$getConditions());
		if (chance <= 0.0D) {
			return;
		}
		if (entry instanceof AlternativesEntry alternatives) {
			// Nested alternatives stay in the set they are already part of: one of the outer set is
			// in at a time, and so one of the whole lot is
			final int set = group == NO_GROUP ? nextGroup++ : group;
			double remaining = chance;
			for (LootPoolEntryContainer child : children(alternatives)) {
				final double childChance = conditions(((LootEntryConditionsAccessor) child).explorerscompass$getConditions());
				expand(child, remaining, leaves, visited, set);
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
				expand(child, remaining, leaves, visited, group);
				remaining *= conditions(((LootEntryConditionsAccessor) child).explorerscompass$getConditions());
				if (remaining <= 0.0D) {
					return;
				}
			}
			return;
		}
		if (entry instanceof CompositeEntryBase composite) {
			for (LootPoolEntryContainer child : children(composite)) {
				expand(child, chance, leaves, visited, group);
			}
			return;
		}
		if (entry instanceof LootItem lootItem) {
			final Item item = ((LootItemAccessor) lootItem).explorerscompass$getItem().value();
			if (item != null && item != Items.AIR) {
				final Modifiers modifiers = modifiers(lootItem);
				// Enchanting a book turns it into an enchanted book, as the game does when it runs the
				// function; the table itself only ever names the plain one
				final Item dropped = modifiers.enchanted() && item == Items.BOOK ? Items.ENCHANTED_BOOK : item;
				leaves.add(Leaf.items(List.of(dropped), weight(lootItem), chance, group, modifiers));
			} else {
				leaves.add(Leaf.items(List.of(), weight(lootItem), chance, group, Modifiers.PLAIN));
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
			final Modifiers modifiers = modifiers(tagEntry);
			if (((TagEntryAccessor) tagEntry).explorerscompass$isExpand()) {
				for (Item item : items) {
					leaves.add(Leaf.items(List.of(item), weight, chance, group, modifiers));
				}
			} else {
				leaves.add(Leaf.items(items, weight, chance, group, modifiers));
			}
			return;
		}
		if (entry instanceof NestedLootTable nested) {
			final Either<ResourceKey<LootTable>, LootTable> contents = ((NestedLootTableAccessor) nested).explorerscompass$getContents();
			final Modifiers modifiers = modifiers(nested);
			contents.ifLeft((key) -> leaves.add(Leaf.nestedKey(key, weight(nested), chance, group, modifiers)))
					.ifRight((table) -> leaves.add(Leaf.nestedTable(table, weight(nested), chance, group, modifiers)));
			return;
		}
		if (entry instanceof EmptyLootItem || entry instanceof DynamicLoot) {
			// Nothing, or whatever the container held before it was broken, which a chest in a
			// structure has none of. Either way the entry takes its share of the rolls from the rest.
			leaves.add(Leaf.items(List.of(), weight(entry), chance, group, Modifiers.PLAIN));
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

	/**
	 * What the functions of an entry do to what it drops, as far as a preview reads them. A
	 * {@code set_count} sets the stack to a range, or adds one to the single item the stack starts
	 * as; the last one to run is the one that stands, as it is in the game. The conditions a function
	 * may carry are not read: the game's own tables put none on these.
	 */
	private static Modifiers modifiers(LootPoolSingletonContainer entry) {
		final List<LootItemFunction> functions = ((LootPoolSingletonFunctionsAccessor) entry).explorerscompass$getFunctions();
		if (functions.isEmpty()) {
			return Modifiers.PLAIN;
		}
		int min = 1;
		int max = 1;
		boolean enchanted = false;
		int potion = NO_POTION;
		for (LootItemFunction function : functions) {
			if (function instanceof SetItemCountFunction setCount) {
				final SetItemCountFunctionAccessor accessor = (SetItemCountFunctionAccessor) setCount;
				final int[] range = range(accessor.explorerscompass$getValue());
				if (accessor.explorerscompass$isAdd()) {
					min += range[0];
					max += range[1];
				} else {
					min = range[0];
					max = range[1];
				}
			} else if (function instanceof EnchantRandomlyFunction || function instanceof EnchantWithLevelsFunction || function instanceof SetEnchantmentsFunction) {
				enchanted = true;
			} else if (function instanceof SetPotionFunction setPotion) {
				potion = BuiltInRegistries.POTION.getId(((SetPotionFunctionAccessor) setPotion).explorerscompass$getPotion().value());
			}
		}
		// A count of none is a stack of nothing, which is not an item that appears; and no stack
		// is larger than the largest
		min = Mth.clamp(min, 1, MAX_COUNT);
		max = Mth.clamp(max, min, MAX_COUNT);
		if (min == 1 && max == 1 && !enchanted && potion == NO_POTION) {
			return Modifiers.PLAIN;
		}
		return new Modifiers(min, max, enchanted, potion);
	}

	/**
	 * The whole numbers a provider can draw, from the least to the most, the way the game draws
	 * them: a constant is floored, a uniform range runs from its least to its most inclusive, and a
	 * binomial from none to all of its trials. Anything else — a score, a level, a value from
	 * storage — is taken as one.
	 */
	private static int[] range(NumberProvider provider) {
		if (provider instanceof ConstantValue constant) {
			final int value = Mth.floor(constant.value());
			return new int[] { value, value };
		}
		if (provider instanceof UniformGenerator uniform) {
			return new int[] { range(uniform.min())[0], range(uniform.max())[1] };
		}
		if (provider instanceof BinomialDistributionGenerator binomial) {
			return new int[] { 0, range(binomial.n())[1] };
		}
		return new int[] { 1, 1 };
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
		if (condition instanceof LootItemRandomChanceWithEnchantedBonusCondition bonusChance) {
			// The bonus is for whoever opened the container, and a preview is of one nobody has
			return clamp01(bonusChance.unenchantedChance());
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

	private static void combine(Map<Variant, Tally> chances, Variant variant, double chance, Tally roll) {
		if (variant.item() == null || variant.item() == Items.AIR || chance <= 0.0D) {
			return;
		}
		final Tally tally = chances.computeIfAbsent(variant, (key) -> new Tally());
		// Two ways of getting the item are two independent chances of having it, not one added to the other
		tally.chance = 1.0D - (1.0D - tally.chance) * (1.0D - clamp01(chance));
		tally.widen(roll.minCount, roll.maxCount);
		tally.enchanted |= roll.enchanted;
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

	private static Drops toDrops(Map<Variant, Tally> chances) {
		if (chances.isEmpty()) {
			return Drops.EMPTY;
		}
		final List<Map.Entry<Variant, Tally>> ordered = new ArrayList<Map.Entry<Variant, Tally>>(chances.entrySet());
		ordered.sort(Comparator.<Map.Entry<Variant, Tally>>comparingDouble((entry) -> entry.getValue().chance).reversed());
		final int count = Math.min(MAX_ITEMS, ordered.size());
		final int[] itemIds = new int[count];
		final int[] permilles = new int[count];
		final int[] minCounts = new int[count];
		final int[] maxCounts = new int[count];
		final int[] flags = new int[count];
		final int[] potions = new int[count];
		for (int i = 0; i < count; i++) {
			final Map.Entry<Variant, Tally> drop = ordered.get(i);
			final Tally tally = drop.getValue();
			itemIds[i] = Item.getId(drop.getKey().item());
			int permille = (int) Math.round(tally.chance * PERMILLE_ALWAYS);
			if (tally.chance > 0.0D && permille == 0) {
				permille = 1;
			}
			permilles[i] = Math.min(PERMILLE_ALWAYS, permille);
			minCounts[i] = tally.minCount;
			maxCounts[i] = tally.maxCount;
			flags[i] = tally.enchanted ? FLAG_ENCHANTED : 0;
			potions[i] = drop.getKey().potion();
		}
		return new Drops(itemIds, permilles, minCounts, maxCounts, flags, potions);
	}

	/**
	 * One thing a table can be said to drop: an item, and for a bottle the potion in it. Two bottles
	 * holding different potions are two things to find, so they are counted apart.
	 */
	private record Variant(Item item, int potion) {
	}

	/** What is known so far about one variant: how likely, how many at once, and whether enchanted. */
	private static final class Tally {

		double chance;
		int minCount = Integer.MAX_VALUE;
		int maxCount;
		boolean enchanted;

		/** Adds one more way of rolling the variant within a single roll of a pool. */
		void add(double chance, int minCount, int maxCount, boolean enchanted) {
			this.chance += chance;
			widen(minCount, maxCount);
			this.enchanted |= enchanted;
		}

		/** Takes the range in, so that it runs from the fewest any way puts down to the most any way does. */
		void widen(int minCount, int maxCount) {
			this.minCount = Math.min(this.minCount, minCount);
			this.maxCount = Math.max(this.maxCount, maxCount);
		}

	}

	/** What the functions of an entry do to what it drops. */
	private record Modifiers(int minCount, int maxCount, boolean enchanted, int potion) {

		static final Modifiers PLAIN = new Modifiers(1, 1, false, NO_POTION);

	}

	/**
	 * One thing a roll can put forward: some items, or a table to roll in turn. Leaves that share a
	 * group came out of one set of alternatives, of which a roll only ever puts one forward.
	 */
	private record Leaf(List<Item> items, ResourceKey<LootTable> nestedKey, LootTable nestedTable, int weight, double chance, int group, Modifiers modifiers) {

		static Leaf items(List<Item> items, int weight, double chance, int group, Modifiers modifiers) {
			return new Leaf(items, null, null, weight, chance, group, modifiers);
		}

		static Leaf nestedKey(ResourceKey<LootTable> key, int weight, double chance, int group, Modifiers modifiers) {
			return new Leaf(List.of(), key, null, weight, chance, group, modifiers);
		}

		static Leaf nestedTable(LootTable table, int weight, double chance, int group, Modifiers modifiers) {
			return new Leaf(List.of(), null, table, weight, chance, group, modifiers);
		}

	}

}
