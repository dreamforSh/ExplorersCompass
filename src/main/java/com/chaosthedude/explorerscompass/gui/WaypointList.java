package com.chaosthedude.explorerscompass.gui;

import java.util.Objects;

import com.chaosthedude.explorerscompass.client.CompassWaypoint;
import com.chaosthedude.explorerscompass.util.RenderUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class WaypointList extends ObjectSelectionList<WaypointListEntry> {

	/** How much of the width is left over for the scrollbar and the margins beside the rows. */
	private static final int ROW_INSET = 20;
	private static final int SCROLLBAR_WIDTH = 6;

	private final WaypointsScreen parentScreen;

	public WaypointList(WaypointsScreen parentScreen, Minecraft mc, int left, int width, int height, int top, int bottom, int slotHeight) {
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

	/**
	 * The base class draws a tiled backdrop behind the rows and rules across the top and bottom
	 * edges. This list sits on the screen's own panel, so both are left out, and the rows are clipped
	 * to the list so that the ones being scrolled past do not spill over the title above it.
	 */
	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		RenderUtils.enableScissor(guiGraphics, x0, y0, x1, y1);
		renderList(guiGraphics, mouseX, mouseY, partialTicks);
		RenderUtils.disableScissor(guiGraphics);
	}

	@Override
	protected void renderList(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		final int bandLeft = getRowLeft() - 3;
		final int bandRight = getRowLeft() + getRowWidth() + 3;
		final WaypointListEntry hoveredEntry = getHoveredEntry(mouseX, mouseY);

		final int firstRow = Math.max(0, (int) Math.floor(getScrollAmount() / itemHeight) - 1);
		final int visibleRowCount = (int) Math.ceil((double) (y1 - y0) / itemHeight) + 3;
		final int lastRow = Math.min(getItemCount(), firstRow + visibleRowCount);
		for (int j = firstRow; j < lastRow; ++j) {
			final int rowTop = getRowTop(j);
			final int bandTop = rowTop - 2;
			final int bandBottom = bandTop + itemHeight;
			if (bandBottom < y0 || bandTop > y1) {
				continue;
			}

			final WaypointListEntry entry = getEntry(j);
			final boolean hovered = Objects.equals(entry, hoveredEntry);
			if (isSelectedItem(j)) {
				RenderUtils.drawHorizontalGradient(bandLeft, bandTop, bandRight, bandBottom, GuiTheme.ROW_SELECTED_LEFT, GuiTheme.ROW_SELECTED_RIGHT);
				RenderUtils.drawRect(bandLeft, bandTop, bandLeft + 2, bandBottom, GuiTheme.ACCENT | 0xFF000000);
			} else if (hovered) {
				RenderUtils.drawRect(bandLeft, bandTop, bandRight, bandBottom, GuiTheme.ROW_HOVER);
			}

			if (j < getItemCount() - 1) {
				RenderUtils.drawRect(bandLeft + 2, bandBottom - 1, bandRight - 2, bandBottom, GuiTheme.ROW_SEPARATOR);
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

	/** Rebuilds the rows from the screen's list, which is newest first. */
	public void refreshList() {
		final CompassWaypoint selected = getSelected() != null ? getSelected().getWaypoint() : null;
		clearEntries();
		for (CompassWaypoint waypoint : parentScreen.getWaypoints()) {
			addEntry(new WaypointListEntry(this, waypoint));
		}
		// The selection survives a rebuild where the waypoint does, so that acting on a row does not
		// throw away the pick that the action was about
		WaypointListEntry reselected = null;
		for (WaypointListEntry entry : children()) {
			if (entry.getWaypoint() == selected) {
				reselected = entry;
			}
		}
		selectWaypoint(reselected);
	}

	public void selectWaypoint(WaypointListEntry entry) {
		setSelected(entry);
		parentScreen.selectWaypoint(entry);
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
	public WaypointListEntry getHoveredEntry(double mouseX, double mouseY) {
		return isMouseOver(mouseX, mouseY) ? getEntryAtPosition(mouseX, mouseY) : null;
	}

	public WaypointsScreen getParentScreen() {
		return parentScreen;
	}

}
