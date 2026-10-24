package com.chaosthedude.explorerscompass.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.chaosthedude.explorerscompass.util.RenderUtils;

import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.ModConfigSpec.ConfigValue;

/**
 * Every setting of the mod, on a screen of its own, so that none of them has to be changed by
 * finding and editing a file. The two files are two tabs at the top of the sidebar: this computer's
 * settings, and the settings a server goes by. Under them the sidebar lists the groups the file's
 * settings fall into, one of which is shown at a time, with a dot beside any group holding a setting
 * that is not at its default; typing in the filter gathers the matching settings from every group at
 * once instead. Each setting is changed in place and written out as it is changed.
 *
 * <p>Reached from the gear on the compass screen and from the mod list's configure button alike.
 */
@OnlyIn(Dist.CLIENT)
public class ConfigScreen extends Screen {

	/** The two files, as the two tabs of the screen. */
	public enum Tab {
		CLIENT(ModConfig.Type.CLIENT),
		SERVER(ModConfig.Type.COMMON);

		private final ModConfig.Type type;

		private Tab(ModConfig.Type type) {
			this.type = type;
		}

		ModConfigSpec spec() {
			return this == CLIENT ? ConfigHandler.CLIENT_SPEC : ConfigHandler.GENERAL_SPEC;
		}

		List<ConfigHandler.Group> groups() {
			return this == CLIENT ? ConfigHandler.clientGroups() : ConfigHandler.generalGroups();
		}

		String fileName() {
			return ConfigTexts.fileName(type);
		}

		String translationKey() {
			return ConfigTexts.PREFIX + (this == CLIENT ? "tab.client" : "tab.server");
		}
	}

	private static final String RESET_GLYPH = "↺";
	private static final int GLYPH_BUTTON_WIDTH = 24;
	private static final int GLYPH_BUTTON_GAP = 4;

	private final Screen parentScreen;
	private Tab tab;
	/** Which group of the tab is shown while nothing is typed in the filter. */
	private int groupIndex;
	private ConfigOptionList optionList;
	private TransparentTextField filterField;
	private NavList tabList;
	private NavList groupList;
	private TransparentButton resetButton;
	private TransparentButton folderButton;
	private TransparentButton backButton;
	/** Where the column of groups starts and how far down it may run. */
	private int groupListTop;
	private int groupListBottom;

	public ConfigScreen(Screen parentScreen) {
		this(parentScreen, Tab.CLIENT);
	}

	public ConfigScreen(Screen parentScreen, Tab tab) {
		super(Component.translatable(ConfigTexts.PREFIX + "title"));
		this.parentScreen = parentScreen;
		this.tab = tab;
	}

	@Override
	protected void init() {
		setupWidgets();
	}

	@Override
	public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		renderTransparentBackground(guiGraphics);
		GuiTheme.drawHeader(guiGraphics, width);
		GuiTheme.drawSidebar(guiGraphics, height);
		GuiTheme.drawTitle(guiGraphics, font, title.getString(), tab.fileName(), GuiTheme.SIDEBAR_CONTENT_X, 10);
		// Sets the tabs, which choose the file, apart from the groups of the file they chose
		UiShapes.hairline(guiGraphics, GuiTheme.SIDEBAR_CONTENT_X + 2, GuiTheme.SIDEBAR_CONTENT_X + GuiTheme.SIDEBAR_CONTENT_WIDTH - 2, groupListTop - 5, GuiTheme.PANEL_BORDER);

		// Under the filter, a line on how the screen works, or on whose settings the server tab shows
		final String hint = tab == Tab.SERVER ? serverHint() : I18n.get(ConfigTexts.PREFIX + "hint");
		guiGraphics.drawString(font, RenderUtils.trimToWidth(hint, columnWidth()), columnLeft() + 1, 30, tab == Tab.SERVER && minecraft.level != null && !minecraft.isLocalServer() ? GuiTheme.TEXT_WARNING : GuiTheme.TEXT_MUTED, false);
	}

	/**
	 * Whose settings the server tab is showing. The file is this computer's either way; what differs
	 * is whether a server is going by it right now.
	 */
	private String serverHint() {
		if (minecraft.level != null && !minecraft.isLocalServer()) {
			return I18n.get(ConfigTexts.PREFIX + "serverHint.remote");
		}
		return I18n.get(ConfigTexts.PREFIX + "serverHint.local");
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		super.render(guiGraphics, mouseX, mouseY, partialTicks);

		final int centerX = columnLeft() + columnWidth() / 2;
		if (!tab.spec().isLoaded()) {
			guiGraphics.drawCenteredString(font, Component.translatable(ConfigTexts.PREFIX + "notLoaded"), centerX, height / 2 - 4, GuiTheme.TEXT_WARNING);
		} else if (optionList.children().isEmpty()) {
			guiGraphics.drawCenteredString(font, Component.translatable(ConfigTexts.PREFIX + "noMatches"), centerX, height / 2 - 4, GuiTheme.TEXT_SECONDARY);
		}
		renderTooltips(guiGraphics, mouseX, mouseY);
	}

	/** Explains whatever the pointer is resting on, above everything else on the screen. */
	private void renderTooltips(GuiGraphics guiGraphics, int mouseX, int mouseY) {
		for (Object widget : renderables) {
			if (widget instanceof TransparentButton button && button.visible && button.isPointedAt() && !button.getTooltipLines().isEmpty()) {
				ThemedTooltip.render(guiGraphics, font, button, mouseX, mouseY);
				return;
			}
		}
		final int hoveredTab = tabList.entryAt(mouseX, mouseY);
		if (hoveredTab >= 0) {
			final Tab pointed = Tab.values()[hoveredTab];
			ThemedTooltip.render(guiGraphics, font, List.of(Component.translatable(pointed.translationKey()), Component.translatable(pointed.translationKey() + ".tooltip")), mouseX, mouseY);
			return;
		}
		final ConfigOptionList.Row row = optionList.getHoveredRow(mouseX, mouseY);
		if (row != null) {
			final List<Component> lines = row.getTooltipLines(mouseX, mouseY);
			if (lines != null && !lines.isEmpty()) {
				ThemedTooltip.render(guiGraphics, font, lines, mouseX, mouseY);
			}
		}
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (groupList.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) {
			return true;
		}
		return optionList.mouseScrolled(mouseX, mouseY, scrollX, scrollY) || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	/**
	 * Gives the filter the focus back once whatever was clicked has been let go of, unless the click
	 * landed on a field of the list, which wants the keys itself for as long as it is being typed in.
	 */
	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		final boolean handled = super.mouseReleased(mouseX, mouseY, button);
		if (!optionList.isMouseOver(mouseX, mouseY) && !filterField.isFocused()) {
			setFocused(filterField);
		}
		return handled;
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parentScreen);
	}

	/** Which of the two files the screen is showing. */
	public Tab getTab() {
		return tab;
	}

	private void switchTab(Tab newTab) {
		if (tab == newTab) {
			return;
		}
		tab = newTab;
		groupIndex = 0;
		tabList.setSelected(tab.ordinal());
		// The other file falls into other groups, so the column of them is laid out again
		removeWidget(groupList);
		groupList = addRenderableWidget(createGroupList());
		rebuildList();
		optionList.setScrollAmount(0);
	}

	/** Shows one group of the tab, leaving the filter if one was gathering settings from all of them. */
	private void selectGroup(int index) {
		groupIndex = index;
		if (!filterField.getValue().isEmpty()) {
			// Emptying the filter rebuilds the list on its own
			filterField.setValue("");
		} else {
			rebuildList();
		}
		optionList.setScrollAmount(0);
	}

	/** Whether any setting in the given group of the tab is not at its default, which its entry in the sidebar marks. */
	private boolean isGroupModified(int index) {
		final List<ConfigHandler.Group> groups = tab.groups();
		if (!tab.spec().isLoaded() || index < 0 || index >= groups.size()) {
			return false;
		}
		for (ConfigValue<?> value : groups.get(index).values()) {
			if (!Objects.equals(value.get(), value.getDefault())) {
				return true;
			}
		}
		return false;
	}

	/** Writes the file of the tab being shown, which is what every change on it ends in. */
	void saveCurrentSpec() {
		if (tab.spec().isLoaded()) {
			tab.spec().save();
		}
	}

	/** Opens the list held by a setting on a screen of its own, since a row has no room to edit one. */
	@SuppressWarnings("unchecked")
	void openListEditor(ConfigValue<Object> value, Component label) {
		minecraft.setScreen(new ConfigListEditScreen(this, (ConfigValue<List<String>>) (ConfigValue<?>) value, label));
	}

	/**
	 * Lays the rows out again: the group picked in the sidebar, or, while anything is typed in the
	 * filter, whatever in any group matches it, under the heading of the group it belongs to.
	 */
	private void rebuildList() {
		final List<ConfigHandler.Group> groups = tab.spec().isLoaded() ? tab.groups() : List.of();
		final boolean filtering = !filterField.getValue().isBlank();
		groupIndex = Mth.clamp(groupIndex, 0, Math.max(0, groups.size() - 1));
		groupList.setSelected(filtering ? -1 : groupIndex);
		final List<ConfigHandler.Group> shown = filtering || groups.isEmpty() ? groups : List.of(groups.get(groupIndex));
		optionList.rebuild(shown, filterField.getValue(), filtering);
		resetButton.active = tab.spec().isLoaded();
	}

	private int columnLeft() {
		return GuiTheme.contentLeft() + ConfigOptionList.ROW_INSET / 2;
	}

	/** As wide as the rows under it, which stop short of the edge on a wide window. */
	private int columnWidth() {
		return Math.min(GuiTheme.contentWidth(width) - ConfigOptionList.ROW_INSET, ConfigOptionList.MAX_ROW_WIDTH);
	}

	private NavList createGroupList() {
		final List<Component> labels = new ArrayList<Component>();
		for (ConfigHandler.Group group : tab.groups()) {
			labels.add(ConfigTexts.groupLabel(group.key()));
		}
		return new NavList(GuiTheme.SIDEBAR_CONTENT_X, groupListTop, GuiTheme.SIDEBAR_CONTENT_WIDTH, Math.max(NavList.ROW_HEIGHT, groupListBottom - groupListTop), title, labels, false, groupIndex, this::isGroupModified, this::selectGroup);
	}

	private void setupWidgets() {
		final String previousFilter = filterField != null ? filterField.getValue() : "";
		clearWidgets();
		int sidebarY = GuiTheme.HEADER_HEIGHT + 8;

		final List<Component> tabLabels = new ArrayList<Component>();
		for (Tab each : Tab.values()) {
			tabLabels.add(Component.translatable(each.translationKey()));
		}
		tabList = addRenderableWidget(new NavList(GuiTheme.SIDEBAR_CONTENT_X, sidebarY, GuiTheme.SIDEBAR_CONTENT_WIDTH, NavList.tabsHeight(tabLabels.size()), title, tabLabels, true, tab.ordinal(), (index) -> false, (index) -> switchTab(Tab.values()[index])));
		sidebarY += NavList.tabsHeight(tabLabels.size()) + 10;

		folderButton = addRenderableWidget(new TransparentButton(GuiTheme.SIDEBAR_CONTENT_X, height - 26 - GuiTheme.BUTTON_SPACING - 2, GuiTheme.SIDEBAR_CONTENT_WIDTH, GuiTheme.BUTTON_HEIGHT, Component.translatable(ConfigTexts.PREFIX + "openFolder"), (onPress) -> {
			Util.getPlatform().openPath(FMLPaths.CONFIGDIR.get());
		}));
		folderButton.setTooltipLines(Component.translatable(ConfigTexts.PREFIX + "openFolder.tooltip"));
		backButton = addRenderableWidget(new TransparentButton(GuiTheme.SIDEBAR_CONTENT_X, height - 26, GuiTheme.SIDEBAR_CONTENT_WIDTH, GuiTheme.BUTTON_HEIGHT, Component.translatable("string.explorerscompass.back"), (onPress) -> {
			onClose();
		}));
		backButton.setTooltipLines(Component.translatable(ConfigTexts.PREFIX + "back.tooltip"));

		// The groups take whatever the sidebar has left between the tabs and the buttons at its foot
		groupListTop = sidebarY;
		groupListBottom = folderButton.getY() - 6;
		groupList = addRenderableWidget(createGroupList());

		// The filter narrows the rows as it is typed in, the way the compass screen's own does, and the
		// control that puts every setting shown back to its default stands at its far end
		final int fieldWidth = columnWidth() - GLYPH_BUTTON_WIDTH - GLYPH_BUTTON_GAP;
		resetButton = addRenderableWidget(new TransparentButton(columnLeft() + fieldWidth + GLYPH_BUTTON_GAP, 8, GLYPH_BUTTON_WIDTH, 18, Component.literal(RESET_GLYPH), (onPress) -> {
			optionList.resetAll();
		}));
		resetButton.setDanger(true);
		resetButton.setTooltipTitle(Component.translatable(ConfigTexts.PREFIX + "resetTab"));
		resetButton.setTooltipLines(Component.translatable(ConfigTexts.PREFIX + "resetTab.tooltip"), Component.translatable("string.explorerscompass.tooltip.clearAll.warning").withStyle(ChatFormatting.RED));

		filterField = new TransparentTextField(font, columnLeft(), 8, fieldWidth, 18, Component.translatable(ConfigTexts.PREFIX + "filterHint"));
		filterField.setMaxLength(64);
		filterField.setValue(previousFilter);
		filterField.setResponder((text) -> rebuildList());
		addRenderableWidget(filterField);

		// Recreated on every init so that it picks up the current screen dimensions, and
		// held to the width of the column above it, and laid against its left edge
		optionList = new ConfigOptionList(this, minecraft, GuiTheme.contentLeft(), columnWidth() + ConfigOptionList.ROW_INSET, GuiTheme.HEADER_HEIGHT + 2, height - 10);
		addRenderableWidget(optionList);
		rebuildList();

		setInitialFocus(filterField);
	}

}
