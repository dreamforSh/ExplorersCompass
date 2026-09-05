package com.chaosthedude.explorerscompass.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.chaosthedude.explorerscompass.util.RenderUtils;
import com.mojang.blaze3d.vertex.PoseStack;

import org.lwjgl.glfw.GLFW;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.ForgeConfigSpec.ConfigValue;

/**
 * Edits one of the settings that holds a list of names: the structures and biomes the compass is
 * not to offer. Entries are typed into the field along the top and added with return, and taken out
 * again with the cross on their row. Every change is written to the file as it is made.
 */
@OnlyIn(Dist.CLIENT)
public class ConfigListEditScreen extends Screen {

	private static final String REMOVE_GLYPH = "✕";
	private static final int REMOVE_WIDTH = 14;

	private final ConfigScreen parentScreen;
	private final ConfigValue<List<String>> value;
	private final String key;
	private EntryList entryList;
	private TransparentTextField entryField;
	private TransparentButton addButton;
	private TransparentButton removeButton;
	private TransparentButton clearButton;
	private TransparentButton resetButton;
	private TransparentButton backButton;
	/** Where the sidebar has room for the next control. */
	private int sidebarY;

	public ConfigListEditScreen(ConfigScreen parentScreen, ConfigValue<List<String>> value, Component title) {
		super(title);
		this.parentScreen = parentScreen;
		this.value = value;
		key = ConfigTexts.keyOf(value);
	}

	@Override
	protected void init() {
		setupWidgets();
	}

	@Override
	public void tick() {
		entryField.tick();
	}

	@Override
	public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTicks) {
		renderBackground(poseStack);
		GuiTheme.drawHeader(width);
		GuiTheme.drawSidebar(height);
		GuiTheme.drawTitle(poseStack, font, title.getString(), key, GuiTheme.SIDEBAR_CONTENT_X, 10);

		// Whatever there is to say about what the entries may look like, under the field they go into
		final String hintKey = ConfigTexts.PREFIX + key + ".hint";
		final String hint = I18n.exists(hintKey) ? I18n.get(hintKey) : I18n.get(ConfigTexts.PREFIX + "list.hint");
		font.draw(poseStack, RenderUtils.trimToWidth(hint, columnWidth()), columnLeft() + 1, 30, GuiTheme.TEXT_MUTED);

		super.render(poseStack, mouseX, mouseY, partialTicks);
		if (value.get().isEmpty()) {
			drawCenteredString(poseStack, font, Component.translatable(ConfigTexts.PREFIX + "list.empty"), GuiTheme.contentLeft() + GuiTheme.contentWidth(width) / 2, height / 2 - 4, GuiTheme.TEXT_SECONDARY);
		}
		for (Object widget : renderables) {
			if (widget instanceof TransparentButton button && button.visible && button.isPointedAt() && !button.getTooltipLines().isEmpty()) {
				renderComponentTooltip(poseStack, button.getTooltipLines(), mouseX, mouseY);
				return;
			}
		}
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
		return entryList.mouseScrolled(mouseX, mouseY, delta);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) && entryField.isFocused()) {
			addEntry();
			return true;
		}
		if (keyCode == GLFW.GLFW_KEY_DELETE && entryList.getSelected() != null && !entryField.isFocused()) {
			remove(entryList.getSelected().entry);
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	/** Gives the field the focus back once whatever was clicked has been let go of, so typing always lands in it. */
	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		final boolean handled = super.mouseReleased(mouseX, mouseY, button);
		if (!entryField.isFocused()) {
			setFocused(entryField);
			// Being made the screen's focus does not reach the field itself in this version
			entryField.setFocused(true);
		}
		return handled;
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parentScreen);
	}

	/** Adds whatever is in the field, unless it is empty or already listed. */
	private void addEntry() {
		final String entry = entryField.getValue().trim();
		if (entry.isEmpty()) {
			return;
		}
		final List<String> entries = new ArrayList<String>(value.get());
		if (!entries.contains(entry)) {
			entries.add(entry);
			apply(entries);
		}
		entryField.setValue("");
	}

	private void remove(String entry) {
		final List<String> entries = new ArrayList<String>(value.get());
		if (entries.remove(entry)) {
			apply(entries);
		}
	}

	private void apply(List<String> entries) {
		value.set(entries);
		value.save();
		entryList.refreshList();
		updateButtons();
	}

	private int columnLeft() {
		return GuiTheme.contentLeft() + ConfigOptionList.ROW_INSET / 2;
	}

	private int columnWidth() {
		return GuiTheme.contentWidth(width) - ConfigOptionList.ROW_INSET;
	}

	private void setupWidgets() {
		final String previousEntry = entryField != null ? entryField.getValue() : "";
		clearWidgets();
		sidebarY = GuiTheme.HEADER_HEIGHT + 8;

		addButton = addSidebarButton(Component.translatable(ConfigTexts.PREFIX + "list.add"), (onPress) -> {
			addEntry();
		});
		addButton.setTooltipLines(Component.translatable(ConfigTexts.PREFIX + "list.add.tooltip"));
		removeButton = addSidebarButton(Component.translatable("string.explorerscompass.remove"), (onPress) -> {
			if (entryList.getSelected() != null) {
				remove(entryList.getSelected().entry);
			}
		});
		removeButton.setTooltipLines(Component.translatable(ConfigTexts.PREFIX + "list.remove.tooltip"));
		clearButton = addSidebarButton(Component.translatable("string.explorerscompass.clearAll"), (onPress) -> {
			apply(new ArrayList<String>());
		});
		clearButton.setTooltipLines(Component.translatable(ConfigTexts.PREFIX + "list.clear.tooltip"), Component.translatable("string.explorerscompass.tooltip.clearAll.warning").withStyle(ChatFormatting.RED));
		resetButton = addSidebarButton(Component.translatable(ConfigTexts.PREFIX + "resetTab"), (onPress) -> {
			apply(new ArrayList<String>(value.getDefault()));
		});
		resetButton.setTooltipLines(Component.translatable(ConfigTexts.PREFIX + "reset", ConfigTexts.format(value.getDefault())));

		backButton = addRenderableWidget(new TransparentButton(GuiTheme.SIDEBAR_CONTENT_X, height - 26, GuiTheme.SIDEBAR_CONTENT_WIDTH, GuiTheme.BUTTON_HEIGHT, Component.translatable("string.explorerscompass.back"), (onPress) -> {
			onClose();
		}));
		backButton.setTooltipLines(Component.translatable(ConfigTexts.PREFIX + "list.back.tooltip"));

		entryField = new TransparentTextField(font, columnLeft(), 8, columnWidth(), 18, Component.translatable(ConfigTexts.PREFIX + "list.newEntry"));
		entryField.setMaxLength(256);
		entryField.setValue(previousEntry);
		addRenderableWidget(entryField);

		entryList = new EntryList(minecraft, GuiTheme.contentLeft(), GuiTheme.contentWidth(width), height, GuiTheme.HEADER_HEIGHT + 2, height - 10);
		addRenderableWidget(entryList);
		setInitialFocus(entryField);
		entryField.setFocused(true);
		updateButtons();
	}

	private TransparentButton addSidebarButton(Component label, Button.OnPress onPress) {
		final TransparentButton button = addRenderableWidget(new TransparentButton(GuiTheme.SIDEBAR_CONTENT_X, sidebarY, GuiTheme.SIDEBAR_CONTENT_WIDTH, GuiTheme.BUTTON_HEIGHT, label, onPress));
		sidebarY += GuiTheme.BUTTON_SPACING;
		return button;
	}

	private void updateButtons() {
		removeButton.active = entryList != null && entryList.getSelected() != null;
		clearButton.active = !value.get().isEmpty();
		resetButton.active = !Objects.equals(value.get(), value.getDefault());
	}

	/** The entries, one to a row, each with a cross that takes it out. */
	@OnlyIn(Dist.CLIENT)
	private class EntryList extends ObjectSelectionList<EntryRow> {

		private static final int SCROLLBAR_WIDTH = 6;

		EntryList(Minecraft mc, int left, int width, int height, int top, int bottom) {
			super(mc, width, height, top, bottom, 16);
			setLeftPos(left);
			refreshList();
		}

		@Override
		protected int getScrollbarPosition() {
			return x1 - SCROLLBAR_WIDTH;
		}

		@Override
		public int getRowWidth() {
			return width - ConfigOptionList.ROW_INSET;
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
			RenderUtils.enableScissor(x0, y0, x1, y1);
			renderList(poseStack, mouseX, mouseY, partialTicks);
			RenderUtils.disableScissor();
		}

		@Override
		protected void renderList(PoseStack poseStack, int mouseX, int mouseY, float partialTicks) {
			final int bandLeft = getRowLeft() - 3;
			final int bandRight = getRowLeft() + getRowWidth() + 3;
			final EntryRow hoveredEntry = isMouseOver(mouseX, mouseY) ? getEntryAtPosition(mouseX, mouseY) : null;
			for (int j = 0; j < getItemCount(); j++) {
				final int rowTop = getRowTop(j);
				final int bandTop = rowTop - 2;
				final int bandBottom = bandTop + itemHeight;
				if (bandBottom < y0 || bandTop > y1) {
					continue;
				}
				final EntryRow row = getEntry(j);
				final boolean hovered = Objects.equals(row, hoveredEntry);
				if (isSelectedItem(j)) {
					RenderUtils.drawHorizontalGradient(bandLeft, bandTop, bandRight, bandBottom, GuiTheme.ROW_SELECTED_LEFT, GuiTheme.ROW_SELECTED_RIGHT);
					RenderUtils.drawRect(bandLeft, bandTop, bandLeft + 2, bandBottom, GuiTheme.ACCENT | 0xFF000000);
				} else if (hovered) {
					RenderUtils.drawRect(bandLeft, bandTop, bandRight, bandBottom, GuiTheme.ROW_HOVER);
				}
				row.render(poseStack, j, rowTop, getRowLeft(), getRowWidth(), itemHeight - 4, mouseX, mouseY, hovered, partialTicks);
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

		void refreshList() {
			final String selected = getSelected() != null ? getSelected().entry : null;
			clearEntries();
			for (String entry : value.get()) {
				addEntry(new EntryRow(entry));
			}
			for (EntryRow row : children()) {
				if (row.entry.equals(selected)) {
					setSelected(row);
				}
			}
		}

	}

	/** One name in the list. */
	@OnlyIn(Dist.CLIENT)
	private class EntryRow extends ObjectSelectionList.Entry<EntryRow> {

		private final String entry;
		private int removeLeft;
		private int removeTop;

		EntryRow(String entry) {
			this.entry = entry;
		}

		@Override
		public void render(PoseStack poseStack, int index, int top, int left, int width, int height, int mouseX, int mouseY, boolean hovered, float partialTicks) {
			removeLeft = left + width - REMOVE_WIDTH;
			removeTop = top - 2;
			font.draw(poseStack, RenderUtils.trimToWidth(entry, width - REMOVE_WIDTH - 8), left + 4, top + 2, GuiTheme.TEXT_PRIMARY);
			// The cross shows on the row the pointer is over, and lights up when it is over the cross itself
			if (hovered || Objects.equals(entryList.getSelected(), this)) {
				font.draw(poseStack, REMOVE_GLYPH, removeLeft + 3, top + 2, isOverRemove(mouseX, mouseY) ? GuiTheme.TEXT_WARNING : GuiTheme.TEXT_MUTED);
			}
		}

		private boolean isOverRemove(double mouseX, double mouseY) {
			return mouseX >= removeLeft && mouseX < removeLeft + REMOVE_WIDTH && mouseY >= removeTop && mouseY < removeTop + 16;
		}

		@Override
		public boolean mouseClicked(double mouseX, double mouseY, int button) {
			if (button != 0) {
				return false;
			}
			if (isOverRemove(mouseX, mouseY)) {
				remove(entry);
				return true;
			}
			entryList.setSelected(this);
			updateButtons();
			return true;
		}

		@Override
		public Component getNarration() {
			return Component.literal(entry);
		}

	}

}
