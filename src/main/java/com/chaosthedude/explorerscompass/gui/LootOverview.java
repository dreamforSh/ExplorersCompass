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

	/** The bar down the drawer's leading edge. */
	private static final int BAR = 2;
	/** The room between the drawer's edges, past the bar on the left, and what it holds. */
	private static final int PAD = 7;
	private static final int LINE = 10;
	private static final int ICON = 16;
	/** How far below the drawer's top the title is written, with the close box a pixel higher so that its "×" sits level with it. */
	private static final int TITLE_Y = 8;
	private static final int CLOSE_Y = 7;
	private static final int CLOSE_SIZE = 11;
	/** Where the "×" stands in the close box. */
	private static final int CLOSE_MARK_X = 3;
	private static final int CLOSE_MARK_Y = 2;
	/** How far below the drawer's top the summary is written, on a line of its own under the title. */
	private static final int SUMMARY_Y = 19;
	/** How far below the drawer's top the view switch stands. */
	private static final int SWITCH_Y = 31;
	private static final int SWITCH_HEIGHT = 14;
	/** How far below the top of the view switch its labels are written. */
	private static final int SWITCH_LABEL_Y = 3;
	private static final int CHIP_HEIGHT = 13;
	private static final int CHIP_GAP = 2;
	/** Where a tier chip's icon stands from the chip's top left, and how far right its count starts. */
	private static final int CHIP_ICON_INSET = 2;
	private static final int CHIP_COUNT_X = 13;
	private static final int CHIP_COUNT_Y = 3;
	/** The room a tier chip keeps after its count, which is left out where the chip has less. */
	private static final int CHIP_COUNT_MARGIN = 2;
	/** The room under the view switch and under the tier chips. */
	private static final int CONTROL_GAP = 5;
	private static final int SCROLLBAR_WIDTH = 2;
	/** Room between the list and the scrollbar, so that the last column does not touch it. */
	private static final int SCROLLBAR_GAP = 3;
	/** How far the scrollbar's track stops short of either end of the list. */
	private static final int SCROLLBAR_INSET = 3;
	private static final int SCROLLBAR_MIN_THUMB = 8;
	/** Everything across the drawer that is not columns of tiles: the bar, the room either side and the scrollbar. */
	private static final int CHROME = BAR + PAD + SCROLLBAR_GAP + SCROLLBAR_WIDTH + PAD;
	/** How far above the drawer's bottom the list is cut. */
	private static final int LIST_BOTTOM_GAP = 3;
	/** The room above the list's first heading, so that the light behind it when pointed at is not cut. */
	private static final int CONTENT_TOP = 5;
	/** The room every row of tiles leaves under itself, which the last row of the list has no need of. */
	private static final int TRAIL = LootSlots.ROW_PLAIN - LootSlots.SLOT;
	/** The room between the rule that ends a section and the heading of the next. */
	private static final int HEADING_GAP = 6;
	/** The room between a section's heading and what it holds. */
	private static final int HEADING_CONTENT_GAP = 5;
	/** How far right of a heading's icon its title starts. */
	private static final int HEADING_TITLE_X = ICON + 5;
	/** How far below the top of a heading its pill and its title stand, so that both sit level with the icon's middle. */
	private static final int HEADING_PILL_Y = 3;
	private static final int HEADING_TITLE_Y = 4;
	/** The room kept between a heading's title and its pill. */
	private static final int HEADING_PILL_GAP = 4;
	/** How far the light behind a heading under the pointer reaches past it, above and below, to the left and to the right. */
	private static final int HOVER_REACH_Y = 2;
	private static final int HOVER_REACH_LEFT = 4;
	private static final int HOVER_REACH_RIGHT = 2;
	/** The room between the rule over the item view's footer and its line. */
	private static final int FOOTER_GAP = 5;
	/** How far one notch of the wheel moves the list: one row of labelled tiles. */
	private static final int SCROLL_STEP = LootSlots.ROW_LABELLED;

	/** Lit up when nothing in the drawer is pointed at. Shared, and never changed. */
	private static final BitSet NONE = new BitSet();

	/** The two ways the drawer reads the loot: a section to a table, or a tile to an item across the structure. */
	enum View {
		CONTAINERS("string.explorerscompass.lootViewContainers"),
		ITEMS("string.explorerscompass.lootViewItems");

		/** The key the view's half of the switch is labelled under, and, with ".tooltip" after it, explained under. */
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
	/** How far the list runs from its top, the room above its first heading included, to where its last line ends. */
	private int contentHeight;
	private int scroll;
	/** Where the list wants to be scrolled to, set by a click on a badge or an item and applied at the next layout. */
	private int pendingFocus = -1;
	private int columns = 1;
	/** Where the item view's footer is ruled, measured as the list is, or -1 where it has none. */
	private int footerTop = -1;
	/** Where each tier chip stands, indexed by tier. */
	private final int[] tierChipLeft = new int[LootTier.values().length];
	private final int[] tierChipWidth = new int[LootTier.values().length];
	/** How many containers hold loot and how many tables they name, said three ways, from the whole sentence down to the containers alone. */
	private String summary = "";
	private String shortSummary = "";
	private String containersSummary = "";

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
		viewTop = top + SWITCH_Y;
		tierTop = viewTop + SWITCH_HEIGHT + CONTROL_GAP;
		separatorY = tierTop + CHIP_HEIGHT + CONTROL_GAP;
		listTop = separatorY + 1;
		listBottom = Math.max(listTop, bottom - LIST_BOTTOM_GAP);

		final int innerLeft = innerLeft();
		final int newColumns = Math.max(1, (listRight() - innerLeft + 2 * LootSlots.SLOT_INSET_X) / LootSlots.TILE_W);
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
			layoutTiles(Minecraft.getInstance().font, advanced);
		}
		layoutTierChips(innerLeft, innerRight());

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
		shortSummary = I18n.get("string.explorerscompass.lootOverviewSummaryShort", String.valueOf(containers), String.valueOf(tables));
		containersSummary = I18n.get("string.explorerscompass.lootStructureContainers", String.valueOf(containers));
	}

	/**
	 * Lays the tiles of the current view out in groups by tier, and the sections around them, at a y
	 * measured from the top of the list's content before scrolling.
	 *
	 * <p>A section ends where its last row's room ends, or a little after its last line where it ends
	 * in text, and that is where the rule before the next one goes. The list as a whole ends without
	 * the room after its last row, so that scrolled to the bottom it sits as close to the drawer's
	 * edge as its first heading does to the controls.
	 */
	private void layoutTiles(Font font, boolean advanced) {
		footerTop = -1;
		if (view == View.ITEMS) {
			itemGrid = LootGrid.grouped(itemPermilles, columns, solo);
			if (itemGrid.tiles().isEmpty()) {
				contentHeight = 0;
			} else if (solo != null && itemGrid.hidden() > 0) {
				// Picking a tier out hides the others' tiles without a trace in the list, so a line
				// under it says how many there are, which keeps the filter from reading as the whole
				footerTop = itemGrid.height() + TRAIL;
				contentHeight = CONTENT_TOP + footerTop + FOOTER_GAP + font.lineHeight;
			} else {
				contentHeight = CONTENT_TOP + itemGrid.height() - TRAIL;
			}
			return;
		}
		itemGrid = null;
		int y = 0;
		for (int i = 0; i < sections.size(); i++) {
			final Section section = sections.get(i);
			if (i > 0) {
				y += 1 + HEADING_GAP;
			}
			section.top = y;
			section.headingBottom = y + ICON;
			y = section.headingBottom + HEADING_CONTENT_GAP;
			// The id says nothing the name does not to most players, so it is shown only to those who
			// asked the game for ids
			if (advanced && !section.id.isEmpty()) {
				y += LINE;
			}
			if (section.note != null) {
				y += LINE;
			}
			section.grid = null;
			if (section.entries.isEmpty()) {
				y += TRAIL;
			} else {
				section.gridTop = y;
				section.grid = LootGrid.grouped(section.permilles, columns, solo);
				// A table with nothing in the tier picked out keeps its heading and says so, so that the
				// list still reads as every table the structure has
				y += section.grid.tiles().isEmpty() ? LINE + TRAIL : section.grid.height();
			}
			section.bottom = y;
		}
		contentHeight = sections.isEmpty() ? 0 : CONTENT_TOP + y - TRAIL;
	}

	/**
	 * Lays the four tier chips out across the whole row, equally wide, with the pixels that do not
	 * divide going to the first ones. Equal chips read as one control, and a count that grows by a
	 * digit does not push the chips after it along.
	 */
	private void layoutTierChips(int innerLeft, int innerRight) {
		final int chips = LootTier.values().length;
		final int total = innerRight - innerLeft - CHIP_GAP * (chips - 1);
		int x = innerLeft;
		for (int i = 0; i < chips; i++) {
			tierChipLeft[i] = x;
			tierChipWidth[i] = total / chips + (i < total % chips ? 1 : 0);
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
		final int innerLeft = innerLeft();
		final int innerRight = innerRight();
		final int listRight = listRight();
		findHover(mouseX, mouseY);
		hoveredView = viewAt(mouseX, mouseY);
		hoveredTier = tierAt(mouseX, mouseY);

		RenderUtils.drawVerticalGradient(guiGraphics, left, top, right, bottom, LootSlots.PANEL_TOP, LootSlots.PANEL_BOTTOM);
		RenderUtils.drawInnerOutline(guiGraphics, left, top, right, bottom, LootSlots.PANEL_BORDER);
		// The bar along the leading edge, which is how everything picked out on these screens is marked
		RenderUtils.drawRect(guiGraphics, left, top, left + BAR, bottom, LootSlots.ACCENT_BAR);

		renderHeading(guiGraphics, font, mouseX, mouseY, innerLeft, innerRight);
		LootSlots.drawHairline(guiGraphics, innerLeft, innerRight, separatorY);

		// The list, cut to its band so that a section half scrolled out is drawn half. The band runs
		// left of the list to take in the light behind a heading, and stops short of the scrollbar
		final int origin = contentOrigin();
		guiGraphics.enableScissor(left + BAR, listTop, listClipRight(), listBottom);
		if (view == View.ITEMS) {
			if (itemGrid == null || itemGrid.tiles().isEmpty()) {
				final String empty = RenderUtils.trimToWidth(I18n.get("string.explorerscompass.lootTierEmpty"), listRight - innerLeft);
				guiGraphics.drawString(font, empty, (innerLeft + listRight - font.width(empty)) / 2, (listTop + listBottom - font.lineHeight) / 2, GuiTheme.TEXT_MUTED, false);
			} else {
				LootGrid.render(guiGraphics, font, itemGrid, itemEntries, innerLeft - LootSlots.SLOT_INSET_X, origin, hoveredTile, listTop, listBottom, listRight, true, false);
				if (footerTop >= 0) {
					LootSlots.drawHairline(guiGraphics, innerLeft, listRight, origin + footerTop);
					final String footer = RenderUtils.trimToWidth(I18n.get("string.explorerscompass.lootHiddenByFilter", String.valueOf(itemGrid.hidden())), listRight - innerLeft);
					guiGraphics.drawString(font, footer, innerLeft, origin + footerTop + FOOTER_GAP, GuiTheme.TEXT_MUTED, false);
				}
			}
		} else {
			renderSections(guiGraphics, font, origin, innerLeft, listRight);
		}
		guiGraphics.disableScissor();

		// The scrollbar, only where there is more than fits, kept thin and short of the list's ends so
		// that it reads as where the list is rather than as something to grab
		final int viewport = listBottom - listTop;
		final int trackTop = listTop + SCROLLBAR_INSET;
		final int track = listBottom - SCROLLBAR_INSET - trackTop;
		if (contentHeight > viewport && track > 0) {
			final int trackLeft = innerRight - SCROLLBAR_WIDTH;
			RenderUtils.drawRect(guiGraphics, trackLeft, trackTop, innerRight, trackTop + track, LootSlots.SCROLL_TRACK);
			final int thumbHeight = Math.min(track, Math.max(SCROLLBAR_MIN_THUMB, track * viewport / contentHeight));
			final int thumbTop = trackTop + (track - thumbHeight) * scroll / Math.max(1, contentHeight - viewport);
			RenderUtils.drawRect(guiGraphics, trackLeft, thumbTop, innerRight, thumbTop + thumbHeight, LootSlots.SCROLL_THUMB);
		}
	}

	/**
	 * The heading of the drawer: the title with the close box against the right edge, the summary on
	 * a line of its own under them, then the two views as one switch, then the tier chips.
	 */
	private void renderHeading(GuiGraphics guiGraphics, Font font, int mouseX, int mouseY, int innerLeft, int innerRight) {
		final int closeLeft = innerRight - CLOSE_SIZE;
		final int closeTop = top + CLOSE_Y;
		final String title = RenderUtils.trimToWidth(I18n.get("string.explorerscompass.lootOverview"), closeLeft - 4 - innerLeft);
		guiGraphics.drawString(font, title, innerLeft, top + TITLE_Y, GuiTheme.TEXT_PRIMARY, true);
		final boolean closeLit = isOverClose(mouseX, mouseY);
		RenderUtils.drawRect(guiGraphics, closeLeft, closeTop, closeLeft + CLOSE_SIZE, closeTop + CLOSE_SIZE, closeLit ? LootSlots.CLOSE_HOVER : LootSlots.CLOSE_BG);
		guiGraphics.drawString(font, "×", closeLeft + CLOSE_MARK_X, closeTop + CLOSE_MARK_Y, closeLit ? GuiTheme.TEXT_PRIMARY : GuiTheme.TEXT_SECONDARY, false);
		guiGraphics.drawString(font, fitSummary(font, innerRight - innerLeft), innerLeft, top + SUMMARY_Y, GuiTheme.TEXT_MUTED, false);

		RenderUtils.drawRect(guiGraphics, innerLeft, viewTop, innerRight, viewTop + SWITCH_HEIGHT, LootSlots.SEG_TRACK);
		for (View each : View.values()) {
			// Each part stands a pixel inside the track, so that the track shows round the picked one
			final int partLeft = viewPartLeft(each) + 1;
			final int partRight = each.ordinal() == 0 ? viewPartRight(each) : viewPartRight(each) - 1;
			final boolean current = each == view;
			final boolean lit = each == hoveredView;
			if (current || lit) {
				RenderUtils.drawRect(guiGraphics, partLeft, viewTop + 1, partRight, viewTop + SWITCH_HEIGHT - 1, current ? LootSlots.SEG_ON : LootSlots.SEG_HOVER);
			}
			final String label = RenderUtils.trimToWidth(I18n.get(each.key), partRight - partLeft - 4);
			final int colour = current || lit ? GuiTheme.TEXT_PRIMARY : GuiTheme.TEXT_SECONDARY;
			guiGraphics.drawString(font, label, (partLeft + partRight) / 2 - font.width(label) / 2, viewTop + SWITCH_LABEL_Y, colour, current);
		}

		final int[] counts = tierCounts();
		for (LootTier tier : LootTier.values()) {
			final int i = tier.ordinal();
			final int x = tierChipLeft[i];
			final int width = tierChipWidth[i];
			final boolean picked = solo == tier;
			final boolean empty = counts[i] == 0;
			// A tier hidden by another being picked out, or with nothing in it to pick, is drawn faint,
			// so that the chip still says which tier it is while saying that none of its tiles show
			final boolean dim = empty || solo != null && !picked;
			if (picked) {
				RenderUtils.drawRect(guiGraphics, x, tierTop, x + width, tierTop + CHIP_HEIGHT, LootSlots.tint(tier, LootSlots.CHIP_PICKED_ALPHA));
				RenderUtils.drawInnerOutline(guiGraphics, x, tierTop, x + width, tierTop + CHIP_HEIGHT, LootSlots.CHIP_PICKED_OUTLINE);
			} else {
				RenderUtils.drawRect(guiGraphics, x, tierTop, x + width, tierTop + CHIP_HEIGHT, tier == hoveredTier && !empty ? LootSlots.SEG_HOVER : LootSlots.SEG_TRACK);
			}
			// The count is left out where the chip is too narrow for it rather than being cut, since
			// the icon alone still says which tier is which
			final String count = String.valueOf(counts[i]);
			final boolean counted = CHIP_COUNT_X + font.width(count) + CHIP_COUNT_MARGIN <= width;
			final int iconLeft = counted ? x + CHIP_ICON_INSET : x + (width - LootTier.ICON_SIZE) / 2;
			tier.drawIcon(guiGraphics, iconLeft, tierTop + CHIP_ICON_INSET, false, dim ? LootTier.DIM_ALPHA : 1.0F);
			if (counted) {
				final int colour = dim ? GuiTheme.TEXT_MUTED : picked ? GuiTheme.TEXT_PRIMARY : GuiTheme.TEXT_SECONDARY;
				guiGraphics.drawString(font, count, x + CHIP_COUNT_X, tierTop + CHIP_COUNT_Y, colour, picked);
			}
		}
	}

	/**
	 * The summary as the heading has room for it: the whole sentence, then the shorter one, then the
	 * containers alone, cut only where even that does not fit, so that what is shown is a count that
	 * can be read rather than half of one wherever there is room for that.
	 */
	private String fitSummary(Font font, int width) {
		if (font.width(summary) <= width) {
			return summary;
		}
		if (font.width(shortSummary) <= width) {
			return shortSummary;
		}
		return RenderUtils.trimToWidth(containersSummary, width);
	}

	/**
	 * Every section in sight: the rule before it, its heading, its id and note, and its tiles under a
	 * header for each tier.
	 */
	private void renderSections(GuiGraphics guiGraphics, Font font, int origin, int innerLeft, int listRight) {
		final boolean advanced = Minecraft.getInstance().options.advancedItemTooltips;
		for (int i = 0; i < sections.size(); i++) {
			final Section section = sections.get(i);
			final int y = origin + section.top;
			final int ruleY = y - HEADING_GAP - 1;
			if (ruleY >= listBottom || origin + section.bottom <= listTop) {
				continue;
			}
			if (i > 0) {
				LootSlots.drawHairline(guiGraphics, innerLeft, listRight, ruleY);
			}
			// A heading under the pointer is lit across the list with the bar at its left, the way a
			// picked row is marked elsewhere on these screens, and its title stays white to stay readable
			if (section == hoveredHeading) {
				final int lightLeft = innerLeft - HOVER_REACH_LEFT;
				final int lightTop = y - HOVER_REACH_Y;
				final int lightBottom = origin + section.headingBottom + HOVER_REACH_Y;
				RenderUtils.drawRect(guiGraphics, lightLeft, lightTop, listRight + HOVER_REACH_RIGHT, lightBottom, LootSlots.ROW_HOVER);
				RenderUtils.drawRect(guiGraphics, lightLeft, lightTop, lightLeft + 1, lightBottom, LootSlots.ACCENT_BAR);
			}
			guiGraphics.renderItem(section.icon, innerLeft, y);
			int titleRight = listRight;
			// A table only one container names needs no count to say so
			if (section.containers > 1) {
				final String count = I18n.get("string.explorerscompass.lootTableCount", String.valueOf(section.containers));
				final int pillLeft = listRight - LootSlots.pillWidth(font, count);
				LootSlots.drawPill(guiGraphics, font, pillLeft, y + HEADING_PILL_Y, count);
				titleRight = pillLeft - HEADING_PILL_GAP;
			}
			final int titleX = innerLeft + HEADING_TITLE_X;
			guiGraphics.drawString(font, RenderUtils.trimToWidth(section.title.getString(), titleRight - titleX), titleX, y + HEADING_TITLE_Y, GuiTheme.TEXT_PRIMARY, true);
			int lineY = origin + section.headingBottom + HEADING_CONTENT_GAP;
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
			final int gridY = origin + section.gridTop;
			if (section.grid.tiles().isEmpty()) {
				guiGraphics.drawString(font, RenderUtils.trimToWidth(I18n.get("string.explorerscompass.lootTierEmpty"), listRight - innerLeft), innerLeft, gridY, GuiTheme.TEXT_MUTED, false);
			} else {
				LootGrid.render(guiGraphics, font, section.grid, section.entries, innerLeft - LootSlots.SLOT_INSET_X, gridY, section == hoveredSection ? hoveredTile : null, listTop, listBottom, listRight, false, false);
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
			// Both views lead with the whole summary, which the heading shortens where it does not fit
			final List<Component> lines = new ArrayList<Component>();
			lines.add(Component.literal(summary));
			lines.add(Component.translatable(hoveredView.key + ".tooltip"));
			ThemedTooltip.render(guiGraphics, font, lines, mouseX, mouseY);
		} else if (hoveredTier != null) {
			final List<Component> lines = new ArrayList<Component>();
			lines.add(hoveredTier.displayName().copy().withColor(hoveredTier.colour & 0xFFFFFF)
					.append(Component.literal(" · ").append(hoveredTier.hint()).withColor(GuiTheme.TEXT_SECONDARY)));
			// A tier with nothing in it cannot be picked out, so picking it out is not offered
			if (solo == hoveredTier || tierCounts()[hoveredTier.ordinal()] > 0) {
				lines.add(Component.translatable("string.explorerscompass.lootTierFilterHint").withColor(GuiTheme.TEXT_SECONDARY));
			}
			ThemedTooltip.render(guiGraphics, font, lines, mouseX, mouseY);
		}
	}

	/**
	 * Works out what in the list the pointer is on, and from that what to light up on the model: a
	 * tile lights every table that may give its item, and a heading lights its own table. A heading
	 * answers for all of the light it is drawn with, which reaches a little past it on every side.
	 */
	private void findHover(double mouseX, double mouseY) {
		clearHover();
		if (mouseX < left + BAR || mouseX >= listClipRight() || mouseY < listTop || mouseY >= listBottom) {
			return;
		}
		final int innerLeft = innerLeft();
		final int gridLeft = innerLeft - LootSlots.SLOT_INSET_X;
		final int origin = contentOrigin();
		if (view == View.ITEMS) {
			if (itemGrid != null) {
				final LootGrid.Tile tile = LootGrid.tileAt(itemGrid, gridLeft, origin, mouseX, mouseY);
				if (tile != null && tile.entry() >= 0) {
					hoveredTile = tile;
					hoveredEntry = itemEntries.get(tile.entry());
				}
			}
		} else {
			for (Section section : sections) {
				if (mouseY >= origin + section.top - HOVER_REACH_Y && mouseY < origin + section.headingBottom + HOVER_REACH_Y) {
					if (mouseX >= innerLeft - HOVER_REACH_LEFT) {
						hoveredHeading = section;
					}
					break;
				}
				if (section.grid != null && mouseY >= origin + section.gridTop && mouseY < origin + section.bottom) {
					final LootGrid.Tile tile = LootGrid.tileAt(section.grid, gridLeft, origin + section.gridTop, mouseX, mouseY);
					if (tile != null && tile.entry() >= 0) {
						hoveredSection = section;
						hoveredTile = tile;
						hoveredEntry = section.entries.get(tile.entry());
					}
					break;
				}
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

	/**
	 * The widest the drawer can stand at or under the given width with its list a whole number of
	 * tile columns across, so that the headings, pills and rules of the list end where the last slot
	 * does rather than a part of a column short of it. The width cut goes to the model beside it.
	 * Never less than one column.
	 */
	static int snapWidth(int width) {
		final int columns = Math.max(1, (width - CHROME + 2 * LootSlots.SLOT_INSET_X) / LootSlots.TILE_W);
		return CHROME + columns * LootSlots.TILE_W - 2 * LootSlots.SLOT_INSET_X;
	}

	/** Where what the drawer holds starts on the left, past the bar and the room after it. */
	private int innerLeft() {
		return left + BAR + PAD;
	}

	private int innerRight() {
		return right - PAD;
	}

	/** Where the list ends on the right, short of the scrollbar, which is the right of its last slot. */
	private int listRight() {
		return innerRight() - SCROLLBAR_WIDTH - SCROLLBAR_GAP;
	}

	/** Where the list is cut on the right: past its last slot by the reach of a lit heading, and short of the scrollbar. */
	private int listClipRight() {
		return listRight() + HOVER_REACH_RIGHT;
	}

	/** Where the top of the list's content is drawn, as scrolled. */
	private int contentOrigin() {
		return listTop + CONTENT_TOP - scroll;
	}

	private int maxScroll() {
		return Math.max(0, contentHeight - (listBottom - listTop));
	}

	/** Where the given half of the view switch starts, the first taking whatever pixel does not divide. */
	private int viewPartLeft(View each) {
		final int innerLeft = innerLeft();
		return innerLeft + each.ordinal() * ((innerRight() - innerLeft) / 2);
	}

	private int viewPartRight(View each) {
		return each.ordinal() == 0 ? viewPartLeft(View.values()[1]) : innerRight();
	}

	private View viewAt(double mouseX, double mouseY) {
		if (mouseY < viewTop || mouseY >= viewTop + SWITCH_HEIGHT) {
			return null;
		}
		for (View each : View.values()) {
			if (mouseX >= viewPartLeft(each) && mouseX < viewPartRight(each)) {
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
		final int closeLeft = innerRight() - CLOSE_SIZE;
		final int closeTop = top + CLOSE_Y;
		return mouseX >= closeLeft && mouseX < closeLeft + CLOSE_SIZE && mouseY >= closeTop && mouseY < closeTop + CLOSE_SIZE;
	}

	/**
	 * Takes a click on the drawer: the close box closes it, a half of the view switch switches to that
	 * view, a tier chip picks that tier out or puts every tier back, and an item in the item view goes
	 * to the table likeliest to hold it. Anywhere else on it is swallowed, so that it does not turn the
	 * model.
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
