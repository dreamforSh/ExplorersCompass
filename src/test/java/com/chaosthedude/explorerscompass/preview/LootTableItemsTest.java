package com.chaosthedude.explorerscompass.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.AlternativesEntry;
import net.minecraft.world.level.storage.loot.entries.EmptyLootItem;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.entries.NestedLootTable;
import net.minecraft.world.level.storage.loot.functions.EnchantRandomlyFunction;
import net.minecraft.world.level.storage.loot.functions.SetItemCountFunction;
import net.minecraft.world.level.storage.loot.functions.SetPotionFunction;
import net.minecraft.world.level.storage.loot.predicates.LootItemRandomChanceCondition;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;
import net.minecraft.world.level.storage.loot.providers.number.UniformGenerator;

/**
 * Holds the walk over a loot table to what the table would actually do.
 *
 * <p>The chances a preview shows are worked out rather than rolled, and a chance that is worked
 * out wrong looks exactly like one that is right. Each of these pins one rule of the walk to a
 * table small enough that the right answer can be had by hand.
 */
class LootTableItemsTest {

	private static final ResourceKey<LootTable> MAIN = key("main");
	private static final ResourceKey<LootTable> NESTED = key("nested");

	@BeforeAll
	static void bootstrap() {
		// The items the tables name have to exist. Harmless where the game has already been started.
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void oneRollIsSharedOutByWeight() {
		final LootTable table = LootTable.lootTable().withPool(LootPool.lootPool().setRolls(ConstantValue.exactly(1.0F))
				.add(LootItem.lootTableItem(Items.DIAMOND).setWeight(3))
				.add(LootItem.lootTableItem(Items.EMERALD).setWeight(1))).build();

		final LootTableItems.Drops drops = walker(Map.of(MAIN, table)).itemsOf(MAIN);

		assertEquals(2, drops.itemIds.length);
		// Commonest first, so that a card shows the bread before the diamond
		assertEquals(Items.DIAMOND, Item.byId(drops.itemIds[0]));
		assertEquals(750, drops.permilles[0]);
		assertEquals(Items.EMERALD, Item.byId(drops.itemIds[1]));
		assertEquals(250, drops.permilles[1]);
	}

	@Test
	void severalRollsStackUp() {
		// Half the rolls come up empty, and there are two of them: the item shows up three times in four
		final LootTable table = LootTable.lootTable().withPool(LootPool.lootPool().setRolls(ConstantValue.exactly(2.0F))
				.add(LootItem.lootTableItem(Items.DIAMOND).setWeight(1))
				.add(EmptyLootItem.emptyItem().setWeight(1))).build();

		final LootTableItems.Drops drops = walker(Map.of(MAIN, table)).itemsOf(MAIN);

		assertEquals(1, drops.itemIds.length);
		assertEquals(750, drops.permilles[0]);
	}

	@Test
	void theRollsAreTakenAtTheirAverage() {
		// One to three rolls, so two on average, of a certain item: certain either way
		final LootTable table = LootTable.lootTable().withPool(LootPool.lootPool().setRolls(UniformGenerator.between(1.0F, 3.0F))
				.add(LootItem.lootTableItem(Items.DIAMOND))).build();

		assertEquals(1000, walker(Map.of(MAIN, table)).itemsOf(MAIN).permilles[0]);
	}

	@Test
	void aLoneEntryThatPassesHalfTheTimeIsHalfAsLikely() {
		// Nothing competes with it for the roll; a plain share of the weights would call it certain
		final LootTable table = LootTable.lootTable().withPool(LootPool.lootPool().setRolls(ConstantValue.exactly(1.0F))
				.add(LootItem.lootTableItem(Items.DIAMOND).when(LootItemRandomChanceCondition.randomChance(0.5F)))).build();

		assertEquals(500, walker(Map.of(MAIN, table)).itemsOf(MAIN).permilles[0]);
	}

	@Test
	void aConditionOnTheWholePoolGatesEverythingInIt() {
		final LootTable table = LootTable.lootTable().withPool(LootPool.lootPool().setRolls(ConstantValue.exactly(1.0F))
				.when(LootItemRandomChanceCondition.randomChance(0.25F))
				.add(LootItem.lootTableItem(Items.DIAMOND))).build();

		assertEquals(250, walker(Map.of(MAIN, table)).itemsOf(MAIN).permilles[0]);
	}

	@Test
	void nestedTablesAreFollowed() {
		final LootTable main = LootTable.lootTable().withPool(LootPool.lootPool().setRolls(ConstantValue.exactly(1.0F))
				.add(NestedLootTable.lootTableReference(NESTED))).build();
		final LootTable nested = LootTable.lootTable().withPool(LootPool.lootPool().setRolls(ConstantValue.exactly(1.0F))
				.add(LootItem.lootTableItem(Items.GOLDEN_APPLE))).build();

		final LootTableItems.Drops drops = walker(Map.of(MAIN, main, NESTED, nested)).itemsOf(MAIN);

		assertEquals(1, drops.itemIds.length);
		assertEquals(Items.GOLDEN_APPLE, Item.byId(drops.itemIds[0]));
		assertEquals(1000, drops.permilles[0]);
	}

	@Test
	void aTableThatNamesItselfComesToAnEnd() {
		final LootTable main = LootTable.lootTable().withPool(LootPool.lootPool().setRolls(ConstantValue.exactly(1.0F))
				.add(LootItem.lootTableItem(Items.DIAMOND))
				.add(NestedLootTable.lootTableReference(MAIN))).build();

		final LootTableItems.Drops drops = walker(Map.of(MAIN, main)).itemsOf(MAIN);

		assertEquals(1, drops.itemIds.length);
		assertEquals(Items.DIAMOND, Item.byId(drops.itemIds[0]));
	}

	@Test
	void alternativesFallThroughToTheNextEntry() {
		// The first is taken half the time; the second whenever the first is not
		final LootTable table = LootTable.lootTable().withPool(LootPool.lootPool().setRolls(ConstantValue.exactly(1.0F))
				.add(AlternativesEntry.alternatives(
						LootItem.lootTableItem(Items.DIAMOND).when(LootItemRandomChanceCondition.randomChance(0.5F)),
						LootItem.lootTableItem(Items.EMERALD)))).build();

		final LootTableItems.Drops drops = walker(Map.of(MAIN, table)).itemsOf(MAIN);

		assertEquals(2, drops.itemIds.length);
		assertEquals(500, drops.permilles[0]);
		assertEquals(500, drops.permilles[1]);
	}

	@Test
	void anItemInTwoPoolsIsCombinedRatherThanAdded() {
		// Half and half is three in four, not certain
		final LootTable table = LootTable.lootTable()
				.withPool(LootPool.lootPool().setRolls(ConstantValue.exactly(1.0F)).add(LootItem.lootTableItem(Items.DIAMOND)).add(EmptyLootItem.emptyItem()))
				.withPool(LootPool.lootPool().setRolls(ConstantValue.exactly(1.0F)).add(LootItem.lootTableItem(Items.DIAMOND)).add(EmptyLootItem.emptyItem()))
				.build();

		final LootTableItems.Drops drops = walker(Map.of(MAIN, table)).itemsOf(MAIN);

		assertEquals(1, drops.itemIds.length);
		assertEquals(750, drops.permilles[0]);
	}

	@Test
	void aTableThatCannotBeFoundDropsNothing() {
		assertEquals(0, walker(Map.of()).itemsOf(MAIN).itemIds.length);
	}

	@Test
	void aTableIsWalkedOnceHoweverManyChestsNameIt() {
		final LootTable table = LootTable.lootTable().withPool(LootPool.lootPool().add(LootItem.lootTableItem(Items.DIAMOND))).build();
		final AtomicInteger lookups = new AtomicInteger();
		final LootTableItems walker = new LootTableItems((key) -> {
			lookups.incrementAndGet();
			return table;
		});

		walker.itemsOf(MAIN);
		walker.itemsOf(MAIN);
		walker.itemsOf(MAIN);

		assertEquals(1, lookups.get());
	}

	@Test
	void aChanceTooSmallToRoundToAnythingStillShows() {
		// One in ten thousand rounds to nothing in thousandths, and an item that can drop is not shown as one that cannot
		final LootTable table = LootTable.lootTable().withPool(LootPool.lootPool().setRolls(ConstantValue.exactly(1.0F))
				.add(LootItem.lootTableItem(Items.DIAMOND).when(LootItemRandomChanceCondition.randomChance(0.0001F)))).build();

		final LootTableItems.Drops drops = walker(Map.of(MAIN, table)).itemsOf(MAIN);

		assertEquals(1, drops.itemIds.length);
		assertTrue(drops.permilles[0] >= 1);
	}

	@Test
	void anEntryWithNoFunctionsDropsOneOfItsItemPlain() {
		final LootTable table = LootTable.lootTable().withPool(LootPool.lootPool().add(LootItem.lootTableItem(Items.DIAMOND))).build();

		final LootTableItems.Drops drops = walker(Map.of(MAIN, table)).itemsOf(MAIN);

		assertEquals(1, drops.minCounts[0]);
		assertEquals(1, drops.maxCounts[0]);
		assertEquals(0, drops.flags[0]);
		assertEquals(LootTableItems.NO_POTION, drops.potions[0]);
	}

	@Test
	void theCountOfAStackIsReadOffItsFunction() {
		final LootTable table = LootTable.lootTable().withPool(LootPool.lootPool()
				.add(LootItem.lootTableItem(Items.DIAMOND).apply(SetItemCountFunction.setCount(UniformGenerator.between(1.0F, 3.0F))))).build();

		final LootTableItems.Drops drops = walker(Map.of(MAIN, table)).itemsOf(MAIN);

		assertEquals(1, drops.minCounts[0]);
		assertEquals(3, drops.maxCounts[0]);
	}

	@Test
	void aCountAddedToTheStackStartsFromTheOneItemItHolds() {
		final LootTable table = LootTable.lootTable().withPool(LootPool.lootPool()
				.add(LootItem.lootTableItem(Items.DIAMOND).apply(SetItemCountFunction.setCount(ConstantValue.exactly(2.0F), true)))).build();

		final LootTableItems.Drops drops = walker(Map.of(MAIN, table)).itemsOf(MAIN);

		assertEquals(3, drops.minCounts[0]);
		assertEquals(3, drops.maxCounts[0]);
	}

	@Test
	void theRangeRunsFromTheFewestAnyEntryPutsDownToTheMost() {
		// The same item from two entries, and from a second pool: one figure has to cover all of them
		final LootTable table = LootTable.lootTable()
				.withPool(LootPool.lootPool()
						.add(LootItem.lootTableItem(Items.DIAMOND).apply(SetItemCountFunction.setCount(UniformGenerator.between(1.0F, 2.0F))))
						.add(LootItem.lootTableItem(Items.DIAMOND).apply(SetItemCountFunction.setCount(UniformGenerator.between(4.0F, 6.0F)))))
				.withPool(LootPool.lootPool()
						.add(LootItem.lootTableItem(Items.DIAMOND).apply(SetItemCountFunction.setCount(ConstantValue.exactly(3.0F)))))
				.build();

		final LootTableItems.Drops drops = walker(Map.of(MAIN, table)).itemsOf(MAIN);

		assertEquals(1, drops.itemIds.length);
		assertEquals(1, drops.minCounts[0]);
		assertEquals(6, drops.maxCounts[0]);
	}

	@Test
	void aCountOfNoneIsSaidAsOne() {
		// A stack of none is no item at all, and a stack larger than any that exists is a slip in the table
		final LootTable table = LootTable.lootTable().withPool(LootPool.lootPool()
				.add(LootItem.lootTableItem(Items.DIAMOND).apply(SetItemCountFunction.setCount(UniformGenerator.between(0.0F, 500.0F))))).build();

		final LootTableItems.Drops drops = walker(Map.of(MAIN, table)).itemsOf(MAIN);

		assertEquals(1, drops.minCounts[0]);
		assertEquals(LootTableItems.MAX_COUNT, drops.maxCounts[0]);
	}

	@Test
	void anItemThatComesEnchantedIsFlagged() {
		final LootTable table = LootTable.lootTable().withPool(LootPool.lootPool()
				.add(LootItem.lootTableItem(Items.IRON_SWORD).apply(EnchantRandomlyFunction.randomEnchantment()))
				.add(LootItem.lootTableItem(Items.BREAD))).build();

		final LootTableItems.Drops drops = walker(Map.of(MAIN, table)).itemsOf(MAIN);

		assertEquals(2, drops.itemIds.length);
		for (int i = 0; i < drops.itemIds.length; i++) {
			final boolean enchanted = (drops.flags[i] & LootTableItems.FLAG_ENCHANTED) != 0;
			assertEquals(Item.byId(drops.itemIds[i]) == Items.IRON_SWORD, enchanted, "the wrong item is flagged as enchanted");
		}
	}

	@Test
	void aBookThatComesEnchantedIsAnEnchantedBook() {
		// The table names the plain book; enchanting it is what turns it into the other item
		final LootTable table = LootTable.lootTable().withPool(LootPool.lootPool()
				.add(LootItem.lootTableItem(Items.BOOK).apply(EnchantRandomlyFunction.randomEnchantment()))).build();

		final LootTableItems.Drops drops = walker(Map.of(MAIN, table)).itemsOf(MAIN);

		assertEquals(Items.ENCHANTED_BOOK, Item.byId(drops.itemIds[0]));
		assertEquals(LootTableItems.FLAG_ENCHANTED, drops.flags[0]);
	}

	@Test
	void anEnchantmentOnTheEntryNamingANestedTableReachesWhatComesOutOfIt() {
		final LootTable main = LootTable.lootTable().withPool(LootPool.lootPool()
				.add(NestedLootTable.lootTableReference(NESTED).apply(EnchantRandomlyFunction.randomEnchantment()))).build();
		final LootTable nested = LootTable.lootTable().withPool(LootPool.lootPool()
				.add(LootItem.lootTableItem(Items.IRON_SWORD).apply(SetItemCountFunction.setCount(ConstantValue.exactly(2.0F))))).build();

		final LootTableItems.Drops drops = walker(Map.of(MAIN, main, NESTED, nested)).itemsOf(MAIN);

		assertEquals(1, drops.itemIds.length);
		assertEquals(LootTableItems.FLAG_ENCHANTED, drops.flags[0]);
		// And what the nested table says about the count stands
		assertEquals(2, drops.minCounts[0]);
	}

	@Test
	void twoBottlesOfDifferentPotionsAreTwoThingsToFind() {
		final LootTable table = LootTable.lootTable().withPool(LootPool.lootPool()
				.add(LootItem.lootTableItem(Items.POTION).setWeight(3).apply(SetPotionFunction.setPotion(Potions.REGENERATION)))
				.add(LootItem.lootTableItem(Items.POTION).setWeight(1).apply(SetPotionFunction.setPotion(Potions.SWIFTNESS)))).build();

		final LootTableItems.Drops drops = walker(Map.of(MAIN, table)).itemsOf(MAIN);

		assertEquals(2, drops.itemIds.length);
		assertEquals(Items.POTION, Item.byId(drops.itemIds[0]));
		assertEquals(Items.POTION, Item.byId(drops.itemIds[1]));
		// Commonest first, and each said as the potion it is
		assertEquals(BuiltInRegistries.POTION.getId(Potions.REGENERATION.value()), drops.potions[0]);
		assertEquals(750, drops.permilles[0]);
		assertEquals(BuiltInRegistries.POTION.getId(Potions.SWIFTNESS.value()), drops.potions[1]);
		assertEquals(250, drops.permilles[1]);
	}

	@Test
	void theSamePotionFromTwoEntriesIsOneThing() {
		final LootTable table = LootTable.lootTable().withPool(LootPool.lootPool()
				.add(LootItem.lootTableItem(Items.POTION).apply(SetPotionFunction.setPotion(Potions.REGENERATION)))
				.add(LootItem.lootTableItem(Items.POTION).apply(SetPotionFunction.setPotion(Potions.REGENERATION)))).build();

		assertEquals(1, walker(Map.of(MAIN, table)).itemsOf(MAIN).itemIds.length);
	}

	private static LootTableItems walker(Map<ResourceKey<LootTable>, LootTable> tables) {
		final Map<ResourceKey<LootTable>, LootTable> copy = new HashMap<ResourceKey<LootTable>, LootTable>(tables);
		return new LootTableItems((key) -> copy.getOrDefault(key, LootTable.EMPTY));
	}

	private static ResourceKey<LootTable> key(String name) {
		return ResourceKey.create(Registries.LOOT_TABLE, ResourceLocation.fromNamespaceAndPath("explorerscompass", "test/" + name));
	}

}
