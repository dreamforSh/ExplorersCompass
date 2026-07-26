package com.chaosthedude.explorerscompass.gui;

import java.util.Objects;

import com.chaosthedude.explorerscompass.util.RenderUtils;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class StructureSearchList extends ObjectSelectionList<StructureSearchEntry> {

	/** How much of the width is left over for the scrollbar and the margins beside the rows. */
	private static final int ROW_INSET = 20;
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
	public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTicks) {
		// A row that is only half inside the list is still drawn whole, so without clipping the rows
		// being scrolled past spill over the search field and the title above the list
		RenderUtils.enableScissor(x0, y0, x1, y1);
		renderList(poseStack, mouseX, mouseY, partialTicks);
		RenderUtils.disableScissor();
	}

	@Override
	protected void renderList(PoseStack poseStack, int mouseX, int mouseY, float partialTicks) {
		final int bandLeft = getRowLeft() - 3;
		final int bandRight = getRowLeft() + getRowWidth() + 3;
		final boolean overList = isMouseOver((double) mouseX, (double) mouseY);

		for (int j = 0; j < getItemCount(); ++j) {
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
			} else if (parentScreen.isMultiSelected(entry.getStructureKey())) {
				// Rows picked with Ctrl-click show fainter, with the same accent along their edge
				RenderUtils.drawRect(bandLeft, bandTop, bandRight, bandBottom, GuiTheme.ROW_MULTI_SELECTED);
				RenderUtils.drawRect(bandLeft, bandTop, bandLeft + 2, bandBottom, GuiTheme.ACCENT_DIM);
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

	public void refreshList() {
		clearEntries();
		for (ResourceLocation key : parentScreen.sortStructures()) {
			addEntry(new StructureSearchEntry(this, key));
		}
		selectStructure(null);
		setScrollAmount(0);
	}

	public void selectStructure(StructureSearchEntry entry) {
		setSelected(entry);
		parentScreen.selectStructure(entry);
	}

	/** Selects the entry for the given key, if the list holds one, and scrolls it into view. */
	public void selectByKey(ResourceLocation key) {
		for (StructureSearchEntry entry : children()) {
			if (entry.getStructureKey().equals(key)) {
				selectStructure(entry);
				centerScrollOn(entry);
				return;
			}
		}
	}

	public boolean hasSelection() {
		return getSelected() != null;
	}

	public ExplorersCompassScreen getParentScreen() {
		return parentScreen;
	}

}
