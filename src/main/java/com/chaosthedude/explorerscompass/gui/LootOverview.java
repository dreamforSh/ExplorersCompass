package com.chaosthedude.explorerscompass.gui;

import java.util.ArrayList;
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
 * that a table can be read against the building it belongs to: pointing at a table's heading lights
 * up every container on the model that names it, and clicking a badge opens the drawer at that
 * container's table.
 *
 * <p>Each table is a section: the container's icon, the table's name, how many containers name
 * it, and every item it may drop in a grid, with nothing left out the way the card has to. The
 * tables most of the containers name come first, since those are what most of the structure is
 * made of; containers that were placed holding something follow, and the empty ones come last.
 * Pointing at an item names it along the bottom, with how many come at once and how likely.
 */
@OnlyIn(Dist.CLIENT)
final class LootOverview {

	private static final int PADDING = 6;
	private static final int SLOT = LootSlots.SLOT;
	private static final int LINE = 10;
	private static final int ICON = 16;
	/** The heading of a section: an icon tall, and a pixel under it. */
	private static final int TITLE_ROW = ICON + 1;
	private static final int SECTION_GAP = 5;
	private static final int SCROLLBAR_WIDTH = 3;
	/** Room between the grid and the scrollbar, so that the last column does not touch it. */
	private static final int SCROLLBAR_GAP = 3;
	private static final int CLOSE_SIZE = 10;
	/** How far one notch of the wheel moves the list: two rows of items. */
	private static final int SCROLL_STEP = SLOT * 2;

	private static final int BACKGROUND_TOP = 0xF0141821;
	private static final int BACKGROUND_BOTTOM = 0xF00A0C10;
	private static final int BORDER = 0x50FFFFFF;
	private static final int SEPARATOR = 0x28FFFFFF;
	private static final int HEADING_HOVER = 0x28FFC24B;

	/** One item slot laid out in the list, at a y before scrolling. */
	private record Slot(int x, int y, ItemStack stack, int permille, int minCount, int maxCount) {
	}

	/** One loot table's worth of the list, at a y before scrolling. */
	private static final class Section {

		final int tableIndex;
		final Item icon;
		final int containers;
		final Component title;
		final String id;
		final Component note;
		final boolean named;
		final boolean placed;
		/** Where the section begins and where its heading ends, before scrolling. */
		int top;
		int headingBottom;

		Section(int tableIndex, Item icon, int containers, Component title, String id, Component note, boolean named, boolean placed) {
			this.tableIndex = tableIndex;
			this.icon = icon;
			this.containers = containers;
			this.title = title;
			this.id = id;
			this.note = note;
			this.named = named;
			this.placed = placed;
		}

	}

	private final List<Section> sections = new ArrayList<Section>();
	private final List<Slot> slots = new ArrayList<Slot>();
	private boolean open;
	/** The preview the sections were gathered from, so that they are gathered again only when it changes. */
	private StructurePreview laidOutFor;
	/** The width the slots were last laid out for, so that they are laid out again only when it changes. */
	private int laidOutColumns = -1;
	private int laidOutLeft;
	private int left;
	private int top;
	private int right;
	private int bottom;
	/** The band the list scrolls in, between the heading of the drawer and its footer. */
	private int listTop;
	private int listBottom;
	private int contentHeight;
	private int scroll;
	/** Where the list wants to be scrolled to, set by a click on a badge and applied at the next layout. */
	private int pendingFocus = -1;
	private int columns = 1;
	private String summary = "";
	private Slot hoveredSlot;
	private int hoveredTable = -1;

	boolean isOpen() {
		return open;
	}

	void setOpen(boolean open) {
		this.open = open;
		if (!open) {
			hoveredSlot = null;
			hoveredTable = -1;
		}
	}

	/** Opens the drawer at the given table's section, so that a click on a badge lands on what it holds. */
	void openAt(int table) {
		setOpen(true);
		pendingFocus = table;
	}

	/** Whether the pointer is anywhere on the drawer. */
	boolean isMouseOver(double mouseX, double mouseY) {
		return open && mouseX >= left && mouseX < right && mouseY >= top && mouseY < bottom;
	}

	/** The table whose heading the pointer is on, or -1, so that its containers can be lit up on the model. */
	int getHoveredTable() {
		return hoveredTable;
	}

	/**
	 * Lays the drawer out in the given rectangle. The sections are gathered afresh when the preview
	 * has changed, and the slots laid out again only when that or the width they are laid out in
	 * has; every other frame keeps the last layout, since a large structure lists hundreds of items.
	 */
	void layout(StructurePreview preview, int left, int top, int right, int bottom) {
		this.left = left;
		this.top = top;
		this.right = right;
		this.bottom = bottom;
		listTop = top + PADDING + LINE + 2 + LINE + 3;
		listBottom = bottom - PADDING - LINE - 4;

		final int innerLeft = left + PADDING;
		final int innerRight = right - PADDING - SCROLLBAR_WIDTH - SCROLLBAR_GAP;
		final int newColumns = Math.max(1, (innerRight - innerLeft) / SLOT);
		if (preview != laidOutFor) {
			gatherSections(preview);
			laidOutFor = preview;
			laidOutColumns = -1;
			scroll = 0;
		}
		if (newColumns != laidOutColumns || innerLeft != laidOutLeft) {
			columns = newColumns;
			laidOutColumns = newColumns;
			laidOutLeft = innerLeft;
			layoutSlots(preview, innerLeft);
		}

		if (pendingFocus >= 0) {
			for (Section section : sections) {
				if (section.tableIndex == pendingFocus) {
					scroll = section.top;
					break;
				}
			}
			pendingFocus = -1;
		}
		scroll = Mth.clamp(scroll, 0, Math.max(0, contentHeight - (listBottom - listTop)));
	}

	/** The rows and the slots of every section, at a y measured from the top of the list before scrolling. */
	private void layoutSlots(StructurePreview preview, int innerLeft) {
		slots.clear();
		int y = 0;
		for (int i = 0; i < sections.size(); i++) {
			final Section section = sections.get(i);
			if (i > 0) {
				y += SECTION_GAP;
			}
			section.top = y;
			y += TITLE_ROW;
			section.headingBottom = y;
			if (!section.id.isEmpty()) {
				y += LINE;
			}
			if (section.note != null) {
				y += LINE;
			}
			final int[] items = preview.getLootTableItems(section.tableIndex);
			if (items.length > 0) {
				final int[] chances = preview.getLootTableChances(section.tableIndex);
				final int[] minCounts = preview.getLootTableMinCounts(section.tableIndex);
				final int[] maxCounts = preview.getLootTableMaxCounts(section.tableIndex);
				y += 2;
				for (int item = 0; item < items.length; item++) {
					slots.add(new Slot(innerLeft + (item % columns) * SLOT, y + (item / columns) * SLOT, LootSlots.stackFor(preview, section.tableIndex, item), chances[item], minCounts[item], maxCounts[item]));
				}
				y += ((items.length + columns - 1) / columns) * SLOT;
			}
			y += 2;
		}
		contentHeight = y;
	}

	/**
	 * One section per table the preview names, with how many containers name each. Ordered by how
	 * much of the structure each is: the tables most containers name first, then what was placed
	 * holding something, then whatever holds nothing at all.
	 */
	private void gatherSections(StructurePreview preview) {
		sections.clear();
		final int[] containers = new int[preview.getLootTableCount()];
		final Item[] icons = new Item[preview.getLootTableCount()];
		for (int marker = 0; marker < preview.getLootMarkerCount(); marker++) {
			final int table = preview.getLootMarkerTableIndex(marker);
			containers[table]++;
			// Drawn as the first container that names it, which for a table is the kind of container it is for
			if (icons[table] == null) {
				icons[table] = preview.getLootMarkerIcon(marker);
			}
		}
		for (int table = 0; table < preview.getLootTableCount(); table++) {
			if (containers[table] == 0) {
				continue;
			}
			final String id = preview.getLootTableId(table);
			final int[] items = preview.getLootTableItems(table);
			final Item icon = icons[table] == null || icons[table] == Items.AIR ? Items.CHEST : icons[table];
			if (id.isEmpty()) {
				final Component title = Component.translatable(items.length > 0 ? "string.explorerscompass.lootFixedItems" : "string.explorerscompass.noLootTable");
				sections.add(new Section(table, icon, containers[table], title, "", null, false, items.length > 0));
				continue;
			}
			final Component title = LootSlots.displayName(id);
			final String shownId = title.getString().equals(id) ? "" : id;
			final Component note = items.length == 0 ? Component.translatable("string.explorerscompass.lootUnresolved") : null;
			sections.add(new Section(table, icon, containers[table], title, shownId, note, true, false));
		}
		sections.sort(Comparator.<Section>comparingInt((section) -> section.named ? 0 : section.placed ? 1 : 2)
				.thenComparing(Comparator.<Section>comparingInt((section) -> section.containers).reversed())
				.thenComparing((section) -> section.title.getString()));
		summary = I18n.get("string.explorerscompass.lootOverviewSummary", String.valueOf(preview.getLootMarkerCount()), String.valueOf(sections.size()));
	}

	void render(GuiGraphics guiGraphics, int mouseX, int mouseY) {
		if (!open) {
			return;
		}
		final Font font = Minecraft.getInstance().font;
		final int innerLeft = left + PADDING;
		final int innerRight = right - PADDING;
		final int listRight = innerRight - SCROLLBAR_WIDTH - SCROLLBAR_GAP;
		final boolean overList = mouseX >= innerLeft && mouseX < innerRight && mouseY >= listTop && mouseY < listBottom;

		hoveredSlot = null;
		hoveredTable = -1;
		if (overList) {
			for (Slot slot : slots) {
				final int y = slot.y() + listTop - scroll;
				if (mouseX >= slot.x() && mouseX < slot.x() + SLOT && mouseY >= y && mouseY < y + SLOT) {
					hoveredSlot = slot;
				}
			}
			if (hoveredSlot == null) {
				for (Section section : sections) {
					final int y = section.top + listTop - scroll;
					if (mouseY >= y && mouseY < y + section.headingBottom - section.top) {
						hoveredTable = section.tableIndex;
					}
				}
			}
		}

		RenderUtils.drawVerticalGradient(guiGraphics, left, top, right, bottom, BACKGROUND_TOP, BACKGROUND_BOTTOM);
		RenderUtils.drawInnerOutline(guiGraphics, left, top, right, bottom, BORDER);
		// The bar along the leading edge, which is how everything picked out on these screens is marked
		RenderUtils.drawRect(guiGraphics, left, top, left + 2, bottom, GuiTheme.ACCENT | 0xFF000000);

		// The heading: the title, the close box against the right edge, and the summary under them
		int y = top + PADDING;
		guiGraphics.drawString(font, RenderUtils.trimToWidth(I18n.get("string.explorerscompass.lootOverview"), innerRight - CLOSE_SIZE - 4 - innerLeft), innerLeft, y, GuiTheme.TEXT_PRIMARY, true);
		final boolean closeLit = isOverClose(mouseX, mouseY);
		final int closeLeft = innerRight - CLOSE_SIZE;
		RenderUtils.drawRect(guiGraphics, closeLeft, y - 1, closeLeft + CLOSE_SIZE, y - 1 + CLOSE_SIZE, closeLit ? GuiTheme.CHIP_ACCENT_BACKGROUND : GuiTheme.CHIP_BACKGROUND);
		guiGraphics.drawString(font, "×", closeLeft + (CLOSE_SIZE - font.width("×")) / 2 + 1, y, closeLit ? GuiTheme.ACCENT : GuiTheme.TEXT_SECONDARY, false);
		y += LINE + 2;
		guiGraphics.drawString(font, RenderUtils.trimToWidth(summary, innerRight - innerLeft), innerLeft, y, GuiTheme.TEXT_SECONDARY, false);
		y += LINE;
		RenderUtils.drawRect(guiGraphics, innerLeft, y, innerRight, y + 1, SEPARATOR);

		// The list, cut to its band so that a section half scrolled out is drawn half
		guiGraphics.enableScissor(innerLeft, listTop, innerRight, listBottom);
		for (int i = 0; i < sections.size(); i++) {
			final Section section = sections.get(i);
			final int sectionTop = section.top + listTop - scroll;
			if (sectionTop > listBottom || sectionTop + sectionHeight(i) < listTop) {
				continue;
			}
			if (i > 0) {
				RenderUtils.drawRect(guiGraphics, innerLeft, sectionTop - SECTION_GAP / 2 - 1, listRight, sectionTop - SECTION_GAP / 2, SEPARATOR);
			}
			if (section.tableIndex == hoveredTable) {
				RenderUtils.drawRect(guiGraphics, innerLeft - 2, sectionTop - 1, listRight, sectionTop + TITLE_ROW, HEADING_HOVER);
			}
			guiGraphics.renderItem(new ItemStack(section.icon), innerLeft, sectionTop);
			final int titleY = sectionTop + (ICON - font.lineHeight) / 2 + 1;
			final String count = I18n.get("string.explorerscompass.lootTableCount", String.valueOf(section.containers));
			guiGraphics.drawString(font, count, listRight - font.width(count), titleY, GuiTheme.TEXT_SECONDARY, false);
			guiGraphics.drawString(font, RenderUtils.trimToWidth(section.title.getString(), listRight - font.width(count) - 4 - (innerLeft + ICON + 4)), innerLeft + ICON + 4, titleY, section.tableIndex == hoveredTable ? GuiTheme.ACCENT : GuiTheme.TEXT_PRIMARY, true);
			int lineY = sectionTop + TITLE_ROW;
			if (!section.id.isEmpty()) {
				guiGraphics.drawString(font, RenderUtils.trimToWidth(section.id, listRight - innerLeft), innerLeft, lineY, GuiTheme.TEXT_MUTED, false);
				lineY += LINE;
			}
			if (section.note != null) {
				guiGraphics.drawString(font, RenderUtils.trimToWidth(section.note.getString(), listRight - innerLeft), innerLeft, lineY, GuiTheme.TEXT_MUTED, false);
			}
		}
		for (Slot slot : slots) {
			final int y0 = slot.y() + listTop - scroll;
			if (y0 + SLOT < listTop || y0 > listBottom) {
				continue;
			}
			LootSlots.drawSlot(guiGraphics, font, slot.x(), y0, slot.stack(), slot.permille(), slot == hoveredSlot);
		}
		guiGraphics.disableScissor();

		// The scrollbar, only where there is more than fits
		final int viewport = listBottom - listTop;
		if (contentHeight > viewport) {
			final int trackLeft = innerRight - SCROLLBAR_WIDTH;
			RenderUtils.drawRect(guiGraphics, trackLeft, listTop, innerRight, listBottom, GuiTheme.SCROLLBAR_TRACK);
			final int thumbHeight = Math.max(8, viewport * viewport / contentHeight);
			final int thumbTop = listTop + Math.round((viewport - thumbHeight) * (scroll / (float) (contentHeight - viewport)));
			RenderUtils.drawRect(guiGraphics, trackLeft, thumbTop, innerRight, thumbTop + thumbHeight, GuiTheme.SCROLLBAR_THUMB);
		}

		// The footer: what the pointer is on, or how to use the drawer
		final int footerY = bottom - PADDING - LINE + 2;
		RenderUtils.drawRect(guiGraphics, innerLeft, listBottom + 1, innerRight, listBottom + 2, SEPARATOR);
		if (hoveredSlot != null && !hoveredSlot.stack().isEmpty()) {
			LootSlots.drawDetail(guiGraphics, font, innerLeft, innerRight, footerY, hoveredSlot.stack(), hoveredSlot.permille(), hoveredSlot.minCount(), hoveredSlot.maxCount());
		} else {
			guiGraphics.drawString(font, RenderUtils.trimToWidth(I18n.get("string.explorerscompass.lootOverviewHint"), innerRight - innerLeft), innerLeft, footerY, GuiTheme.TEXT_MUTED, false);
		}
	}

	/** How tall the given section is, from its top to the top of the next or the end of the list. */
	private int sectionHeight(int index) {
		final int next = index + 1 < sections.size() ? sections.get(index + 1).top - SECTION_GAP : contentHeight;
		return next - sections.get(index).top;
	}

	private boolean isOverClose(double mouseX, double mouseY) {
		final int closeLeft = right - PADDING - CLOSE_SIZE;
		final int closeTop = top + PADDING - 1;
		return mouseX >= closeLeft && mouseX < closeLeft + CLOSE_SIZE && mouseY >= closeTop && mouseY < closeTop + CLOSE_SIZE;
	}

	/** Takes a click on the drawer. Closes it from the close box; anywhere else on it is swallowed, so that it does not turn the model. */
	boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (!isMouseOver(mouseX, mouseY)) {
			return false;
		}
		if (button == 0 && isOverClose(mouseX, mouseY)) {
			setOpen(false);
		}
		return true;
	}

	boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
		if (!isMouseOver(mouseX, mouseY)) {
			return false;
		}
		scroll = Mth.clamp(scroll - (int) Math.signum(scrollY) * SCROLL_STEP, 0, Math.max(0, contentHeight - (listBottom - listTop)));
		return true;
	}

}
