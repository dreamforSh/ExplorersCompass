package com.chaosthedude.explorerscompass.gui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.chaosthedude.explorerscompass.preview.StructurePreview;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * What the loot of a whole structure comes to, worked out once for a preview and read by every
 * part of the interface that shows it: the card, the overview, the tooltip and the badges.
 *
 * <p>The unit is the variant: one item as it comes, so that a plain bottle, a bottle of healing and
 * an enchanted book that glints are each a variant of their own, while a diamond named by three
 * different tables is one variant found in three places. Each variant knows which tables give it,
 * how likely each one is to, and from that how likely the structure as a whole is to give it at
 * least once, counting every container that names a table, including those a slice through the
 * model hides.
 *
 * <p>The variants are kept likeliest first across the structure, and in a fixed order beyond that,
 * so that the overview lists them the same way every time it is opened rather than in whatever
 * order the names happen to sort in the player's language.
 *
 * <p>The counting itself, in {@link #compute}, needs nothing but numbers, so that it can be tested
 * without the game. The stacks the variants are drawn as, and how much each is worth finding, need
 * the registries and are added by {@link #of}.
 */
@OnlyIn(Dist.CLIENT)
final class LootStats {

	/** Thousandths of certainty. */
	private static final int ALWAYS = 1000;

	/**
	 * The loot of a preview as plain numbers: which table each marker names, and for each table its
	 * id and, parallel to its items, the item ids, chances in thousandths, counts, potions (negative
	 * for none) and whether each comes enchanted.
	 */
	record Input(int[] markerTables, String[] ids, int[][] items, int[][] permilles, int[][] minCounts, int[][] maxCounts, int[][] potions, boolean[][] enchanted) {
	}

	/**
	 * One item as it comes, and everywhere it comes from. The tables that give it, the item each of
	 * them names it as, and the chance each gives it with are parallel, ordered likeliest first, then
	 * by the table more containers name, then by table, so that the first is always the best place to
	 * look.
	 *
	 * <p>{@code certain} is whether some container all but surely holds it, in which case the
	 * structure-wide chance is one; see {@link LootMath} for why a table's 1000 thousandths counts
	 * as sure.
	 */
	record Variant(int index, long key, int itemId, int potion, boolean enchanted, int[] tables, int[] items, int[] permilles, int containersWith, int bestPermille, int bestTable, boolean certain, double structureChance, int structurePermille, double expected, int minCount, int maxCount) {

		private Variant withIndex(int index) {
			return new Variant(index, key, itemId, potion, enchanted, tables, items, permilles, containersWith, bestPermille, bestTable, certain, structureChance, structurePermille, expected, minCount, maxCount);
		}

	}

	/** Likeliest across the structure first, then likeliest in one container, then by key, so that no two variants tie. */
	private static final Comparator<Variant> ORDER = Comparator.comparingInt((Variant variant) -> -variant.structurePermille())
			.thenComparingInt((variant) -> -variant.bestPermille())
			.thenComparingLong(Variant::key);

	/** The one preview worked out last, since the screen shows one preview at a time and asks for it every frame. Render thread only. */
	private static StructurePreview cachedFor;
	private static LootStats cached;

	private final int[] containers;
	private final int[] firstMarkers;
	private final int totalContainers;
	private final int unresolvedTables;
	private final List<Variant> variants;
	/** Which variant each item of each table is, as an index into {@link #variants}. */
	private final int[][] variantOf;
	private final BitSet[] sourceTables;
	/** The stacks the variants are drawn as, parallel to them, or null where the registries were not consulted. */
	private final ItemStack[] stacks;
	/** How much the most valuable variant each table gives is worth finding, as an ordinal of the game's rarities. */
	private final int[] tableWorth;

	private LootStats(int[] containers, int[] firstMarkers, int totalContainers, int unresolvedTables, List<Variant> variants, int[][] variantOf, BitSet[] sourceTables, ItemStack[] stacks, int[] tableWorth) {
		this.containers = containers;
		this.firstMarkers = firstMarkers;
		this.totalContainers = totalContainers;
		this.unresolvedTables = unresolvedTables;
		this.variants = variants;
		this.variantOf = variantOf;
		this.sourceTables = sourceTables;
		this.stacks = stacks;
		this.tableWorth = tableWorth;
	}

	/**
	 * The loot of the given preview, worked out the first time it is asked for and kept until
	 * another preview is, with the stacks to draw every variant as and how much each is worth.
	 */
	static LootStats of(StructurePreview preview) {
		if (preview == cachedFor && cached != null) {
			return cached;
		}
		final LootStats counted = compute(inputOf(preview));
		final int variantCount = counted.variants.size();
		final ItemStack[] stacks = new ItemStack[variantCount];
		final int[] tableWorth = new int[counted.tableCount()];
		for (int i = 0; i < variantCount; i++) {
			final Variant variant = counted.variants.get(i);
			stacks[i] = stackFor(variant);
			if (stacks[i].isEmpty()) {
				continue;
			}
			// The component rather than the stack's own rarity, which only rises for a stack that is really enchanted
			final int worth = LootMath.promotedWorth(stacks[i].getOrDefault(DataComponents.RARITY, Rarity.COMMON).ordinal(), variant.enchanted());
			for (int table : variant.tables()) {
				tableWorth[table] = Math.max(tableWorth[table], worth);
			}
		}
		cached = new LootStats(counted.containers, counted.firstMarkers, counted.totalContainers, counted.unresolvedTables, counted.variants, counted.variantOf, counted.sourceTables, stacks, tableWorth);
		cachedFor = preview;
		return cached;
	}

	private static Input inputOf(StructurePreview preview) {
		final int[] markerTables = new int[preview.getLootMarkerCount()];
		for (int marker = 0; marker < markerTables.length; marker++) {
			markerTables[marker] = preview.getLootMarkerTableIndex(marker);
		}
		final int tableCount = preview.getLootTableCount();
		final String[] ids = new String[tableCount];
		final int[][] items = new int[tableCount][];
		final int[][] permilles = new int[tableCount][];
		final int[][] minCounts = new int[tableCount][];
		final int[][] maxCounts = new int[tableCount][];
		final int[][] potions = new int[tableCount][];
		final boolean[][] enchanted = new boolean[tableCount][];
		for (int table = 0; table < tableCount; table++) {
			ids[table] = preview.getLootTableId(table);
			items[table] = preview.getLootTableItems(table);
			permilles[table] = preview.getLootTableChances(table);
			minCounts[table] = preview.getLootTableMinCounts(table);
			maxCounts[table] = preview.getLootTableMaxCounts(table);
			final int itemCount = items[table].length;
			potions[table] = new int[itemCount];
			enchanted[table] = new boolean[itemCount];
			for (int item = 0; item < itemCount; item++) {
				potions[table][item] = preview.getLootTableItemPotion(table, item);
				enchanted[table][item] = preview.isLootTableItemEnchanted(table, item);
			}
		}
		return new Input(markerTables, ids, items, permilles, minCounts, maxCounts, potions, enchanted);
	}

	/**
	 * The stack a variant is drawn and named as: the item, holding its potion where it is a bottle
	 * of one, and made to glint where it comes enchanted. Empty for an item this side does not know.
	 */
	private static ItemStack stackFor(Variant variant) {
		final Item resolved = Item.byId(variant.itemId());
		if (resolved == null || resolved == Items.AIR) {
			return ItemStack.EMPTY;
		}
		final ItemStack stack = new ItemStack(resolved);
		if (variant.potion() >= 0) {
			final Potion potion = BuiltInRegistries.POTION.byId(variant.potion());
			if (potion != null) {
				stack.set(DataComponents.POTION_CONTENTS, new PotionContents(BuiltInRegistries.POTION.wrapAsHolder(potion)));
			}
		}
		if (variant.enchanted()) {
			stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, Boolean.TRUE);
		}
		return stack;
	}

	/**
	 * What one variant comes to within one table, where the table names it more than once. A table
	 * that does is merged as though its entries rolled apart, so that it gives the item where any of
	 * them does.
	 */
	private static final class Source {

		final int table;
		final int item;
		int permille;
		int minCount;
		int maxCount;

		Source(int table, int item, int permille, int minCount, int maxCount) {
			this.table = table;
			this.item = item;
			this.permille = permille;
			this.minCount = minCount;
			this.maxCount = maxCount;
		}

		void merge(int otherPermille, int otherMin, int otherMax) {
			if (permille >= ALWAYS || otherPermille >= ALWAYS) {
				permille = ALWAYS;
			} else {
				final double miss = (1.0D - permille / (double) ALWAYS) * (1.0D - otherPermille / (double) ALWAYS);
				// Two chances short of certain stay short of it, however near their rounding brings them
				permille = Math.min(ALWAYS - 1, (int) Math.round((1.0D - miss) * ALWAYS));
			}
			minCount = Math.min(minCount, otherMin);
			maxCount = Math.max(maxCount, otherMax);
		}

	}

	/** One variant as it is gathered, before the numbers about it are worked out. */
	private static final class Tally {

		final long key;
		final int itemId;
		final int potion;
		final boolean enchanted;
		final List<Source> sources = new ArrayList<Source>();

		Tally(long key, int itemId, int potion, boolean enchanted) {
			this.key = key;
			this.itemId = itemId;
			this.potion = potion;
			this.enchanted = enchanted;
		}

	}

	/**
	 * What tells one variant from another, as one number: the item, then the potion, then whether it
	 * glints. No potion is stored as nought, so that the plain item sorts before any bottle of it.
	 */
	static long variantKey(int itemId, int potion, boolean enchanted) {
		return ((long) itemId << 32) | ((long) (potion + 1) << 1) | (enchanted ? 1L : 0L);
	}

	/**
	 * Works out the loot of the given numbers without consulting any registry: the variants come
	 * without stacks to draw them as, and every one is worth nothing in particular.
	 */
	static LootStats compute(Input input) {
		final int tableCount = input.ids().length;
		final int[] containers = new int[tableCount];
		final int[] firstMarkers = new int[tableCount];
		Arrays.fill(firstMarkers, -1);
		final int[] markerTables = input.markerTables();
		for (int marker = 0; marker < markerTables.length; marker++) {
			final int table = markerTables[marker];
			containers[table]++;
			if (firstMarkers[table] < 0) {
				firstMarkers[table] = marker;
			}
		}

		int unresolved = 0;
		final Map<Long, Integer> byKey = new HashMap<Long, Integer>();
		final List<Tally> tallies = new ArrayList<Tally>();
		final int[][] tallyOf = new int[tableCount][];
		for (int table = 0; table < tableCount; table++) {
			final int[] items = input.items()[table];
			tallyOf[table] = new int[items.length];
			// A table with an id and nothing under it is one this side could not read, which a container
			// with no table at all is not
			if (items.length == 0 && !input.ids()[table].isEmpty() && containers[table] > 0) {
				unresolved++;
			}
			for (int item = 0; item < items.length; item++) {
				final int potion = input.potions()[table][item];
				final boolean enchanted = input.enchanted()[table][item];
				final long key = variantKey(items[item], potion, enchanted);
				Integer index = byKey.get(key);
				if (index == null) {
					index = tallies.size();
					byKey.put(key, index);
					tallies.add(new Tally(key, items[item], potion, enchanted));
				}
				tallyOf[table][item] = index;
				final Tally tally = tallies.get(index);
				final int permille = input.permilles()[table][item];
				final int minCount = input.minCounts()[table][item];
				final int maxCount = input.maxCounts()[table][item];
				// The tables are walked one at a time, so an entry for this table already there is the last one
				final Source last = tally.sources.isEmpty() ? null : tally.sources.get(tally.sources.size() - 1);
				if (last != null && last.table == table) {
					last.merge(permille, minCount, maxCount);
				} else {
					tally.sources.add(new Source(table, item, permille, minCount, maxCount));
				}
			}
		}

		final List<Variant> unsorted = new ArrayList<Variant>(tallies.size());
		for (Tally tally : tallies) {
			unsorted.add(summarise(tally, containers));
		}
		final Integer[] order = new Integer[unsorted.size()];
		for (int i = 0; i < order.length; i++) {
			order[i] = i;
		}
		Arrays.sort(order, (a, b) -> ORDER.compare(unsorted.get(a), unsorted.get(b)));
		final int[] sortedIndex = new int[order.length];
		final List<Variant> variants = new ArrayList<Variant>(order.length);
		final BitSet[] sourceTables = new BitSet[order.length];
		for (int i = 0; i < order.length; i++) {
			sortedIndex[order[i]] = i;
			final Variant variant = unsorted.get(order[i]).withIndex(i);
			variants.add(variant);
			final BitSet tables = new BitSet(tableCount);
			for (int table : variant.tables()) {
				tables.set(table);
			}
			sourceTables[i] = tables;
		}
		for (int[] row : tallyOf) {
			for (int item = 0; item < row.length; item++) {
				row[item] = sortedIndex[row[item]];
			}
		}
		return new LootStats(containers, firstMarkers, markerTables.length, unresolved, Collections.unmodifiableList(variants), tallyOf, sourceTables, null, new int[tableCount]);
	}

	/** Works out the numbers about one variant from where it comes from. */
	private static Variant summarise(Tally tally, int[] containers) {
		final List<Source> sources = new ArrayList<Source>(tally.sources);
		// The likeliest first, the one more containers name where two are as likely, and the earlier table after that
		sources.sort(Comparator.comparingInt((Source source) -> -source.permille)
				.thenComparingInt((source) -> -containers[source.table])
				.thenComparingInt((source) -> source.table));
		final int count = sources.size();
		final int[] tables = new int[count];
		final int[] items = new int[count];
		final int[] permilles = new int[count];
		final int[] counts = new int[count];
		int containersWith = 0;
		int minCount = Integer.MAX_VALUE;
		int maxCount = 0;
		for (int i = 0; i < count; i++) {
			final Source source = sources.get(i);
			tables[i] = source.table;
			items[i] = source.item;
			permilles[i] = source.permille;
			counts[i] = containers[source.table];
			containersWith += counts[i];
			minCount = Math.min(minCount, source.minCount);
			maxCount = Math.max(maxCount, source.maxCount);
		}
		final boolean certain = LootMath.isCertain(permilles, counts);
		final double chance = certain ? 1.0D : LootMath.structureChance(permilles, counts);
		return new Variant(-1, tally.key, tally.itemId, tally.potion, tally.enchanted, tables, items, permilles, containersWith, permilles[0], tables[0], certain, chance, LootMath.structurePermille(chance, certain), LootMath.expected(permilles, counts), minCount, maxCount);
	}

	/** How many tables the preview names, whether or not anything could be read from them. */
	int tableCount() {
		return containers.length;
	}

	/** How many containers name the given table, counting those a slice through the model hides. */
	int containers(int table) {
		return containers[table];
	}

	/** How many containers the preview marks in all. */
	int totalContainers() {
		return totalContainers;
	}

	/** The first marker that names the given table, or -1 where none does. */
	int firstMarker(int table) {
		return firstMarkers[table];
	}

	/**
	 * How many tables some container names but this side could not read, so that a chance worked
	 * out without them can say it may be short.
	 */
	int unresolvedTables() {
		return unresolvedTables;
	}

	/** Which variant the given item of the given table is, as an index into {@link #variants}. */
	int variantOf(int table, int item) {
		return variantOf[table][item];
	}

	/** Every variant, likeliest across the structure first. */
	List<Variant> variants() {
		return variants;
	}

	Variant variant(int index) {
		return variants.get(index);
	}

	/** The tables that give the given variant. Shared between callers, who must not change it. */
	BitSet sourceTables(int variant) {
		return sourceTables[variant];
	}

	/** The stack the given variant is drawn as, empty for an item this side does not know. */
	ItemStack stack(int variant) {
		return stacks == null ? ItemStack.EMPTY : stacks[variant];
	}

	/** How much the most valuable thing the given table gives is worth finding. */
	int tableWorth(int table) {
		return tableWorth[table];
	}

}
