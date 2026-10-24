package com.chaosthedude.explorerscompass.gui;

import java.util.Objects;

import com.chaosthedude.explorerscompass.client.CompassWaypoint;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class WaypointList extends ObjectSelectionList<WaypointListEntry> {

	/** How much of the width is left over for the scrollbar and the margins beside the rows. */
	private static final int ROW_INSET = 20;
	private static final int SCROLLBAR_WIDTH = 6;

	private final WaypointsScreen parentScreen;
	private final ListChrome chrome = new ListChrome();

	public WaypointList(WaypointsScreen parentScreen, Minecraft mc, int left, int width, int top, int bottom, int slotHeight) {
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

	/** The rows take the right button too, which is what takes a waypoint off the strip and puts it back. */
	@Override
	protected boolean isValidMouseClick(int button) {
		return button == 0 || button == 1;
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
		final WaypointListEntry hoveredEntry = getHoveredEntry(mouseX, mouseY);

		chrome.beginFrame();
		// Drawn before any of the rows, so that it slides under them on its way to the next pick
		chrome.drawSelection(guiGraphics, getSelected() != null ? children().indexOf(getSelected()) : -1, this::getRowTop, itemHeight, bandLeft, bandRight);

		final int firstRow = Math.max(0, (int) Math.floor(getScrollAmount() / itemHeight) - 1);
		final int visibleRowCount = (int) Math.ceil((double) height / itemHeight) + 3;
		final int lastRow = Math.min(getItemCount(), firstRow + visibleRowCount);
		for (int j = firstRow; j < lastRow; ++j) {
			final int rowTop = getRowTop(j);
			final int bandTop = rowTop - 2;
			final int bandBottom = bandTop + itemHeight;
			if (bandBottom < getY() || bandTop > getBottom()) {
				continue;
			}

			final WaypointListEntry entry = getEntry(j);
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

	/** Rebuilds the rows from the screen's list, which is newest first. */
	public void refreshList() {
		final CompassWaypoint selected = getSelected() != null ? getSelected().getWaypoint() : null;
		clearEntries();
		chrome.reset();
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

	/** The row under the pointer, so that the screen can explain it above everything else it draws. */
	public WaypointListEntry getHoveredEntry(double mouseX, double mouseY) {
		return isMouseOver(mouseX, mouseY) ? getEntryAtPosition(mouseX, mouseY) : null;
	}

	public WaypointsScreen getParentScreen() {
		return parentScreen;
	}

}
