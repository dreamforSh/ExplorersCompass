package com.chaosthedude.explorerscompass.gui;

import java.util.List;
import java.util.Objects;

import com.chaosthedude.explorerscompass.util.BookmarkEntry;
import com.chaosthedude.explorerscompass.util.RenderUtils;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class BookmarkList extends ObjectSelectionList<BookmarkListEntry> {

	/** How much of the width is left over for the scrollbar and the margins beside the rows. */
	private static final int ROW_INSET = 20;
	private static final int SCROLLBAR_WIDTH = 6;

	private final BookmarksScreen parentScreen;

	public BookmarkList(BookmarksScreen parentScreen, Minecraft mc, int left, int width, int height, int top, int bottom, int slotHeight) {
		super(mc, width, height, top, bottom, slotHeight);
		this.parentScreen = parentScreen;
		setLeftPos(left);
		refreshList();
	}

	@Override
	protected int getScrollbarPosition() {
		return x1 - SCROLLBAR_WIDTH;
	}

	@Override
	public int getRowWidth() {
		return width - ROW_INSET;
	}

	@Override
	public int getRowLeft() {
		return x0 + width / 2 - getRowWidth() / 2;
	}

	@Override
	protected boolean isSelectedItem(int slotIndex) {
		return slotIndex >= 0 && slotIndex < children().size() ? children().get(slotIndex).equals(getSelected()) : false;
	}

	@Override
	public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTicks) {
		// A row that is only half inside the list is still drawn whole, so without clipping the rows
		// being scrolled past spill over the title above the list
		RenderUtils.enableScissor(x0, y0, x1, y1);
		renderList(poseStack, mouseX, mouseY, partialTicks);
		RenderUtils.disableScissor();
	}

	@Override
	protected void renderList(PoseStack poseStack, int mouseX, int mouseY, float partialTicks) {
		final int bandLeft = getRowLeft() - 3;
		final int bandRight = getRowLeft() + getRowWidth() + 3;
		final boolean overList = isMouseOver((double) mouseX, (double) mouseY);

		final int firstRow = Math.max(0,
				(int) Math.floor(getScrollAmount() / itemHeight) - 1);
		final int visibleRowCount = (int) Math.ceil((double) (y1 - y0) / itemHeight) + 3;
		final int lastRow = Math.min(getItemCount(), firstRow + visibleRowCount);
		for (int j = firstRow; j < lastRow; ++j) {
			final int rowTop = getRowTop(j);
			final int bandTop = rowTop - 2;
			final int bandBottom = bandTop + itemHeight;
			if (bandBottom < y0 || bandTop > y1) {
				continue;
			}

			final BookmarkListEntry entry = getEntry(j);
			final boolean hovered = overList && Objects.equals(getEntryAtPosition((double) mouseX, (double) mouseY), entry);
			if (isSelectedItem(j)) {
				RenderUtils.drawHorizontalGradient(bandLeft, bandTop, bandRight, bandBottom, GuiTheme.ROW_SELECTED_LEFT, GuiTheme.ROW_SELECTED_RIGHT);
				RenderUtils.drawRect(bandLeft, bandTop, bandLeft + 2, bandBottom, GuiTheme.ACCENT | 0xFF000000);
			} else if (hovered) {
				RenderUtils.drawRect(bandLeft, bandTop, bandRight, bandBottom, GuiTheme.ROW_HOVER);
			}

			if (j < getItemCount() - 1) {
				RenderUtils.drawRect(bandLeft + 2, bandBottom - 1, bandRight - 2, bandBottom, GuiTheme.ROW_SEPARATOR);
			}

			entry.render(poseStack, j, rowTop, getRowLeft(), getRowWidth(), itemHeight - 4, mouseX, mouseY, hovered, partialTicks);
		}

		if (getMaxScroll() > 0) {
			final int left = getScrollbarPosition();
			final int right = left + SCROLLBAR_WIDTH;
			int thumbHeight = (int) ((float) ((y1 - y0) * (y1 - y0)) / (float) getMaxPosition());
			thumbHeight = Mth.clamp(thumbHeight, 32, y1 - y0 - 8);
			int thumbTop = (int) getScrollAmount() * (y1 - y0 - thumbHeight) / getMaxScroll() + y0;
			if (thumbTop < y0) {
				thumbTop = y0;
			}

			RenderUtils.drawRect(left, y0, right, y1, GuiTheme.SCROLLBAR_TRACK);
			RenderUtils.drawRect(left + 1, thumbTop, right - 1, thumbTop + thumbHeight, GuiTheme.SCROLLBAR_THUMB);
		}
	}

	/** Rebuilds the rows, showing the most recently located structure first. */
	public void refreshList() {
		clearEntries();
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

	/** The row under the pointer, so that the screen can explain it above everything else it draws. */
	public BookmarkListEntry getHoveredEntry(double mouseX, double mouseY) {
		return getEntryAtPosition(mouseX, mouseY);
	}

	public BookmarksScreen getParentScreen() {
		return parentScreen;
	}

}
