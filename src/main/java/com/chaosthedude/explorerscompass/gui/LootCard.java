package com.chaosthedude.explorerscompass.gui;

import java.util.ArrayList;
import java.util.List;

import com.chaosthedude.explorerscompass.preview.StructurePreview;
import com.chaosthedude.explorerscompass.util.RenderUtils;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
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
 * is on it, so that it can be read into: each item is a slot, and pointing at one names it along
 * the bottom of the card, with how many come at once and how likely. The screen holds the model
 * still meanwhile.
 *
 * <p>One card can stand for several containers, where a badge does. Each loot table among them
 * gets a section of its own, headed by the container's icon and the table's name and saying how
 * many of the containers name it, with the items in a grid under it. A card shared between several
 * tables shows the first rows of each and says how many items it left out, so that a room lined
 * with pots does not open into a card taller than the screen; the whole of every table is in the
 * overview, which a click on the badge opens.
 *
 * <p>The slots themselves are drawn by {@link LootSlots}, the same way the overview draws them.
 */
@OnlyIn(Dist.CLIENT)
final class LootCard {

	private static final int PADDING = 6;
	private static final int SLOT = LootSlots.SLOT;
	private static final int COLUMNS = 9;
	private static final int LINE = 10;
	private static final int ICON = 16;
	/** The heading of a section: an icon tall, and a pixel under it. */
	private static final int TITLE_ROW = ICON + 1;
	/** Room between the last row of items and the line along the bottom. */
	private static final int FOOTER_GAP = 4;
	/** How many sections a card shows before it only counts the rest. */
	private static final int MAX_SECTIONS = 4;
	/** How many rows of items a section may take when it shares the card with others. */
	private static final int SHARED_SECTION_ROWS = 2;
	/** How wide a card may grow to fit a long name before the name is shortened instead, and how narrow one with nothing but a line of text may be. */
	private static final int MAX_TEXT_WIDTH = 240;
	private static final int MIN_TEXT_WIDTH = 60;
	private static final int GAP_FROM_BADGE = 8;
	private static final int SCREEN_MARGIN = 4;
	/** Drawn over the model and the controls both, at the height the game draws its own tooltips. */
	private static final float Z = 400.0F;

	private static final int BACKGROUND_TOP = 0xF4161A22;
	private static final int BACKGROUND_BOTTOM = 0xF40A0C10;
	private static final int BORDER = 0x60FFFFFF;
	private static final int SEPARATOR = 0x28FFFFFF;
	private static final int MORE_CHIP_BACKGROUND = 0xC0000000;

	/** One item slot laid out on the card, so that the pointer can be matched to it. */
	private record Slot(int x, int y, ItemStack stack, int permille, int minCount, int maxCount) {
	}

	/** One loot table's worth of the card. */
	private static final class Section {

		final int tableIndex;
		final Item icon;
		int containers;
		Component title;
		String id = "";
		int[] items = new int[0];
		int[] chances = new int[0];
		int[] minCounts = new int[0];
		int[] maxCounts = new int[0];
		int rowsShown;
		int hiddenItems;
		/** What is said in place of a grid when there is no grid to show. */
		Component note;

		Section(int tableIndex, Item icon) {
			this.tableIndex = tableIndex;
			this.icon = icon;
		}

	}

	private final List<Section> sections = new ArrayList<Section>();
	private final List<Slot> slots = new ArrayList<Slot>();
	private int badgeId = Integer.MIN_VALUE;
	private int badgeLeft;
	private int badgeTop;
	private int badgeRight;
	private int badgeBottom;
	private int left;
	private int top;
	private int width;
	private int height;
	private Component header;
	private boolean hasFooter;
	/** Whether any section left items out, so that the footer can say where the rest are. */
	private boolean hasHiddenItems;
	private Slot hoveredSlot;

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

	/** Lays the card out for the given badge, beside it, and inside the screen. */
	void layout(StructurePreview preview, StructurePreviewView.LootBadge badge, int screenWidth, int screenHeight) {
		final Font font = Minecraft.getInstance().font;
		badgeId = badge.getId();
		badgeLeft = badge.getBoxLeft();
		badgeTop = badge.getBoxTop();
		badgeRight = badge.getBoxRight();
		badgeBottom = badge.getBoxBottom();
		sections.clear();
		slots.clear();
		hoveredSlot = null;

		gatherSections(preview, badge);
		final boolean shared = sections.size() > 1;
		header = badge.getCount() > 1 ? Component.translatable("string.explorerscompass.lootContainers", String.valueOf(badge.getCount())) : null;

		// Wide enough for the grid where there is one, and for the longest line up to a point
		int textWidth = header == null ? 0 : font.width(header);
		boolean anyGrid = false;
		for (Section section : sections) {
			textWidth = Math.max(textWidth, ICON + 4 + font.width(section.title) + (showsCount(section) ? 4 + font.width(countLabel(section.containers)) : 0));
			if (!section.id.isEmpty()) {
				textWidth = Math.max(textWidth, font.width(section.id));
			}
			if (section.note != null) {
				textWidth = Math.max(textWidth, font.width(section.note));
			}
			anyGrid |= section.items.length > 0;
		}
		final int gridWidth = COLUMNS * SLOT;
		width = Mth.clamp(textWidth, anyGrid ? gridWidth : MIN_TEXT_WIDTH, MAX_TEXT_WIDTH) + 2 * PADDING;

		// The rows and the slots, laid out against a left edge of zero and moved into place below
		hasFooter = false;
		hasHiddenItems = false;
		int y = PADDING;
		if (header != null) {
			y += LINE + 3;
		}
		for (int i = 0; i < sections.size(); i++) {
			final Section section = sections.get(i);
			if (i > 0) {
				y += 4;
			}
			y += TITLE_ROW;
			if (!section.id.isEmpty()) {
				y += LINE;
			}
			if (section.note != null) {
				y += LINE;
			}
			if (section.items.length > 0) {
				final int rows = (section.items.length + COLUMNS - 1) / COLUMNS;
				section.rowsShown = shared ? Math.min(rows, SHARED_SECTION_ROWS) : rows;
				final int shown = Math.min(section.items.length, section.rowsShown * COLUMNS);
				section.hiddenItems = section.items.length - shown;
				hasHiddenItems |= section.hiddenItems > 0;
				y += 2;
				for (int item = 0; item < shown; item++) {
					slots.add(new Slot(PADDING + (item % COLUMNS) * SLOT, y + (item / COLUMNS) * SLOT, LootSlots.stackFor(preview, section.tableIndex, item), section.chances[item], section.minCounts[item], section.maxCounts[item]));
				}
				y += section.rowsShown * SLOT;
				hasFooter = true;
			}
		}
		if (hasFooter) {
			y += FOOTER_GAP + LINE;
		}
		height = y + PADDING - 2;

		// Beside the badge, on whichever side has room, and never off the screen
		int cardLeft = badgeRight + GAP_FROM_BADGE;
		if (cardLeft + width > screenWidth - SCREEN_MARGIN) {
			cardLeft = badgeLeft - GAP_FROM_BADGE - width;
		}
		left = Mth.clamp(cardLeft, SCREEN_MARGIN, Math.max(SCREEN_MARGIN, screenWidth - SCREEN_MARGIN - width));
		top = Mth.clamp((badgeTop + badgeBottom) / 2 - height / 2, SCREEN_MARGIN, Math.max(SCREEN_MARGIN, screenHeight - SCREEN_MARGIN - height));
	}

	/** Gathers the badge's containers by the table each names, nearest first, into sections. */
	private void gatherSections(StructurePreview preview, StructurePreviewView.LootBadge badge) {
		final Int2IntOpenHashMap sectionByTable = new Int2IntOpenHashMap();
		sectionByTable.defaultReturnValue(-1);
		final IntArrayList markers = badge.getMarkers();
		int overflow = 0;
		for (int i = 0; i < markers.size(); i++) {
			final int marker = markers.getInt(i);
			final int table = preview.getLootMarkerTableIndex(marker);
			int index = sectionByTable.get(table);
			if (index < 0) {
				if (sections.size() >= MAX_SECTIONS) {
					overflow++;
					continue;
				}
				index = sections.size();
				sectionByTable.put(table, index);
				sections.add(describe(preview, table, preview.getLootMarkerIcon(marker)));
			}
			sections.get(index).containers++;
		}
		if (overflow > 0) {
			// Said on the last section rather than as a section of its own, which would want a table
			final Section last = sections.get(sections.size() - 1);
			last.note = Component.translatable("string.explorerscompass.lootMoreContainers", String.valueOf(overflow));
		}
	}

	/** What one table is called and what it drops, ready to be laid out. */
	private static Section describe(StructurePreview preview, int table, Item icon) {
		final Section section = new Section(table, icon);
		final String id = preview.getLootTableId(table);
		section.items = preview.getLootTableItems(table);
		section.chances = preview.getLootTableChances(table);
		section.minCounts = preview.getLootTableMinCounts(table);
		section.maxCounts = preview.getLootTableMaxCounts(table);
		if (id.isEmpty()) {
			// A container with no table either holds what it was placed with, or nothing at all
			section.title = Component.translatable(section.items.length > 0 ? "string.explorerscompass.lootFixedItems" : "string.explorerscompass.noLootTable");
			return section;
		}
		section.title = LootSlots.displayName(id);
		if (!section.title.getString().equals(id)) {
			section.id = id;
		}
		if (section.items.length == 0) {
			// Named, but nothing could be read out of it: a table that is missing, or one built from
			// entries a mod added that this does not know how to walk
			section.note = Component.translatable("string.explorerscompass.lootUnresolved");
		}
		return section;
	}

	void render(GuiGraphics guiGraphics, int mouseX, int mouseY) {
		if (badgeId == Integer.MIN_VALUE) {
			return;
		}
		final Font font = Minecraft.getInstance().font;
		hoveredSlot = null;
		for (Slot slot : slots) {
			final int x = left + slot.x();
			final int y = top + slot.y();
			if (mouseX >= x && mouseX < x + SLOT && mouseY >= y && mouseY < y + SLOT) {
				hoveredSlot = slot;
			}
		}

		guiGraphics.pose().pushPose();
		guiGraphics.pose().translate(0.0F, 0.0F, Z);

		RenderUtils.drawVerticalGradient(guiGraphics, left, top, left + width, top + height, BACKGROUND_TOP, BACKGROUND_BOTTOM);
		RenderUtils.drawInnerOutline(guiGraphics, left, top, left + width, top + height, BORDER);
		// The bar along the leading edge, which is how everything picked out on these screens is marked
		RenderUtils.drawRect(guiGraphics, left, top, left + 2, top + height, GuiTheme.ACCENT | 0xFF000000);

		final int textLeft = left + PADDING;
		final int textRight = left + width - PADDING;
		int y = top + PADDING;
		if (header != null) {
			guiGraphics.drawString(font, RenderUtils.trimToWidth(header.getString(), textRight - textLeft), textLeft, y, GuiTheme.TEXT_SECONDARY, false);
			y += LINE;
			RenderUtils.drawRect(guiGraphics, textLeft, y, textRight, y + 1, SEPARATOR);
			y += 3;
		}

		for (int i = 0; i < sections.size(); i++) {
			final Section section = sections.get(i);
			if (i > 0) {
				RenderUtils.drawRect(guiGraphics, textLeft, y + 1, textRight, y + 2, SEPARATOR);
				y += 4;
			}
			// The heading: the container's icon, the table's name level with its middle, and how many
			// containers name it against the right edge
			final ItemStack icon = new ItemStack(section.icon == null || section.icon == Items.AIR ? Items.CHEST : section.icon);
			guiGraphics.renderItem(icon, textLeft, y);
			final int titleY = y + (ICON - font.lineHeight) / 2 + 1;
			int titleRight = textRight;
			if (showsCount(section)) {
				final String count = countLabel(section.containers);
				titleRight -= font.width(count) + 4;
				guiGraphics.drawString(font, count, textRight - font.width(count), titleY, GuiTheme.TEXT_SECONDARY, false);
			}
			guiGraphics.drawString(font, RenderUtils.trimToWidth(section.title.getString(), titleRight - (textLeft + ICON + 4)), textLeft + ICON + 4, titleY, GuiTheme.TEXT_PRIMARY, true);
			y += TITLE_ROW;
			if (!section.id.isEmpty()) {
				guiGraphics.drawString(font, RenderUtils.trimToWidth(section.id, textRight - textLeft), textLeft, y, GuiTheme.TEXT_MUTED, false);
				y += LINE;
			}
			if (section.note != null) {
				guiGraphics.drawString(font, RenderUtils.trimToWidth(section.note.getString(), textRight - textLeft), textLeft, y, GuiTheme.TEXT_MUTED, false);
				y += LINE;
			}
			if (section.items.length > 0) {
				y += 2 + section.rowsShown * SLOT;
				if (section.hiddenItems > 0) {
					// Pinned to the last slot of the last row, where the eye runs out of items, and
					// lifted over the item drawn there
					final String more = I18n.get("string.explorerscompass.lootMoreItems", String.valueOf(section.hiddenItems));
					final int chipWidth = font.width(more) + 4;
					final int chipLeft = textLeft + COLUMNS * SLOT - chipWidth;
					guiGraphics.pose().pushPose();
					guiGraphics.pose().translate(0.0F, 0.0F, LootSlots.OVER_ITEMS);
					RenderUtils.drawRect(guiGraphics, chipLeft, y - 11, chipLeft + chipWidth, y - 1, MORE_CHIP_BACKGROUND);
					guiGraphics.drawString(font, more, chipLeft + 2, y - 10, GuiTheme.ACCENT, false);
					guiGraphics.pose().popPose();
				}
			}
		}

		for (Slot slot : slots) {
			LootSlots.drawSlot(guiGraphics, font, left + slot.x(), top + slot.y(), slot.stack(), slot.permille(), slot == hoveredSlot);
		}

		if (hasFooter) {
			// Level with where the layout left room for it, under a rule a pixel clear of the last row
			final int footerY = top + height - PADDING - LINE + 2;
			RenderUtils.drawRect(guiGraphics, textLeft, footerY - FOOTER_GAP + 1, textRight, footerY - FOOTER_GAP + 2, SEPARATOR);
			if (hoveredSlot != null && !hoveredSlot.stack().isEmpty()) {
				LootSlots.drawDetail(guiGraphics, font, textLeft, textRight, footerY, hoveredSlot.stack(), hoveredSlot.permille(), hoveredSlot.minCount(), hoveredSlot.maxCount());
			} else {
				// Where the card left items out, the one thing worth saying is where the rest are
				final String hint = I18n.get(hasHiddenItems ? "string.explorerscompass.lootClickForAll" : "string.explorerscompass.lootChanceHint");
				guiGraphics.drawString(font, RenderUtils.trimToWidth(hint, textRight - textLeft), textLeft, footerY, GuiTheme.TEXT_MUTED, false);
			}
		}

		guiGraphics.pose().popPose();
	}

	/**
	 * Whether a section says how many containers name its table. Only where the card is shared
	 * between tables: with one table the heading above the card already says how many there are.
	 */
	private boolean showsCount(Section section) {
		return sections.size() > 1 && section.containers > 1;
	}

	private static String countLabel(int containers) {
		return I18n.get("string.explorerscompass.lootTableCount", String.valueOf(containers));
	}

}
