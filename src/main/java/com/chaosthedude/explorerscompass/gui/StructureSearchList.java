package com.chaosthedude.explorerscompass.gui;

import java.util.Objects;

import com.chaosthedude.explorerscompass.util.RenderUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class StructureSearchList extends ObjectSelectionList<StructureSearchEntry> {

	/** How much of the width is left over for the scrollbar and the margins beside the rows. */
	public static final int ROW_INSET = 20;
	private static final int SCROLLBAR_WIDTH = 6;

	private final ExplorersCompassScreen parentScreen;

	public StructureSearchList(ExplorersCompassScreen parentScreen, Minecraft mc, int left, int width, int height, int top, int bottom, int slotHeight) {
		super(mc, width, height, top, bottom, slotHeight);
		this.parentScreen = parentScreen;
		// The list is laid out where the screen puts it rather than across the whole screen, so that the
		// rows fill the space beside the sidebar and grow with the window instead of staying a fixed size
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
		// Without the offset the base class adds, what is drawn lines up exactly with what can be
		// clicked, which is what lets the badge along the right edge of a row be clickable
		return x0 + width / 2 - getRowWidth() / 2;
	}

	@Override
	protected boolean isSelectedItem(int slotIndex) {
		return slotIndex >= 0 && slotIndex < children().size() ? children().get(slotIndex).equals(getSelected()) : false;
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		// A row that is only half inside the list is still drawn whole, so without clipping the rows
		// being scrolled past spill over the search field and the title above the list
		RenderUtils.enableScissor(guiGraphics, x0, y0, x1, y1);
		renderList(guiGraphics, mouseX, mouseY, partialTicks);
		RenderUtils.disableScissor(guiGraphics);
	}

	@Override
	protected void renderList(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
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

			final StructureSearchEntry entry = getEntry(j);
			final boolean hovered = overList && Objects.equals(getEntryAtPosition((double) mouseX, (double) mouseY), entry);
			if (isSelectedItem(j)) {
				// The selection fades out towards the right, so that the row it marks stays readable
				RenderUtils.drawHorizontalGradient(bandLeft, bandTop, bandRight, bandBottom, GuiTheme.ROW_SELECTED_LEFT, GuiTheme.ROW_SELECTED_RIGHT);
				RenderUtils.drawRect(bandLeft, bandTop, bandLeft + 2, bandBottom, GuiTheme.ACCENT | 0xFF000000);
			} else if (parentScreen.isMultiSelected(entry.getKey())) {
				// Rows picked with Ctrl-click show fainter, with the same accent along their edge
				RenderUtils.drawRect(bandLeft, bandTop, bandRight, bandBottom, GuiTheme.ROW_MULTI_SELECTED);
				RenderUtils.drawRect(bandLeft, bandTop, bandLeft + 2, bandBottom, GuiTheme.ACCENT_DIM);
			} else if (hovered) {
				RenderUtils.drawRect(bandLeft, bandTop, bandRight, bandBottom, GuiTheme.ROW_HOVER);
			}

			if (j < getItemCount() - 1) {
				// A brighter rule closes off the rows held at the top of the list, so that their order
				// reads as deliberate rather than as the sort having gone wrong
				final boolean lastPinned = j == parentScreen.getPinnedCount() - 1;
				RenderUtils.drawRect(bandLeft + 2, bandBottom - 1, bandRight - 2, bandBottom, lastPinned ? GuiTheme.ROW_SECTION_SEPARATOR : GuiTheme.ROW_SEPARATOR);
			}

			entry.render(guiGraphics, j, rowTop, getRowLeft(), getRowWidth(), itemHeight - 4, mouseX, mouseY, hovered, partialTicks);
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

	public void refreshList() {
		clearEntries();
		for (ResourceLocation key : parentScreen.sortKeys()) {
			addEntry(new StructureSearchEntry(this, key));
		}
		selectEntry(null);
		setScrollAmount(0);
	}

	public void selectEntry(StructureSearchEntry entry) {
		setSelected(entry);
		parentScreen.selectEntry(entry);
	}

	/**
	 * Moves the selection up or down the list and scrolls it into view, so that a row can be picked
	 * without leaving the filter field the screen opens focused on.
	 */
	public void moveSelectionBy(int delta) {
		if (children().isEmpty()) {
			return;
		}
		final int size = children().size();
		// From no selection at all, the first press takes the row at whichever end it came from
		final int index = hasSelection() ? Mth.clamp(children().indexOf(getSelected()) + delta, 0, size - 1) : (delta > 0 ? 0 : size - 1);
		final StructureSearchEntry entry = children().get(index);
		selectEntry(entry);
		ensureVisible(entry);
	}

	/** Selects the row at the top of the list, if there is one. */
	public void selectFirst() {
		if (!children().isEmpty()) {
			selectEntry(children().get(0));
			ensureVisible(children().get(0));
		}
	}

	/** Selects the entry for the given key, if the list holds one, and scrolls it into view. */
	public void selectByKey(ResourceLocation key) {
		for (StructureSearchEntry entry : children()) {
			if (entry.getKey().equals(key)) {
				selectEntry(entry);
				centerScrollOn(entry);
				return;
			}
		}
	}

	public boolean hasSelection() {
		return getSelected() != null;
	}

	/** The entry under the pointer, exposed so the owning screen can draw its ID tooltip last. */
	public StructureSearchEntry getHoveredEntry(double mouseX, double mouseY) {
		return getEntryAtPosition(mouseX, mouseY);
	}

	public ExplorersCompassScreen getParentScreen() {
		return parentScreen;
	}

}
