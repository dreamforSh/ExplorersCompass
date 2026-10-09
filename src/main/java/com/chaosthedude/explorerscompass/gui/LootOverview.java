package com.chaosthedude.explorerscompass.gui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import com.chaosthedude.explorerscompass.preview.StructurePreview;
import com.chaosthedude.explorerscompass.util.RenderUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Everything a structure's containers may hold, in one place.
 *
 * <p>The badges on the model say where the containers are, and the card beside one says what that
 * one holds; this is for the other question, which is what the structure as a whole is worth going
 * to. It stands as a drawer along the right of the panel, with the model drawn in what is left, so
 * that what it lists can be read against the building it belongs to: pointing at a table's heading
 * lights up every container on the model that names it, pointing at an item lights up every
 * container that may hold it, and clicking a badge opens the drawer at that container's table.
 *
 * <p>It reads the loot two ways. By container, each table is a section: the container's icon, the
 * table's name, how many containers name it, and every item it may drop, with nothing left out the
 * way the card has to. The tables most of the containers name come first, since those are what most
 * of the structure is made of; containers that were placed holding something follow, and the empty
 * ones come last. By item, every item the structure may give is one tile, labelled with the chance
 * of finding at least one anywhere in it, which is the view for a player who came for one thing and
 * wants to know whether this is the place; clicking a tile goes back to the table likeliest to hold
 * it. Either way the tiles are grouped by tier, and a tier can be picked out on its own, so that the
 * one rare item among sixty is not a matter of reading sixty labels.
 */
@OnlyIn(Dist.CLIENT)
final class LootOverview {

	private static final int PADDING = 6;
	private static final int LINE = 10;
	private static final int ICON = 16;
	/** The heading of a section: an icon tall, and a pixel under it. */
	private static final int TITLE_ROW = ICON + 1;
	private static final int SECTION_GAP = 5;
	private static final int CLOSE_SIZE = 10;
	private static final int SCROLLBAR_WIDTH = 3;
	/** Room between the grid and the scrollbar, so that the last column does not touch it. */
	private static final int SCROLLBAR_GAP = 3;
	/** How far one notch of the wheel moves the list: one row of labelled tiles. */
	private static final int SCROLL_STEP = LootSlots.ROW_LABELLED;
	private static final int CHIP_HEIGHT = 12;
	private static final int CHIP_GAP = 3;
	/** The two views are drawn as one control split in two, so they stand only a pixel apart. */
	private static final int VIEW_CHIP_GAP = 1;
	/** The square of a tier's colour a tier chip leads with. */
	private static final int TIER_SWATCH = 5;
	/** The room either side of what a tier chip holds. */
	private static final int TIER_CHIP_MARGIN = 3;
	/** The room between a tier chip's swatch and its count. */
	private static final int TIER_COUNT_GAP = 2;
	/** How wide a chip's tooltip is allowed to run before it is broken into lines. */
	private static final int TOOLTIP_WIDTH = 200;

	private static final int BACKGROUND_TOP = 0xF0141821;
	private static final int BACKGROUND_BOTTOM = 0xF00A0C10;
	private static final int BORDER = 0x50FFFFFF;
	private static final int SEPARATOR = 0x28FFFFFF;
	private static final int HEADING_HOVER = 0x28FFC24B;
	/** What a line saying how to use something is written in, the way the game writes its own such hints. */
	private static final int HINT = 0x555555;

	/** Lit up when nothing in the drawer is pointed at. Shared, and never changed. */
	private static final BitSet NONE = new BitSet();

	/** The two ways the drawer reads the loot: a section to a table, or a tile to an item across the structure. */
	enum View {
		CONTAINERS("string.explorerscompass.lootViewContainers"),
		ITEMS("string.explorerscompass.lootViewItems");

		/** The key the view's chip is labelled under, and, with ".tooltip" after it, explained under. */
		final String key;

		View(String key) {
			this.key = key;
		}
	}

	/** One loot table's worth of the list, at a y measured from the top of the list before scrolling. */
	private static final class Section {

		final int tableIndex;
		final ItemStack icon;
		final int containers;
		final Component title;
		final String id;
		final Component note;
		final boolean named;
		final boolean placed;
		/** The table's drops in the order the table lists them, and their chances in the same order. */
		final List<LootGrid.Entry> entries;
		final int[] permilles;
		int top;
		int headingBottom;
		int gridTop;
		int bottom;
		/** The tiles as last laid out, or null for a table with nothing to show. */
		LootGrid.Layout grid;

		Section(int tableIndex, ItemStack icon, int containers, Component title, String id, Component note, boolean named, boolean placed, List<LootGrid.Entry> entries, int[] permilles) {
			this.tableIndex = tableIndex;
			this.icon = icon;
			this.containers = containers;
			this.title = title;
			this.id = id;
			this.note = note;
			this.named = named;
			this.placed = placed;
			this.entries = entries;
			this.permilles = permilles;
		}

		/** Whether the containers naming this table hold nothing at all, which keeps it out of every count. */
		boolean isEmpty() {
			return !named && !placed;
		}

	}

	private final List<Section> sections = new ArrayList<Section>();
	/** Every item the structure may give, as the item view draws it, and the chances its tiles are grouped by. */
	private List<LootGrid.Entry> itemEntries = Collections.<LootGrid.Entry>emptyList();
	private int[] itemPermilles = new int[0];
	private LootGrid.Layout itemGrid;
	/** How many tiles each view has in each tier before one is picked out, indexed by tier. */
	private final int[] containerTierCounts = new int[LootTier.values().length];
	private final int[] itemTierCounts = new int[LootTier.values().length];
	/** The one table a heading lights, kept rather than made anew every frame. */
	private final BitSet headingTables = new BitSet();

	private boolean open;
	/** Kept from one preview to the next, since a player who reads by item reads every structure that way. */
	private View view = View.CONTAINERS;
	/** The tier picked out on its own, or null for every tier. */
	private LootTier solo;
	/** The preview the lists were gathered from, so that they are gathered again only when it changes. */
	private StructurePreview laidOutFor;
	private LootStats stats;
	/** What the tiles were last laid out for, so that they are laid out again only when one of these changes. */
	private int laidOutColumns = -1;
	private View laidOutView;
	private LootTier laidOutSolo;
	private boolean laidOutAdvanced;
	private int left;
	private int top;
	private int right;
	private int bottom;
	private int viewTop;
	private int tierTop;
	private int separatorY;
	/** The band the list scrolls in, under the heading of the drawer. */
	private int listTop;
	private int listBottom;
	private int contentHeight;
	private int scroll;
	/** Where the list wants to be scrolled to, set by a click on a badge or an item and applied at the next layout. */
	private int pendingFocus = -1;
	private int columns = 1;
	/** Where each tier chip stands, indexed by tier, and whether there was room to count in them. */
	private final int[] tierChipLeft = new int[LootTier.values().length];
	private final int[] tierChipWidth = new int[LootTier.values().length];
	private boolean tierCountsShown;
	private String summary = "";

	private Section hoveredSection;
	private LootGrid.Tile hoveredTile;
	private LootGrid.Entry hoveredEntry;
	private Section hoveredHeading;
	private View hoveredView;
	private LootTier hoveredTier;
	private BitSet highlighted = NONE;

	boolean isOpen() {
		return open;
	}

	void setOpen(boolean open) {
		this.open = open;
		if (!open) {
			clearHover();
		}
	}

	/**
	 * Opens the drawer at the given table's section, so that a click on a badge lands on what it
	 * holds. That section is in the container view and could be filtered out of sight, so the drawer
	 * is put back to that view with every tier shown.
	 */
	void openAt(int table) {
		setOpen(true);
		view = View.CONTAINERS;
		solo = null;
		pendingFocus = table;
	}

	/** Whether the pointer is anywhere on the drawer. */
	boolean isMouseOver(double mouseX, double mouseY) {
		return open && mouseX >= left && mouseX < right && mouseY >= top && mouseY < bottom;
	}

	/**
	 * The tables whose containers should be lit up on the model for what the pointer is on: the one
	 * table of a heading, or every table that may give an item. Never null, and shared, so the caller
	 * must not change it.
	 */
	BitSet getHighlightedTables() {
		return open ? highlighted : NONE;
	}

	/**
	 * Lays the drawer out in the given rectangle. The lists are gathered afresh when the preview has
	 * changed, and the tiles laid out again only when that, the number of columns, the view, the tier
	 * picked out or whether ids are shown has; every other frame keeps the last layout, since a large
	 * structure lists hundreds of items.
	 */
	void layout(StructurePreview preview, int left, int top, int right, int bottom) {
		this.left = left;
		this.top = top;
		this.right = right;
		this.bottom = bottom;
		final int headingTop = top + PADDING;
		viewTop = headingTop + LINE + 2;
		tierTop = viewTop + CHIP_HEIGHT + CHIP_GAP;
		separatorY = tierTop + CHIP_HEIGHT + 2;
		listTop = separatorY + 2;
		listBottom = Math.max(listTop, bottom - PADDING);

		final int innerLeft = left + PADDING;
		final int newColumns = Math.max(1, (listRight() - innerLeft - LootSlots.RIBBON) / LootSlots.TILE_W);
		final boolean advanced = Minecraft.getInstance().options.advancedItemTooltips;
		if (preview != laidOutFor) {
			gather(preview);
			laidOutFor = preview;
			laidOutColumns = -1;
			solo = null;
			scroll = 0;
		}
		if (newColumns != laidOutColumns || view != laidOutView || solo != laidOutSolo || advanced != laidOutAdvanced) {
			columns = newColumns;
			laidOutColumns = newColumns;
			laidOutView = view;
			laidOutSolo = solo;
			laidOutAdvanced = advanced;
			layoutTiles(advanced);
		}
		layoutTierChips(Minecraft.getInstance().font, innerLeft, right - PADDING);

		if (pendingFocus >= 0) {
			if (view == View.CONTAINERS) {
				for (Section section : sections) {
					if (section.tableIndex == pendingFocus) {
						scroll = section.top;
						break;
					}
				}
			}
			pendingFocus = -1;
		}
		scroll = Mth.clamp(scroll, 0, maxScroll());
	}

	/**
	 * One section per table some container names, with how many containers name each, and one tile
	 * per item for the item view. The sections are ordered by how much of the structure each is: the
	 * tables most containers name first, then what was placed holding something, then whatever holds
	 * nothing at all.
	 */
	private void gather(StructurePreview preview) {
		stats = LootStats.of(preview);
		sections.clear();
		final Item[] icons = new Item[preview.getLootTableCount()];
		for (int marker = 0; marker < preview.getLootMarkerCount(); marker++) {
			final int table = preview.getLootMarkerTableIndex(marker);
			// Drawn as the first container that names it, which for a table is the kind of container it is for
			if (icons[table] == null) {
				icons[table] = preview.getLootMarkerIcon(marker);
			}
		}
		for (int table = 0; table < preview.getLootTableCount(); table++) {
			final int containers = stats.containers(table);
			if (containers == 0) {
				continue;
			}
			final String id = preview.getLootTableId(table);
			final int[] items = preview.getLootTableItems(table);
			final int[] permilles = preview.getLootTableChances(table);
			final int[] minCounts = preview.getLootTableMinCounts(table);
			final int[] maxCounts = preview.getLootTableMaxCounts(table);
			final List<LootGrid.Entry> entries = new ArrayList<LootGrid.Entry>(items.length);
			for (int item = 0; item < items.length; item++) {
				final int variant = stats.variantOf(table, item);
				entries.add(new LootGrid.Entry(stats.stack(variant), permilles[item], minCounts[item], maxCounts[item], variant, table, item));
			}
			final ItemStack icon = new ItemStack(icons[table] == null || icons[table] == Items.AIR ? Items.CHEST : icons[table]);
			if (id.isEmpty()) {
				final Component title = Component.translatable(items.length > 0 ? "string.explorerscompass.lootFixedItems" : "string.explorerscompass.noLootTable");
				sections.add(new Section(table, icon, containers, title, "", null, false, items.length > 0, entries, permilles));
				continue;
			}
			final Component title = LootSlots.displayName(id);
			final String shownId = title.getString().equals(id) ? "" : id;
			final Component note = items.length == 0 ? Component.translatable("string.explorerscompass.lootUnresolved") : null;
			sections.add(new Section(table, icon, containers, title, shownId, note, true, false, entries, permilles));
		}
		sections.sort(Comparator.<Section>comparingInt((section) -> section.named ? 0 : section.placed ? 1 : 2)
				.thenComparing(Comparator.<Section>comparingInt((section) -> section.containers).reversed())
				.thenComparing((section) -> section.title.getString()));

		// Every item a container may really hold, already likeliest across the structure first; one
		// named only by a table no container names could not be found here at all
		final List<LootGrid.Entry> entries = new ArrayList<LootGrid.Entry>();
		for (LootStats.Variant variant : stats.variants()) {
			if (variant.containersWith() > 0) {
				entries.add(new LootGrid.Entry(stats.stack(variant.index()), variant.structurePermille(), variant.minCount(), variant.maxCount(), variant.index(), -1, -1));
			}
		}
		itemEntries = entries;
		itemPermilles = new int[entries.size()];
		for (int i = 0; i < itemPermilles.length; i++) {
			itemPermilles[i] = entries.get(i).permille();
		}

		// The tier counts and the summary leave out containers that hold nothing: they are listed so
		// that the list accounts for every container, but there is no loot in them to count
		Arrays.fill(containerTierCounts, 0);
		Arrays.fill(itemTierCounts, 0);
		int containers = 0;
		int tables = 0;
		for (Section section : sections) {
			if (section.isEmpty()) {
				continue;
			}
			containers += section.containers;
			tables++;
			for (int permille : section.permilles) {
				containerTierCounts[LootTier.of(permille).ordinal()]++;
			}
		}
		for (int permille : itemPermilles) {
			itemTierCounts[LootTier.of(permille).ordinal()]++;
		}
		summary = I18n.get("string.explorerscompass.lootOverviewSummary", String.valueOf(containers), String.valueOf(tables));
	}

	/**
	 * Lays the tiles of the current view out in groups by tier, and the sections around them, at a y
	 * measured from the top of the list before scrolling.
	 */
	private void layoutTiles(boolean advanced) {
		if (view == View.ITEMS) {
			itemGrid = LootGrid.grouped(itemPermilles, columns, solo);
			contentHeight = itemGrid.tiles().isEmpty() ? 0 : 2 + itemGrid.height() + 2;
			return;
		}
		itemGrid = null;
		int y = 0;
		for (int i = 0; i < sections.size(); i++) {
			final Section section = sections.get(i);
			if (i > 0) {
				y += SECTION_GAP;
			}
			section.top = y;
			y += TITLE_ROW;
			section.headingBottom = y;
			// The id says nothing the name does not to most players, so it is shown only to those who
			// asked the game for ids
			if (advanced && !section.id.isEmpty()) {
				y += LINE;
			}
			if (section.note != null) {
				y += LINE;
			}
			section.grid = null;
			if (!section.entries.isEmpty()) {
				y += 2;
				section.gridTop = y;
				section.grid = LootGrid.grouped(section.permilles, columns, solo);
				// A table with nothing in the tier picked out keeps its heading and says so, so that the
				// list still reads as every table the structure has
				y += section.grid.tiles().isEmpty() ? LINE : section.grid.height();
			}
			y += 2;
			section.bottom = y;
		}
		contentHeight = y;
	}

	/**
	 * Lays the tier chips out from the left, each holding its tier's swatch and how many tiles of the
	 * current view are in it. Where the four do not fit with their counts, as in a narrow drawer, they
	 * go without them rather than being cut, since the swatch alone still says which tier is which.
	 */
	private void layoutTierChips(Font font, int innerLeft, int innerRight) {
		final int[] counts = tierCounts();
		final int bare = TIER_CHIP_MARGIN + TIER_SWATCH + TIER_CHIP_MARGIN;
		int total = CHIP_GAP * (counts.length - 1);
		for (int count : counts) {
			total += bare + TIER_COUNT_GAP + font.width(String.valueOf(count));
		}
		tierCountsShown = total <= innerRight - innerLeft;
		int x = innerLeft;
		for (int i = 0; i < counts.length; i++) {
			tierChipLeft[i] = x;
			tierChipWidth[i] = tierCountsShown ? bare + TIER_COUNT_GAP + font.width(String.valueOf(counts[i])) : bare;
			x += tierChipWidth[i] + CHIP_GAP;
		}
	}

	private int[] tierCounts() {
		return view == View.ITEMS ? itemTierCounts : containerTierCounts;
	}

	void render(GuiGraphics guiGraphics, int mouseX, int mouseY) {
		if (!open) {
			return;
		}
		final Font font = Minecraft.getInstance().font;
		final int innerLeft = left + PADDING;
		final int innerRight = right - PADDING;
		final int listRight = listRight();
		findHover(mouseX, mouseY);
		hoveredView = viewAt(mouseX, mouseY);
		hoveredTier = tierAt(mouseX, mouseY);

		RenderUtils.drawVerticalGradient(guiGraphics, left, top, right, bottom, BACKGROUND_TOP, BACKGROUND_BOTTOM);
		RenderUtils.drawInnerOutline(guiGraphics, left, top, right, bottom, BORDER);
		// The bar along the leading edge, which is how everything picked out on these screens is marked
		RenderUtils.drawRect(guiGraphics, left, top, left + 2, bottom, GuiTheme.ACCENT | 0xFF000000);

		renderHeading(guiGraphics, font, mouseX, mouseY, innerLeft, innerRight);
		RenderUtils.drawRect(guiGraphics, innerLeft, separatorY, innerRight, separatorY + 1, SEPARATOR);

		// The list, cut to its band so that a section half scrolled out is drawn half
		guiGraphics.enableScissor(innerLeft, listTop, innerRight, listBottom);
		if (view == View.ITEMS) {
			if (itemGrid == null || itemGrid.tiles().isEmpty()) {
				final String empty = RenderUtils.trimToWidth(I18n.get("string.explorerscompass.lootTierEmpty"), listRight - innerLeft);
				guiGraphics.drawString(font, empty, (innerLeft + listRight - font.width(empty)) / 2, (listTop + listBottom - font.lineHeight) / 2, GuiTheme.TEXT_MUTED, false);
			} else {
				LootGrid.render(guiGraphics, font, itemGrid, itemEntries, innerLeft, listTop - scroll + 2, hoveredTile, listTop, listBottom);
			}
		} else {
			renderSections(guiGraphics, font, innerLeft, listRight);
		}
		guiGraphics.disableScissor();

		// The scrollbar, only where there is more than fits
		final int viewport = listBottom - listTop;
		if (contentHeight > viewport && viewport > 0) {
			final int trackLeft = innerRight - SCROLLBAR_WIDTH;
			RenderUtils.drawRect(guiGraphics, trackLeft, listTop, innerRight, listBottom, GuiTheme.SCROLLBAR_TRACK);
			final int thumbHeight = Math.max(8, viewport * viewport / contentHeight);
			final int thumbTop = listTop + Math.round((viewport - thumbHeight) * (scroll / (float) (contentHeight - viewport)));
			RenderUtils.drawRect(guiGraphics, trackLeft, thumbTop, innerRight, thumbTop + thumbHeight, GuiTheme.SCROLLBAR_THUMB);
		}
	}

	/**
	 * The heading of the drawer: the title with the close box against the right edge and the summary
	 * between them where it fits whole, then the two views, then the tier chips.
	 */
	private void renderHeading(GuiGraphics guiGraphics, Font font, int mouseX, int mouseY, int innerLeft, int innerRight) {
		final int y = top + PADDING;
		final int closeLeft = innerRight - CLOSE_SIZE;
		final String title = RenderUtils.trimToWidth(I18n.get("string.explorerscompass.lootOverview"), closeLeft - 4 - innerLeft);
		guiGraphics.drawString(font, title, innerLeft, y, GuiTheme.TEXT_PRIMARY, true);
		// Left out rather than cut where it does not fit, since half a count says nothing; the views'
		// tooltips say it as well
		final int summaryWidth = font.width(summary);
		if (innerLeft + font.width(title) + 4 + summaryWidth <= closeLeft - 4) {
			guiGraphics.drawString(font, summary, closeLeft - 4 - summaryWidth, y, GuiTheme.TEXT_MUTED, false);
		}
		final boolean closeLit = isOverClose(mouseX, mouseY);
		RenderUtils.drawRect(guiGraphics, closeLeft, y - 1, closeLeft + CLOSE_SIZE, y - 1 + CLOSE_SIZE, closeLit ? GuiTheme.CHIP_ACCENT_BACKGROUND : GuiTheme.CHIP_BACKGROUND);
		guiGraphics.drawString(font, "×", closeLeft + (CLOSE_SIZE - font.width("×")) / 2 + 1, y, closeLit ? GuiTheme.ACCENT : GuiTheme.TEXT_SECONDARY, false);

		final int viewWidth = viewChipWidth();
		for (View each : View.values()) {
			final int x = viewChipLeft(each);
			final boolean current = each == view;
			RenderUtils.drawRect(guiGraphics, x, viewTop, x + viewWidth, viewTop + CHIP_HEIGHT, current ? GuiTheme.CHIP_ACCENT_BACKGROUND : GuiTheme.CHIP_BACKGROUND);
			final String label = RenderUtils.trimToWidth(I18n.get(each.key), viewWidth - 4);
			final int colour = current ? GuiTheme.ACCENT : each == hoveredView ? GuiTheme.TEXT_PRIMARY : GuiTheme.TEXT_SECONDARY;
			guiGraphics.drawString(font, label, x + (viewWidth - font.width(label)) / 2, viewTop + 2, colour, false);
		}

		final int[] counts = tierCounts();
		for (LootTier tier : LootTier.values()) {
			final int i = tier.ordinal();
			final int x = tierChipLeft[i];
			final boolean picked = solo == tier;
			// A tier hidden by another being picked out is drawn hollow, so that the chip still says
			// which tier it is while saying that its tiles are not shown
			final boolean hidden = solo != null && !picked;
			RenderUtils.drawRect(guiGraphics, x, tierTop, x + tierChipWidth[i], tierTop + CHIP_HEIGHT, picked ? GuiTheme.CHIP_ACCENT_BACKGROUND : GuiTheme.CHIP_BACKGROUND);
			final int swatchLeft = x + TIER_CHIP_MARGIN;
			final int swatchTop = tierTop + (CHIP_HEIGHT - TIER_SWATCH) / 2;
			if (hidden) {
				RenderUtils.drawInnerOutline(guiGraphics, swatchLeft, swatchTop, swatchLeft + TIER_SWATCH, swatchTop + TIER_SWATCH, tier.colour);
			} else {
				RenderUtils.drawRect(guiGraphics, swatchLeft, swatchTop, swatchLeft + TIER_SWATCH, swatchTop + TIER_SWATCH, tier.colour);
			}
			if (tierCountsShown) {
				final int colour = hidden || counts[i] == 0 ? GuiTheme.TEXT_MUTED : picked || tier == hoveredTier ? GuiTheme.TEXT_PRIMARY : GuiTheme.TEXT_SECONDARY;
				guiGraphics.drawString(font, String.valueOf(counts[i]), swatchLeft + TIER_SWATCH + TIER_COUNT_GAP, tierTop + 2, colour, false);
			}
		}
	}

	/** Every section in sight: its heading, its id and note, and its tiles. */
	private void renderSections(GuiGraphics guiGraphics, Font font, int innerLeft, int listRight) {
		final boolean advanced = Minecraft.getInstance().options.advancedItemTooltips;
		for (int i = 0; i < sections.size(); i++) {
			final Section section = sections.get(i);
			final int sectionTop = section.top + listTop - scroll;
			if (sectionTop >= listBottom || section.bottom + listTop - scroll <= listTop) {
				continue;
			}
			if (i > 0) {
				RenderUtils.drawRect(guiGraphics, innerLeft, sectionTop - SECTION_GAP / 2 - 1, listRight, sectionTop - SECTION_GAP / 2, SEPARATOR);
			}
			final boolean lit = section == hoveredHeading;
			if (lit) {
				RenderUtils.drawRect(guiGraphics, innerLeft, sectionTop - 1, listRight, sectionTop + TITLE_ROW, HEADING_HOVER);
			}
			guiGraphics.renderItem(section.icon, innerLeft, sectionTop);
			final int titleY = sectionTop + (ICON - font.lineHeight) / 2 + 1;
			final String count = I18n.get("string.explorerscompass.lootTableCount", String.valueOf(section.containers));
			guiGraphics.drawString(font, count, listRight - font.width(count), titleY, GuiTheme.TEXT_SECONDARY, false);
			guiGraphics.drawString(font, RenderUtils.trimToWidth(section.title.getString(), listRight - font.width(count) - 4 - (innerLeft + ICON + 4)), innerLeft + ICON + 4, titleY, lit ? GuiTheme.ACCENT : GuiTheme.TEXT_PRIMARY, true);
			int lineY = sectionTop + TITLE_ROW;
			if (advanced && !section.id.isEmpty()) {
				guiGraphics.drawString(font, RenderUtils.trimToWidth(section.id, listRight - innerLeft), innerLeft, lineY, GuiTheme.TEXT_MUTED, false);
				lineY += LINE;
			}
			if (section.note != null) {
				guiGraphics.drawString(font, RenderUtils.trimToWidth(section.note.getString(), listRight - innerLeft), innerLeft, lineY, GuiTheme.TEXT_MUTED, false);
			}
			if (section.grid == null) {
				continue;
			}
			final int gridY = section.gridTop + listTop - scroll;
			if (section.grid.tiles().isEmpty()) {
				guiGraphics.drawString(font, RenderUtils.trimToWidth(I18n.get("string.explorerscompass.lootTierEmpty"), listRight - innerLeft), innerLeft, gridY, GuiTheme.TEXT_MUTED, false);
			} else {
				LootGrid.render(guiGraphics, font, section.grid, section.entries, innerLeft, gridY, section == hoveredSection ? hoveredTile : null, listTop, listBottom);
			}
		}
	}

	/**
	 * The tooltip for what is being pointed at, if anything: an item's, or what a chip does. Drawn
	 * apart from the drawer itself so that the screen can put it in front of everything.
	 */
	void renderTooltip(GuiGraphics guiGraphics, int mouseX, int mouseY) {
		if (!open || laidOutFor == null) {
			return;
		}
		final Font font = Minecraft.getInstance().font;
		if (hoveredEntry != null) {
			LootTooltip.render(guiGraphics, font, laidOutFor, stats, hoveredEntry.variant(), hoveredEntry.table(), hoveredEntry.item(), mouseX, mouseY);
		} else if (hoveredView != null) {
			// Both views lead with the summary, which the heading leaves out where it does not fit
			final List<Component> lines = new ArrayList<Component>();
			lines.add(Component.literal(summary).withColor(GuiTheme.TEXT_SECONDARY));
			lines.add(Component.translatable(hoveredView.key + ".tooltip"));
			guiGraphics.renderTooltip(font, wrapped(font, lines), mouseX, mouseY);
		} else if (hoveredTier != null) {
			final List<Component> lines = new ArrayList<Component>();
			lines.add(hoveredTier.displayName().copy().withColor(hoveredTier.colour & 0xFFFFFF)
					.append(Component.literal(" · ").append(hoveredTier.hint()).withColor(GuiTheme.TEXT_SECONDARY)));
			// A tier with nothing in it cannot be picked out, so picking it out is not offered
			if (solo == hoveredTier || tierCounts()[hoveredTier.ordinal()] > 0) {
				lines.add(Component.translatable("string.explorerscompass.lootTierFilterHint").withColor(HINT));
			}
			guiGraphics.renderTooltip(font, wrapped(font, lines), mouseX, mouseY);
		}
	}

	/**
	 * A view's description runs to a sentence, and a tooltip is drawn one line to a component, so the
	 * long ones are split at a readable width. Split as styled text rather than as a string, so that a
	 * line coloured in parts, like a tier's name and its range, keeps each part's colour across the break.
	 */
	private static List<FormattedCharSequence> wrapped(Font font, List<Component> lines) {
		final List<FormattedCharSequence> out = new ArrayList<FormattedCharSequence>();
		for (Component line : lines) {
			out.addAll(font.split(line, TOOLTIP_WIDTH));
		}
		return out;
	}

	/**
	 * Works out what in the list the pointer is on, and from that what to light up on the model: a
	 * tile lights every table that may give its item, and a heading lights its own table.
	 */
	private void findHover(double mouseX, double mouseY) {
		clearHover();
		final int innerLeft = left + PADDING;
		if (mouseX < innerLeft || mouseX >= listRight() || mouseY < listTop || mouseY >= listBottom) {
			return;
		}
		if (view == View.ITEMS) {
			if (itemGrid != null) {
				final LootGrid.Tile tile = LootGrid.tileAt(itemGrid, innerLeft, listTop - scroll + 2, mouseX, mouseY);
				if (tile != null && tile.entry() >= 0) {
					hoveredTile = tile;
					hoveredEntry = itemEntries.get(tile.entry());
				}
			}
		} else {
			for (Section section : sections) {
				if (mouseY < section.top + listTop - scroll || mouseY >= section.bottom + listTop - scroll) {
					continue;
				}
				if (mouseY < section.headingBottom + listTop - scroll) {
					hoveredHeading = section;
				} else if (section.grid != null) {
					final LootGrid.Tile tile = LootGrid.tileAt(section.grid, innerLeft, section.gridTop + listTop - scroll, mouseX, mouseY);
					if (tile != null && tile.entry() >= 0) {
						hoveredSection = section;
						hoveredTile = tile;
						hoveredEntry = section.entries.get(tile.entry());
					}
				}
				break;
			}
		}
		if (hoveredEntry != null) {
			highlighted = stats.sourceTables(hoveredEntry.variant());
		} else if (hoveredHeading != null) {
			headingTables.clear();
			headingTables.set(hoveredHeading.tableIndex);
			highlighted = headingTables;
		}
	}

	private void clearHover() {
		hoveredSection = null;
		hoveredTile = null;
		hoveredEntry = null;
		hoveredHeading = null;
		hoveredView = null;
		hoveredTier = null;
		highlighted = NONE;
	}

	/** Where the list ends on the right, short of the scrollbar. */
	private int listRight() {
		return right - PADDING - SCROLLBAR_WIDTH - SCROLLBAR_GAP;
	}

	private int maxScroll() {
		return Math.max(0, contentHeight - (listBottom - listTop));
	}

	private int viewChipWidth() {
		return (right - left - 2 * PADDING - VIEW_CHIP_GAP) / 2;
	}

	private int viewChipLeft(View each) {
		return left + PADDING + each.ordinal() * (viewChipWidth() + VIEW_CHIP_GAP);
	}

	private View viewAt(double mouseX, double mouseY) {
		if (mouseY < viewTop || mouseY >= viewTop + CHIP_HEIGHT) {
			return null;
		}
		final int width = viewChipWidth();
		for (View each : View.values()) {
			final int x = viewChipLeft(each);
			if (mouseX >= x && mouseX < x + width) {
				return each;
			}
		}
		return null;
	}

	private LootTier tierAt(double mouseX, double mouseY) {
		if (mouseY < tierTop || mouseY >= tierTop + CHIP_HEIGHT) {
			return null;
		}
		for (LootTier tier : LootTier.values()) {
			final int x = tierChipLeft[tier.ordinal()];
			if (mouseX >= x && mouseX < x + tierChipWidth[tier.ordinal()]) {
				return tier;
			}
		}
		return null;
	}

	private boolean isOverClose(double mouseX, double mouseY) {
		final int closeLeft = right - PADDING - CLOSE_SIZE;
		final int closeTop = top + PADDING - 1;
		return mouseX >= closeLeft && mouseX < closeLeft + CLOSE_SIZE && mouseY >= closeTop && mouseY < closeTop + CLOSE_SIZE;
	}

	/**
	 * Takes a click on the drawer: the close box closes it, a view chip switches to that view, a tier
	 * chip picks that tier out or puts every tier back, and an item in the item view goes to the table
	 * likeliest to hold it. Anywhere else on it is swallowed, so that it does not turn the model.
	 */
	boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (!isMouseOver(mouseX, mouseY)) {
			return false;
		}
		if (button != 0) {
			return true;
		}
		if (isOverClose(mouseX, mouseY)) {
			setOpen(false);
			return true;
		}
		final View clickedView = viewAt(mouseX, mouseY);
		if (clickedView != null) {
			if (clickedView != view) {
				view = clickedView;
				scroll = 0;
				// A tier is counted afresh in each view, and one that holds nothing in this one would
				// leave an empty list behind a chip that cannot be picked
				if (solo != null && tierCounts()[solo.ordinal()] == 0) {
					solo = null;
				}
			}
			return true;
		}
		final LootTier clickedTier = tierAt(mouseX, mouseY);
		if (clickedTier != null) {
			// Putting every tier back is always allowed; picking one out only where it holds something
			if (solo == clickedTier) {
				solo = null;
			} else if (tierCounts()[clickedTier.ordinal()] > 0) {
				solo = clickedTier;
			}
			return true;
		}
		if (view == View.ITEMS) {
			findHover(mouseX, mouseY);
			if (hoveredEntry != null) {
				final int table = locate(stats.variant(hoveredEntry.variant()));
				clearHover();
				openAt(table);
			}
		}
		return true;
	}

	/**
	 * The table a click on an item goes to: the likeliest to hold it of those some container names,
	 * since a table no container names has no section to go to.
	 */
	private int locate(LootStats.Variant variant) {
		for (int table : variant.tables()) {
			if (stats.containers(table) > 0) {
				return table;
			}
		}
		return variant.bestTable();
	}

	boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
		if (!isMouseOver(mouseX, mouseY)) {
			return false;
		}
		scroll = Mth.clamp(scroll - (int) Math.signum(scrollY) * SCROLL_STEP, 0, maxScroll());
		return true;
	}

}
