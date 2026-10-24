package com.chaosthedude.explorerscompass.gui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

import com.chaosthedude.explorerscompass.preview.StructurePreview;
import com.chaosthedude.explorerscompass.util.RenderUtils;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.ChatFormatting;
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
 * The card that opens beside a loot badge, saying what the container holds.
 *
 * <p>Laid out beside the badge rather than under the pointer, and left standing while the pointer
 * is on it, so that it can be read into: each item is a tile, likeliest first and without a break,
 * marked with its tier in the corner and with how likely under it, and pointing at one brings up
 * the item's tooltip, with everything the loot says about it, and lights every badge on the model
 * that can give it. The screen holds the model still meanwhile.
 *
 * <p>The heading puts the container's icon on a plate the size of a slot, whose left edge is the
 * left edge of the slots under it, and tallies under the table's name how many of its drops fall
 * in each tier, so that what a container is worth can be read before its tiles are. How many
 * containers name the table is said in a pill at the right, where that is more than one.
 *
 * <p>One card can stand for several containers, where a badge does. Where they name more than one
 * loot table, each table gets a tab along the top, showing the container's icon with how many of
 * the containers name it beside it, and the card shows one table at a time: the one the badge is
 * drawn as, until another tab is clicked or scrolled to. The card is as tall as the tallest of its
 * tables, so that it does not jump under the pointer from one tab to the next.
 *
 * <p>A table too long for the screen shows what fits, keeping the last row for its rarest items,
 * which are the ones worth finding, and says along the bottom how many it left out; the whole of
 * every table is in the overview, which a click on the badge or the card opens.
 *
 * <p>The tiles are laid out and drawn by {@link LootGrid}, the same way the overview draws them,
 * and what is worked out about each item comes from {@link LootStats}.
 */
@OnlyIn(Dist.CLIENT)
final class LootCard {

	/** The bar along the leading edge, which the padding is counted from. */
	private static final int BAR = 2;
	private static final int PAD = 7;
	/** The heading: a plate a little bigger than a slot, holding the container's icon, with the name and the tally beside it. */
	private static final int HEADING = 20;
	private static final int PLATE_ICON = 2;
	/** Where the name starts beside the plate, and where the tally stands under it. */
	private static final int TITLE_X = 25;
	private static final int TITLE_Y = 1;
	private static final int TALLY_Y = 12;
	/** Room between the end of the name and the pill counting the containers. */
	private static final int PILL_GAP = 4;
	private static final int LINE = 10;
	/** A line of text without the room under it that {@link #LINE} leaves, for the line along the bottom, which has the padding under it instead. */
	private static final int TEXT_HEIGHT = 9;
	/** Room between the heading and the lines of text under it, where there are any. */
	private static final int LINE_GAP = 2;
	/** Room above the rule over the grid, and under it. */
	private static final int RULE_ABOVE = 4;
	private static final int RULE_BELOW = 6;
	/**
	 * The room every row of tiles leaves under itself. The last row on the card does without it,
	 * since the padding along the bottom already parts it from the edge.
	 */
	private static final int ROW_TRAIL = 3;
	/** Room between the last row of items and the rule along the bottom, and between that rule and the line under it. */
	private static final int FOOTER_RULE_GAP = 4;
	private static final int FOOTER_TEXT_GAP = 5;
	/** The line along the bottom that says how many items were left out, with its rule and the room around them. */
	private static final int FOOTER = FOOTER_RULE_GAP + 1 + FOOTER_TEXT_GAP + TEXT_HEIGHT;
	/** A tab with nothing but an icon on it, which is also how wide the tab counting the tables left out is. */
	private static final int TAB_W = 22;
	private static final int TAB_H = 20;
	private static final int TAB_GAP = 2;
	private static final int TAB_ICON_X = 3;
	private static final int TAB_ICON_Y = 1;
	/** Where the count stands beside the icon, level with its middle, and the room it keeps from the tab's right edge. */
	private static final int TAB_COUNT_X = 21;
	private static final int TAB_COUNT_Y = 6;
	private static final int TAB_COUNT_PAD = 2;
	/** The bar along the foot of the tab shown. */
	private static final int TAB_BAR = 2;
	/** The row of tabs, and a little room under it before the heading. */
	private static final int TAB_ROW = TAB_H + 6;
	/** How many tiles a row may hold, which is as many as a chest's row of slots. */
	private static final int MAX_COLUMNS = 9;
	/** How wide a card may grow to fit a long name before the name is shortened instead, and how narrow one with nothing but a line of text may be. */
	private static final int MAX_TEXT_WIDTH = 240;
	private static final int MIN_TEXT_WIDTH = 60;
	/** How few rows of items a card keeps however short the screen, so that a long table still shows more than its first few. */
	private static final int MIN_ROWS = 2;
	private static final int GAP_FROM_BADGE = 8;
	private static final int SCREEN_MARGIN = 4;
	/** Drawn over the model and the controls both, at the height the game draws its own tooltips. */
	private static final float Z = 400.0F;

	/** Lit for nothing, handed out where no tile is pointed at. Never changed. */
	private static final BitSet NO_TABLES = new BitSet();

	/** One loot table's worth of the card. */
	private static final class Section {

		final int table;
		final ItemStack icon;
		/** How many of the badge's containers name the table. */
		int containers;
		Component title;
		/** The table's id, said under its name with advanced tooltips on, or empty where it is not said. */
		String id = "";
		/** What is said in place of a grid when there is no grid to show. */
		Component note;
		/** The table's items, likeliest first, and their chances in the same order. */
		final List<LootGrid.Entry> entries = new ArrayList<LootGrid.Entry>();
		int[] permilles = new int[0];
		/** How many of the items fall in each tier, in the order of {@link LootTier#values()}, for the tally under the name. */
		final int[] tierCounts = new int[LootTier.values().length];
		/** Where the tiles fall, or null where the table gives nothing to show. */
		LootGrid.Layout grid;
		/** How far below the top of the card the grid begins. */
		int gridTop;

		Section(int table, ItemStack icon) {
			this.table = table;
			this.icon = icon;
		}

		/** How tall the heading stands with the lines of text under it, where there are any. */
		int headingHeight() {
			int height = HEADING;
			if (!id.isEmpty() || note != null) {
				height += LINE_GAP;
			}
			if (!id.isEmpty()) {
				height += LINE;
			}
			if (note != null) {
				height += LINE;
			}
			return height;
		}

		/** How far below the top of the heading the grid begins, under the rule that parts the two. */
		int gridOffset() {
			return headingHeight() + RULE_ABOVE + 1 + RULE_BELOW;
		}

		/** How tall the section stands under the tabs, the line along the bottom included where it has one. */
		int height() {
			if (grid == null) {
				return headingHeight();
			}
			int height = gridOffset() + grid.height() - ROW_TRAIL;
			if (grid.hidden() > 0) {
				height += FOOTER;
			}
			return height;
		}

	}

	/**
	 * One tab along the top, with its left edge relative to the card's and its width. The section is
	 * the index of the table it stands for, or -1 for the tab that counts the tables there was no
	 * room for, in which case it carries how many that is.
	 */
	private record Tab(int x, int w, int section, int hidden) {
	}

	private final List<Section> sections = new ArrayList<Section>();
	private final List<Tab> tabs = new ArrayList<Tab>();
	private StructurePreview preview;
	private LootStats stats;
	private int badgeId = Integer.MIN_VALUE;
	private int badgeLeft;
	private int badgeTop;
	private int badgeRight;
	private int badgeBottom;
	private int left;
	private int top;
	private int width;
	private int height;

	/** The table shown, by its index in the preview, or -1 before the card has been laid out. */
	private int selectedTable = -1;
	/** The section of the table shown, as an index into {@link #sections}. */
	private int selectedSection;

	/** What the layout was last worked out for, so that it is only worked out again when one of them changes. */
	private final IntArrayList builtMarkers = new IntArrayList();
	private int builtSelectedTable = -1;
	private int builtAreaLeft = -1;
	private int builtAreaRight = -1;
	private int builtScreenHeight = -1;
	private boolean builtAdvanced;

	private LootGrid.Tile hoveredTile;
	private Tab hoveredTab;
	/** Whether the card was drawn since its tooltip last was, so that a card no longer open leaves no tooltip and no lit badge behind. */
	private boolean rendered;

	/** Whether the card has been laid out for the given badge, so that its rectangle can be trusted. */
	boolean isFor(StructurePreviewView.LootBadge badge) {
		return badge != null && badge.getId() == badgeId;
	}

	/** Whether the pointer is on the card itself. */
	boolean isMouseOver(double mouseX, double mouseY) {
		return badgeId != Integer.MIN_VALUE && mouseX >= left && mouseX < left + width && mouseY >= top && mouseY < top + height;
	}

	/**
	 * Whether the pointer is on the card, or crossing from the badge to it. The two stand a little
	 * apart, and a card that closed while the pointer crossed the gap could never be reached. The
	 * crossing is a band as tall as the badge running from it to the card, and no wider, so that a
	 * badge standing above or below the gap can still be pointed at.
	 */
	boolean isKeepingOpen(double mouseX, double mouseY) {
		if (isMouseOver(mouseX, mouseY)) {
			return true;
		}
		final int bandLeft = Math.min(left, badgeLeft) - 1;
		final int bandRight = Math.max(left + width, badgeRight) + 1;
		return mouseX >= bandLeft && mouseX < bandRight && mouseY >= badgeTop - 2 && mouseY < badgeBottom + 2;
	}

	/**
	 * Lays the card out for the given badge, beside it, inside the given span across the screen and
	 * inside the screen's height. Called every frame the card is open; what is on the card is only
	 * worked out again when the badge, the preview, the table shown, the room it has or whether
	 * advanced tooltips are on changes, and otherwise the card is only moved to stay beside its badge.
	 *
	 * <p>The span stops short of the slider at the edge of the model, and of the drawer where it is
	 * open, rather than running to the edge of the screen: the pointer on either is taken to have
	 * left the badge, so the part of a card standing over them would close the card when reached
	 * for. The sidebar on the other side is no such trouble, since the screen hands a click on the
	 * card to the card before its controls, and a card kept off it would end up over its own badge.
	 */
	void layout(StructurePreview preview, StructurePreviewView.LootBadge badge, int areaLeft, int areaRight, int screenHeight) {
		final boolean advanced = Minecraft.getInstance().options.advancedItemTooltips;
		final boolean otherBadge = badge.getId() != badgeId || preview != this.preview;
		if (otherBadge) {
			// A badge opens on the table it is drawn as, which is the container most worth seeing
			selectedTable = preview.getLootMarkerTableIndex(badge.getShownMarker());
		}
		badgeId = badge.getId();
		this.preview = preview;
		badgeLeft = badge.getBoxLeft();
		badgeTop = badge.getBoxTop();
		badgeRight = badge.getBoxRight();
		badgeBottom = badge.getBoxBottom();
		// The markers as well as the badge's id, since a badge gathers whichever containers stand
		// near the one it is named for, and those change as the model turns
		if (otherBadge || !badge.getMarkers().equals(builtMarkers) || selectedTable != builtSelectedTable || areaLeft != builtAreaLeft || areaRight != builtAreaRight || screenHeight != builtScreenHeight || advanced != builtAdvanced) {
			rebuild(preview, badge, areaRight - areaLeft, screenHeight, advanced);
			builtAreaLeft = areaLeft;
			builtAreaRight = areaRight;
		}

		// Beside the badge, on whichever side has room, and never out of the span it was given
		int cardLeft = badgeRight + GAP_FROM_BADGE;
		if (cardLeft + width > areaRight - SCREEN_MARGIN) {
			cardLeft = badgeLeft - GAP_FROM_BADGE - width;
		}
		left = Mth.clamp(cardLeft, areaLeft + SCREEN_MARGIN, Math.max(areaLeft + SCREEN_MARGIN, areaRight - SCREEN_MARGIN - width));
		top = Mth.clamp((badgeTop + badgeBottom) / 2 - height / 2, SCREEN_MARGIN, Math.max(SCREEN_MARGIN, screenHeight - SCREEN_MARGIN - height));
	}

	/**
	 * Works out what is on the card and how big it is, against a top left corner of nought, for a
	 * span of the given width.
	 *
	 * <p>The grid is drawn a slot's inset left of the padding, so that the slots, and not the room
	 * around them, line up with the plate and the text above; a grid is therefore that much
	 * narrower than its columns, on either side, when the card is sized to it.
	 */
	private void rebuild(StructurePreview preview, StructurePreviewView.LootBadge badge, int areaWidth, int screenHeight, boolean advanced) {
		final Font font = Minecraft.getInstance().font;
		sections.clear();
		tabs.clear();
		hoveredTile = null;
		hoveredTab = null;
		stats = LootStats.of(preview);
		gatherSections(preview, badge);
		for (Section section : sections) {
			describe(preview, section, advanced);
		}
		selectedSection = sectionOf(selectedTable);
		if (selectedSection < 0) {
			// The table shown is no longer among the badge's, which the containers gathered under it changing can do
			selectedTable = preview.getLootMarkerTableIndex(badge.getShownMarker());
			selectedSection = Math.max(0, sectionOf(selectedTable));
		}
		final boolean tabbed = sections.size() > 1;
		final int inset = 2 * LootSlots.SLOT_INSET_X;
		final int chromeWidth = BAR + 2 * PAD;

		// As many columns as the span has room for, up to a chest's row, and no more than the longest table needs
		int mostItems = 0;
		for (Section section : sections) {
			mostItems = Math.max(mostItems, section.entries.size());
		}
		final int columnsFit = Mth.clamp((areaWidth - 2 * SCREEN_MARGIN - chromeWidth + inset) / LootSlots.TILE_W, 1, MAX_COLUMNS);
		final int columns = Math.min(columnsFit, Math.max(1, mostItems));
		// As many rows as the screen has room for under everything else the card holds
		final int chrome = PAD + (tabbed ? TAB_ROW : 0) + HEADING + (advanced ? LINE_GAP + LINE : 0) + RULE_ABOVE + 1 + RULE_BELOW - ROW_TRAIL + FOOTER + PAD;
		final int maxRows = Math.max(MIN_ROWS, (screenHeight - 2 * SCREEN_MARGIN - chrome) / LootSlots.ROW_LABELLED);

		// Wide enough for the grid where there is one, and for the longest line up to a point
		int textWidth = 0;
		boolean anyGrid = false;
		int bodyHeight = 0;
		final int headerHeight = PAD + (tabbed ? TAB_ROW : 0);
		for (Section section : sections) {
			textWidth = Math.max(textWidth, TITLE_X + font.width(section.title) + (section.containers > 1 ? PILL_GAP + LootSlots.pillWidth(font, countLabel(section.containers)) : 0));
			textWidth = Math.max(textWidth, TITLE_X + LootSlots.tallyWidth(font, section.tierCounts));
			if (!section.id.isEmpty()) {
				textWidth = Math.max(textWidth, font.width(section.id));
			}
			if (section.note != null) {
				textWidth = Math.max(textWidth, font.width(section.note));
			}
			if (!section.entries.isEmpty()) {
				anyGrid = true;
				section.grid = LootGrid.flow(section.permilles, columns, maxRows);
				if (section.grid.hidden() > 0) {
					textWidth = Math.max(textWidth, font.width(I18n.get("string.explorerscompass.lootMoreFooter", String.valueOf(section.grid.hidden()))));
				}
				section.gridTop = headerHeight + section.gridOffset();
			}
			bodyHeight = Math.max(bodyHeight, section.height());
		}
		// Never wider than the span, though, where a long line is shortened instead
		final int narrowest = anyGrid ? columns * LootSlots.TILE_W - inset : MIN_TEXT_WIDTH;
		final int inner = Mth.clamp(textWidth, narrowest, Math.min(MAX_TEXT_WIDTH, Math.max(narrowest, areaWidth - 2 * SCREEN_MARGIN - chromeWidth)));
		width = chromeWidth + inner;
		height = headerHeight + bodyHeight + PAD;
		if (tabbed) {
			layoutTabs(font, inner);
		}

		builtMarkers.clear();
		builtMarkers.addAll(badge.getMarkers());
		builtSelectedTable = selectedTable;
		builtScreenHeight = screenHeight;
		builtAdvanced = advanced;
	}

	/**
	 * Lays the tabs out along the top, from the left, as many as fit in the given width. Where they
	 * do not all fit, room is kept at the end for a tab counting the rest, and the table shown, where
	 * it is among the rest, takes the place of as many before it as it needs, so that the tab of what
	 * is shown is always there to be seen.
	 */
	private void layoutTabs(Font font, int inner) {
		final int count = sections.size();
		final int[] widths = new int[count];
		int total = -TAB_GAP;
		for (int i = 0; i < count; i++) {
			widths[i] = tabWidth(font, sections.get(i));
			total += widths[i] + TAB_GAP;
		}
		final IntArrayList shown = new IntArrayList(count);
		if (total <= inner) {
			for (int i = 0; i < count; i++) {
				shown.add(i);
			}
		} else {
			final int room = inner - TAB_GAP - TAB_W;
			int x = 0;
			for (int i = 0; i < count && x + widths[i] <= room; i++) {
				shown.add(i);
				x += widths[i] + TAB_GAP;
			}
			if (!shown.contains(selectedSection)) {
				while (!shown.isEmpty() && x + widths[selectedSection] > room) {
					x -= widths[shown.removeInt(shown.size() - 1)] + TAB_GAP;
				}
				shown.add(selectedSection);
			}
		}
		int x = BAR + PAD;
		for (int i = 0; i < shown.size(); i++) {
			final int section = shown.getInt(i);
			tabs.add(new Tab(x, widths[section], section, 0));
			x += widths[section] + TAB_GAP;
		}
		final int hidden = count - shown.size();
		if (hidden > 0) {
			tabs.add(new Tab(x, TAB_W, -1, hidden));
		}
	}

	/** Gathers the badge's containers by the table each names, nearest first, into sections. */
	private void gatherSections(StructurePreview preview, StructurePreviewView.LootBadge badge) {
		final Int2IntOpenHashMap sectionByTable = new Int2IntOpenHashMap();
		sectionByTable.defaultReturnValue(-1);
		final IntArrayList markers = badge.getMarkers();
		for (int i = 0; i < markers.size(); i++) {
			final int marker = markers.getInt(i);
			final int table = preview.getLootMarkerTableIndex(marker);
			int index = sectionByTable.get(table);
			if (index < 0) {
				index = sections.size();
				sectionByTable.put(table, index);
				final Item icon = preview.getLootMarkerIcon(marker);
				sections.add(new Section(table, new ItemStack(icon == null || icon == Items.AIR ? Items.CHEST : icon)));
			}
			sections.get(index).containers++;
		}
	}

	/** What one table is called and what it drops, likeliest first, ready to be laid out. */
	private void describe(StructurePreview preview, Section section, boolean advanced) {
		final int table = section.table;
		final String id = preview.getLootTableId(table);
		final int[] items = preview.getLootTableItems(table);
		if (id.isEmpty()) {
			// A container with no table either holds what it was placed with, or nothing at all
			section.title = Component.translatable(items.length > 0 ? "string.explorerscompass.lootFixedItems" : "string.explorerscompass.noLootTable");
		} else {
			section.title = LootSlots.displayName(id);
			// Only for the player who asked to see ids, and only where the name does not already say it
			if (advanced && !section.title.getString().equals(id)) {
				section.id = id;
			}
			if (items.length == 0) {
				// Named, but nothing could be read out of it: a table that is missing, or one built from
				// entries a mod added that this does not know how to walk
				section.note = Component.translatable("string.explorerscompass.lootUnresolved");
			}
		}
		if (items.length == 0) {
			return;
		}
		final int[] chances = preview.getLootTableChances(table);
		final int[] minCounts = preview.getLootTableMinCounts(table);
		final int[] maxCounts = preview.getLootTableMaxCounts(table);
		// The table comes likeliest first already; sorted again, keeping ties in place, since cutting a
		// long table short keeps its rarest items by counting on that order
		final Integer[] order = new Integer[items.length];
		for (int item = 0; item < order.length; item++) {
			order[item] = item;
		}
		Arrays.sort(order, (a, b) -> Integer.compare(chances[b], chances[a]));
		section.permilles = new int[order.length];
		for (int i = 0; i < order.length; i++) {
			final int item = order[i];
			final int variant = stats.variantOf(table, item);
			section.permilles[i] = chances[item];
			section.tierCounts[LootTier.of(chances[item]).ordinal()]++;
			section.entries.add(new LootGrid.Entry(stats.stack(variant), chances[item], minCounts[item], maxCounts[item], variant, table, item));
		}
	}

	private int sectionOf(int table) {
		for (int i = 0; i < sections.size(); i++) {
			if (sections.get(i).table == table) {
				return i;
			}
		}
		return -1;
	}

	void render(GuiGraphics guiGraphics, int mouseX, int mouseY) {
		if (badgeId == Integer.MIN_VALUE || sections.isEmpty()) {
			return;
		}
		final Font font = Minecraft.getInstance().font;
		final Section section = sections.get(selectedSection);
		final int innerLeft = left + BAR + PAD;
		final int innerRight = left + width - PAD;
		final int gridLeft = innerLeft - LootSlots.SLOT_INSET_X;
		hoveredTile = section.grid == null ? null : LootGrid.tileAt(section.grid, gridLeft, top + section.gridTop, mouseX, mouseY);
		hoveredTab = tabAt(mouseX, mouseY);
		rendered = true;

		guiGraphics.pose().pushPose();
		guiGraphics.pose().translate(0.0F, 0.0F, Z);

		RenderUtils.drawVerticalGradient(guiGraphics, left, top, left + width, top + height, LootSlots.PANEL_TOP, LootSlots.PANEL_BOTTOM);
		RenderUtils.drawInnerOutline(guiGraphics, left, top, left + width, top + height, LootSlots.PANEL_BORDER);
		// The bar along the leading edge, which is how everything picked out on these screens is marked
		RenderUtils.drawRect(guiGraphics, left, top, left + BAR, top + height, LootSlots.ACCENT_BAR);

		int y = top + PAD;
		if (!tabs.isEmpty()) {
			renderTabs(guiGraphics, font, y);
			y += TAB_ROW;
		}

		// The heading: the container's icon on its plate, the table's name with the tally under it,
		// and how many containers name it in a pill against the right edge
		RenderUtils.drawRect(guiGraphics, innerLeft, y, innerLeft + HEADING, y + HEADING, LootSlots.SLOT_FILL);
		guiGraphics.renderItem(section.icon, innerLeft + PLATE_ICON, y + PLATE_ICON);
		final int titleX = innerLeft + TITLE_X;
		int titleRight = innerRight;
		if (section.containers > 1) {
			final String count = countLabel(section.containers);
			final int pillX = innerRight - LootSlots.pillWidth(font, count);
			LootSlots.drawPill(guiGraphics, font, pillX, y, count);
			titleRight = pillX - PILL_GAP;
		}
		guiGraphics.drawString(font, RenderUtils.trimToWidth(section.title.getString(), titleRight - titleX), titleX, y + TITLE_Y, GuiTheme.TEXT_PRIMARY, true);
		LootSlots.drawTally(guiGraphics, font, titleX, y + TALLY_Y, section.tierCounts);
		y += HEADING;
		if (!section.id.isEmpty() || section.note != null) {
			y += LINE_GAP;
		}
		if (!section.id.isEmpty()) {
			guiGraphics.drawString(font, RenderUtils.trimToWidth(section.id, innerRight - innerLeft), innerLeft, y, GuiTheme.TEXT_MUTED, false);
			y += LINE;
		}
		if (section.note != null) {
			guiGraphics.drawString(font, RenderUtils.trimToWidth(section.note.getString(), innerRight - innerLeft), innerLeft, y, GuiTheme.TEXT_MUTED, false);
		}

		if (section.grid != null) {
			LootSlots.drawHairline(guiGraphics, innerLeft, innerRight, top + section.gridTop - RULE_BELOW - 1);
			LootGrid.render(guiGraphics, font, section.grid, section.entries, gridLeft, top + section.gridTop, hoveredTile, Integer.MIN_VALUE, Integer.MAX_VALUE, innerRight, false, true);
			if (section.grid.hidden() > 0) {
				// Along the bottom of the card, which is where it stands whichever tab is shown, under a
				// rule of its own
				final int footerY = top + height - PAD - TEXT_HEIGHT;
				LootSlots.drawHairline(guiGraphics, innerLeft, innerRight, footerY - FOOTER_TEXT_GAP - 1);
				final String more = I18n.get("string.explorerscompass.lootMoreFooter", String.valueOf(section.grid.hidden()));
				guiGraphics.drawString(font, RenderUtils.trimToWidth(more, innerRight - innerLeft), innerLeft, footerY, GuiTheme.TEXT_MUTED, false);
			}
		}

		guiGraphics.pose().popPose();
	}

	/**
	 * Draws the tabs along the given row: each a container's icon on a tile, the one shown picked out
	 * with a bar along its foot, and how many containers name its table beside the icon where that
	 * is more than one. The count stands beside the icon rather than over it, so that it needs no
	 * plate of its own to be read against the item.
	 */
	private void renderTabs(GuiGraphics guiGraphics, Font font, int y) {
		for (Tab tab : tabs) {
			final int x = left + tab.x();
			final boolean selected = tab.section() == selectedSection;
			RenderUtils.drawRect(guiGraphics, x, y, x + tab.w(), y + TAB_H, selected ? LootSlots.SEG_ON : tab.equals(hoveredTab) ? LootSlots.SEG_HOVER : LootSlots.SEG_TRACK);
			if (selected) {
				RenderUtils.drawRect(guiGraphics, x, y + TAB_H - TAB_BAR, x + tab.w(), y + TAB_H, LootSlots.ACCENT_BAR);
			}
			if (tab.section() >= 0) {
				final Section section = sections.get(tab.section());
				guiGraphics.renderItem(section.icon, x + TAB_ICON_X, y + TAB_ICON_Y);
				if (section.containers > 1) {
					guiGraphics.drawString(font, String.valueOf(section.containers), x + TAB_COUNT_X, y + TAB_COUNT_Y, selected ? GuiTheme.TEXT_PRIMARY : GuiTheme.TEXT_SECONDARY, selected);
				}
			} else {
				final String more = I18n.get("string.explorerscompass.lootMoreItems", String.valueOf(tab.hidden()));
				guiGraphics.drawString(font, more, x + tab.w() / 2 - font.width(more) / 2, y + TAB_COUNT_Y, GuiTheme.TEXT_SECONDARY, false);
			}
		}
	}

	/**
	 * The tooltip for whatever on the card is being pointed at: an item's, with everything the loot
	 * says about it, or a tab's name. Drawn apart from the card itself so that the screen can put it
	 * in front of everything, the card included.
	 */
	void renderTooltip(GuiGraphics guiGraphics, int mouseX, int mouseY) {
		if (!rendered) {
			// The card was not drawn this frame, so whatever was pointed at on it is no longer there
			hoveredTile = null;
			hoveredTab = null;
			return;
		}
		rendered = false;
		final Font font = Minecraft.getInstance().font;
		if (hoveredTile != null && hoveredTile.entry() >= 0) {
			final LootGrid.Entry entry = sections.get(selectedSection).entries.get(hoveredTile.entry());
			LootTooltip.render(guiGraphics, font, preview, stats, entry.variant(), entry.table(), entry.item(), mouseX, mouseY);
		} else if (hoveredTab != null) {
			if (hoveredTab.section() >= 0) {
				ThemedTooltip.render(guiGraphics, font, List.of(tabName(sections.get(hoveredTab.section()))), mouseX, mouseY);
			} else {
				ThemedTooltip.render(guiGraphics, font, List.of(Component.translatable("string.explorerscompass.lootMoreFooter", String.valueOf(hoveredTab.hidden()))), mouseX, mouseY);
			}
		}
	}

	/**
	 * Takes a click on one of the tabs, which shows its table. Anything else on the card is left to
	 * the screen, which opens the overview for it, the tab counting the tables left out included.
	 */
	boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button != 0 || badgeId == Integer.MIN_VALUE) {
			return false;
		}
		final Tab tab = tabAt(mouseX, mouseY);
		if (tab == null || tab.section() < 0) {
			return false;
		}
		select(tab.section());
		return true;
	}

	/**
	 * Turns from one tab to the next, up for the one before, round from the last to the first, where
	 * there is more than one; otherwise leaves the wheel to the model.
	 */
	boolean mouseScrolled(double scrollY) {
		if (sections.size() < 2 || scrollY == 0.0D) {
			return false;
		}
		select(Math.floorMod(selectedSection + (scrollY > 0.0D ? -1 : 1), sections.size()));
		return true;
	}

	/** The table shown, by its index in the preview, or -1 where the card has not been laid out. */
	int getSelectedTable() {
		return selectedTable;
	}

	/**
	 * The tables that give the item being pointed at, so that every badge on the model holding one
	 * can be lit; empty where no item is. Shared, and not to be changed.
	 */
	BitSet getHighlightedTables() {
		if (hoveredTile == null || hoveredTile.entry() < 0 || stats == null) {
			return NO_TABLES;
		}
		return stats.sourceTables(sections.get(selectedSection).entries.get(hoveredTile.entry()).variant());
	}

	/** Shows the given section; the card is laid out again for it the next time it is laid out. */
	private void select(int section) {
		selectedSection = section;
		selectedTable = sections.get(section).table;
		hoveredTile = null;
	}

	private Tab tabAt(double mouseX, double mouseY) {
		final int tabTop = top + PAD;
		if (mouseY < tabTop || mouseY >= tabTop + TAB_H) {
			return null;
		}
		for (Tab tab : tabs) {
			final int x = left + tab.x();
			if (mouseX >= x && mouseX < x + tab.w()) {
				return tab;
			}
		}
		return null;
	}

	/** How wide a table's tab is: room for its icon, and for how many containers name it where that is more than one. */
	private static int tabWidth(Font font, Section section) {
		if (section.containers <= 1) {
			return TAB_W;
		}
		return TAB_W + font.width(String.valueOf(section.containers)) + TAB_COUNT_PAD;
	}

	/** What a tab is called when pointed at: its table's name, and how many of the containers name it where that is more than one. */
	private static Component tabName(Section section) {
		if (section.containers <= 1) {
			return section.title;
		}
		return Component.empty().append(section.title).append(" ").append(Component.literal(countLabel(section.containers)).withStyle(ChatFormatting.GRAY));
	}

	private static String countLabel(int containers) {
		return I18n.get("string.explorerscompass.lootTableCount", String.valueOf(containers));
	}

}
