package com.chaosthedude.explorerscompass.gui;

import java.util.List;
import java.util.Objects;

import com.chaosthedude.explorerscompass.util.BookmarkEntry;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class BookmarkList extends ObjectSelectionList<BookmarkListEntry> {

	/** How much of the width is left over for the scrollbar and the margins beside the rows. */
	private static final int ROW_INSET = 20;
	private static final int SCROLLBAR_WIDTH = 6;

	private final BookmarksScreen parentScreen;
	private final ListChrome chrome = new ListChrome();

	/**
	 * The list now takes its vertical extent as a height rather than as a pair of edges, which is
	 * what everything the base class works out from it is measured against.
	 */
	public BookmarkList(BookmarksScreen parentScreen, Minecraft mc, int left, int width, int top, int bottom, int slotHeight) {
		super(mc, width, bottom - top, top, slotHeight);
		this.parentScreen = parentScreen;
		setX(left);
		refreshList();
	}

	@Override
	protected int getScrollbarPosition() {
		return getRight() - SCROLLBAR_WIDTH;
	}

	@Override
	public int getRowWidth() {
		return width - ROW_INSET;
	}

	@Override
	public int getRowLeft() {
		return getX() + width / 2 - getRowWidth() / 2;
	}

	@Override
	protected boolean isSelectedItem(int slotIndex) {
		return slotIndex >= 0 && slotIndex < children().size() ? children().get(slotIndex).equals(getSelected()) : false;
	}

	/**
	 * The base class draws a tiled backdrop behind the rows and rules across the top and bottom
	 * edges. This list sits on the screen's own panel, so both are left out.
	 */
	@Override
	protected void renderListBackground(GuiGraphics guiGraphics) {
	}

	@Override
	protected void renderListSeparators(GuiGraphics guiGraphics) {
	}

	/** The scrollbar is drawn in {@link #renderDecorations} instead, to the theme's own colours. */
	@Override
	protected boolean scrollbarVisible() {
		return false;
	}

	@Override
	protected void renderListItems(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		final int bandLeft = getRowLeft() - 3;
		final int bandRight = getRowLeft() + getRowWidth() + 3;
		// The same row the screen explains in a tooltip, so that the two cannot disagree about which
		// one the pointer is on
		final BookmarkListEntry hoveredEntry = getHoveredEntry(mouseX, mouseY);

		chrome.beginFrame();
		// Drawn before any of the rows, so that it slides under them on its way to the next pick
		chrome.drawSelection(guiGraphics, getSelected() != null ? children().indexOf(getSelected()) : -1, this::getRowTop, itemHeight, bandLeft, bandRight);

		final int firstRow = Math.max(0,
				(int) Math.floor(getScrollAmount() / itemHeight) - 1);
		final int visibleRowCount = (int) Math.ceil((double) height / itemHeight) + 3;
		final int lastRow = Math.min(getItemCount(), firstRow + visibleRowCount);
		for (int j = firstRow; j < lastRow; ++j) {
			final int rowTop = getRowTop(j);
			final int bandTop = rowTop - 2;
			final int bandBottom = bandTop + itemHeight;
			if (bandBottom < getY() || bandTop > getBottom()) {
				continue;
			}

			final BookmarkListEntry entry = getEntry(j);
			final boolean hovered = Objects.equals(entry, hoveredEntry);
			chrome.drawRowBand(guiGraphics, entry, bandLeft, bandTop, bandRight, bandBottom, hovered && !isSelectedItem(j), false);

			if (j < getItemCount() - 1) {
				ListChrome.drawSeparator(guiGraphics, bandLeft, bandRight, bandBottom, false);
			}

			entry.render(guiGraphics, j, rowTop, getRowLeft(), getRowWidth(), itemHeight - 4, mouseX, mouseY, hovered, partialTicks);
		}
	}

	/**
	 * The scrollbar, drawn after the rows have been clipped so that it is not cut off by the same
	 * rectangle they are.
	 */
	@Override
	protected void renderDecorations(GuiGraphics guiGraphics, int mouseX, int mouseY) {
		ListChrome.drawScrollbar(guiGraphics, getScrollbarPosition(), getY(), getBottom(), SCROLLBAR_WIDTH, getScrollAmount(), getMaxScroll(), getMaxPosition(), mouseX, mouseY);
	}

	/** Rebuilds the rows, showing the most recently located structure first. */
	public void refreshList() {
		clearEntries();
		chrome.reset();
		final List<BookmarkEntry> bookmarks = parentScreen.getBookmarks();
		for (int i = bookmarks.size() - 1; i >= 0; i--) {
			addEntry(new BookmarkListEntry(this, bookmarks.get(i), i));
		}
		selectBookmark(null);
		setScrollAmount(0);
	}

	public void selectBookmark(BookmarkListEntry entry) {
		setSelected(entry);
		parentScreen.selectBookmark(entry);
	}

	public boolean hasSelection() {
		return getSelected() != null;
	}

	/**
	 * The row under the pointer, so that the screen can explain it above everything else it draws.
	 *
	 * <p>Whether the pointer is over the list at all has to be asked separately. The base class works
	 * the row out from how far the list has been scrolled without ever checking that the pointer is
	 * within the list vertically, so a pointer above or below it still arrives at whichever row that
	 * arithmetic lands on, and the tooltip would name a row that is neither under the pointer nor the
	 * one drawn as hovered.
	 */
	public BookmarkListEntry getHoveredEntry(double mouseX, double mouseY) {
		return isMouseOver(mouseX, mouseY) ? getEntryAtPosition(mouseX, mouseY) : null;
	}

	public BookmarksScreen getParentScreen() {
		return parentScreen;
	}

}
