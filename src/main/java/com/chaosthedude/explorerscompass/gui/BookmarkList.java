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

	private static final int SELECTION_COLOR = 0x60000000;
	private static final int SELECTION_MARKER_COLOR = 0xC0FFFFFF;
	private static final int SCROLLBAR_TRACK_COLOR = 0x2B000000;
	private static final int SCROLLBAR_THUMB_COLOR = 0xF2000000;

	private final BookmarksScreen parentScreen;

	public BookmarkList(BookmarksScreen parentScreen, Minecraft mc, int width, int height, int top, int bottom, int slotHeight) {
		super(mc, width, height, top, bottom, slotHeight);
		this.parentScreen = parentScreen;
		refreshList();
	}

	@Override
	protected int getScrollbarPosition() {
		return super.getScrollbarPosition() + 20;
	}

	@Override
	public int getRowWidth() {
		return super.getRowWidth() + 50;
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
		for (int j = 0; j < getItemCount(); ++j) {
			int rowTop = getRowTop(j);
			int rowBottom = rowTop + itemHeight;
			if (rowBottom >= y0 && rowTop <= y1) {
				int j1 = itemHeight - 4;
				BookmarkListEntry entry = getEntry(j);
				if (isSelectedItem(j)) {
					final int insideLeft = x0 + width / 2 - getRowWidth() / 2 + 2;
					RenderUtils.drawRect(insideLeft - 4, rowTop - 4, insideLeft + getRowWidth() + 4, rowTop + itemHeight, SELECTION_COLOR);
					RenderUtils.drawRect(insideLeft - 4, rowTop - 4, insideLeft - 2, rowTop + itemHeight, SELECTION_MARKER_COLOR);
				}
				entry.render(poseStack, j, rowTop, getRowLeft(), getRowWidth(), j1, mouseX, mouseY, isMouseOver((double) mouseX, (double) mouseY) && Objects.equals(getEntryAtPosition((double) mouseX, (double) mouseY), entry), partialTicks);
			}
		}

		if (getMaxScroll() > 0) {
			int left = getScrollbarPosition();
			int right = left + 6;
			int height = (int) ((float) ((y1 - y0) * (y1 - y0)) / (float) getMaxPosition());
			height = Mth.clamp(height, 32, y1 - y0 - 8);
			int top = (int) getScrollAmount() * (y1 - y0 - height) / getMaxScroll() + y0;
			if (top < y0) {
				top = y0;
			}

			RenderUtils.drawRect(left, y0, right, y1, SCROLLBAR_TRACK_COLOR);
			RenderUtils.drawRect(left, top, right, top + height, SCROLLBAR_THUMB_COLOR);
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

	public BookmarksScreen getParentScreen() {
		return parentScreen;
	}

}
