package com.chaosthedude.explorerscompass.gui;

import java.util.ArrayList;
import java.util.List;

import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.chaosthedude.explorerscompass.util.RenderUtils;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.ForgeConfigSpec.ConfigValue;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * Every setting of the mod, on a screen of its own, so that none of them has to be changed by
 * finding and editing a file. The two files are two tabs: this computer's settings, and the settings
 * a server goes by. Each setting is changed in place and written out as it is changed.
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

		ForgeConfigSpec spec() {
			return this == CLIENT ? ConfigHandler.CLIENT_SPEC : ConfigHandler.GENERAL_SPEC;
		}

		List<ConfigHandler.Group> groups() {
			return this == CLIENT ? ConfigHandler.clientGroups() : ConfigHandler.generalGroups();
		}

		String fileName() {
			return ConfigTexts.fileName(type);
		}
	}

	private final Screen parentScreen;
	private Tab tab;
	private ConfigOptionList optionList;
	private TransparentTextField filterField;
	private TransparentButton clientTabButton;
	private TransparentButton serverTabButton;
	private TransparentButton resetButton;
	private TransparentButton folderButton;
	private TransparentButton backButton;
	/** Where the sidebar has room for the next control. */
	private int sidebarY;

	public ConfigScreen(Screen parentScreen) {
		this(parentScreen, Tab.CLIENT);
	}

	public ConfigScreen(Screen parentScreen, Tab tab) {
		super(Component.translatable(ConfigTexts.PREFIX + "title"));
		this.parentScreen = parentScreen;
		this.tab = tab;
	}

	/** What the mod list opens when its configure button is pressed: this screen, over the mod list. */
	public static ConfigScreenHandler.ConfigScreenFactory modListFactory() {
		return new ConfigScreenHandler.ConfigScreenFactory((mc, parent) -> new ConfigScreen(parent));
	}

	@Override
	protected void init() {
		setupWidgets();
	}

	/** The fields blink their cursors on the tick, which a screen has to hand on to them itself here. */
	@Override
	public void tick() {
		filterField.tick();
		optionList.tick();
	}

	@Override
	public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTicks) {
		renderBackground(poseStack);
		GuiTheme.drawHeader(width);
		GuiTheme.drawSidebar(height);
		GuiTheme.drawTitle(poseStack, font, title.getString(), tab.fileName(), GuiTheme.SIDEBAR_CONTENT_X, 10);

		// Under the filter, a line on how the screen works, or on whose settings the server tab shows
		final String hint = tab == Tab.SERVER ? serverHint() : I18n.get(ConfigTexts.PREFIX + "hint");
		font.draw(poseStack, RenderUtils.trimToWidth(hint, columnWidth()), columnLeft() + 1, 30, tab == Tab.SERVER && minecraft.level != null && !minecraft.isLocalServer() ? GuiTheme.TEXT_WARNING : GuiTheme.TEXT_MUTED);

		super.render(poseStack, mouseX, mouseY, partialTicks);

		if (optionList.children().isEmpty()) {
			drawCenteredString(poseStack, font, Component.translatable(ConfigTexts.PREFIX + "noMatches"), GuiTheme.contentLeft() + GuiTheme.contentWidth(width) / 2, height / 2 - 4, GuiTheme.TEXT_SECONDARY);
		}
		if (!tab.spec().isLoaded()) {
			drawCenteredString(poseStack, font, Component.translatable(ConfigTexts.PREFIX + "notLoaded"), GuiTheme.contentLeft() + GuiTheme.contentWidth(width) / 2, height / 2 - 4, GuiTheme.TEXT_WARNING);
		}
		renderTooltips(poseStack, mouseX, mouseY);
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

	/** Explains whatever the pointer is resting on, above everything else on the screen. */
	private void renderTooltips(PoseStack poseStack, int mouseX, int mouseY) {
		for (Object widget : renderables) {
			if (widget instanceof TransparentButton button && button.visible && button.isPointedAt() && !button.getTooltipLines().isEmpty()) {
				renderComponentTooltip(poseStack, button.getTooltipLines(), mouseX, mouseY);
				return;
			}
		}
		final ConfigOptionList.Row row = optionList.getHoveredRow(mouseX, mouseY);
		if (row != null) {
			final List<Component> lines = row.getTooltipLines(mouseX, mouseY);
			if (lines != null && !lines.isEmpty()) {
				renderComponentTooltip(poseStack, wrapped(lines), mouseX, mouseY);
			}
		}
	}

	/**
	 * A description can run to a paragraph, and a tooltip is drawn one line to a component, so the
	 * long ones are split at a readable width.
	 */
	private List<Component> wrapped(List<Component> lines) {
		final int maxWidth = Math.max(160, Math.min(300, width / 2));
		final List<Component> out = new ArrayList<Component>();
		for (Component line : lines) {
			if (font.width(line) <= maxWidth) {
				out.add(line);
				continue;
			}
			for (FormattedText part : font.getSplitter().splitLines(line.getString(), maxWidth, line.getStyle())) {
				out.add(Component.literal(part.getString()).withStyle(line.getStyle()));
			}
		}
		return out;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
		return optionList.mouseScrolled(mouseX, mouseY, delta) || super.mouseScrolled(mouseX, mouseY, delta);
	}

	/**
	 * Gives the filter the focus back once whatever was clicked has been let go of, unless the click
	 * landed on a field of the list, which wants the keys itself for as long as it is being typed in.
	 */
	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		final boolean handled = super.mouseReleased(mouseX, mouseY, button);
		if (!optionList.isMouseOver(mouseX, mouseY) && !filterField.isFocused()) {
			optionList.clearFocus();
			setFocused(filterField);
			// Being made the screen's focus does not reach the field itself in this version
			filterField.setFocused(true);
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
		clientTabButton.setHighlighted(tab == Tab.CLIENT);
		serverTabButton.setHighlighted(tab == Tab.SERVER);
		rebuildList();
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

	private void rebuildList() {
		optionList.rebuild(tab.spec().isLoaded() ? tab.groups() : List.of(), filterField.getValue());
		resetButton.active = tab.spec().isLoaded();
	}

	private int columnLeft() {
		return GuiTheme.contentLeft() + ConfigOptionList.ROW_INSET / 2;
	}

	private int columnWidth() {
		return GuiTheme.contentWidth(width) - ConfigOptionList.ROW_INSET;
	}

	private void setupWidgets() {
		final String previousFilter = filterField != null ? filterField.getValue() : "";
		clearWidgets();
		sidebarY = GuiTheme.HEADER_HEIGHT + 8;

		clientTabButton = addSidebarButton(Component.translatable(ConfigTexts.PREFIX + "tab.client"), (onPress) -> {
			switchTab(Tab.CLIENT);
		});
		clientTabButton.setHighlighted(tab == Tab.CLIENT);
		clientTabButton.setTooltipLines(Component.translatable(ConfigTexts.PREFIX + "tab.client.tooltip"));
		serverTabButton = addSidebarButton(Component.translatable(ConfigTexts.PREFIX + "tab.server"), (onPress) -> {
			switchTab(Tab.SERVER);
		});
		serverTabButton.setHighlighted(tab == Tab.SERVER);
		serverTabButton.setTooltipLines(Component.translatable(ConfigTexts.PREFIX + "tab.server.tooltip"));

		// A gap between choosing what is shown and acting on all of it
		sidebarY += GuiTheme.BUTTON_SPACING / 2;

		resetButton = addSidebarButton(Component.translatable(ConfigTexts.PREFIX + "resetTab"), (onPress) -> {
			optionList.resetAll();
		});
		resetButton.setTooltipLines(Component.translatable(ConfigTexts.PREFIX + "resetTab.tooltip"), Component.translatable("string.explorerscompass.tooltip.clearAll.warning").withStyle(ChatFormatting.RED));
		folderButton = addSidebarButton(Component.translatable(ConfigTexts.PREFIX + "openFolder"), (onPress) -> {
			Util.getPlatform().openFile(FMLPaths.CONFIGDIR.get().toFile());
		});
		folderButton.setTooltipLines(Component.translatable(ConfigTexts.PREFIX + "openFolder.tooltip"));

		backButton = addRenderableWidget(new TransparentButton(GuiTheme.SIDEBAR_CONTENT_X, height - 26, GuiTheme.SIDEBAR_CONTENT_WIDTH, GuiTheme.BUTTON_HEIGHT, Component.translatable("string.explorerscompass.back"), (onPress) -> {
			onClose();
		}));
		backButton.setTooltipLines(Component.translatable(ConfigTexts.PREFIX + "back.tooltip"));

		// The filter narrows the rows as it is typed in, the way the compass screen's own does
		filterField = new TransparentTextField(font, columnLeft(), 8, columnWidth(), 18, Component.translatable(ConfigTexts.PREFIX + "filterHint"));
		filterField.setMaxLength(64);
		filterField.setValue(previousFilter);
		filterField.setResponder((text) -> rebuildList());
		addRenderableWidget(filterField);

		// Recreated on every init so that it picks up the current screen dimensions
		optionList = new ConfigOptionList(this, minecraft, GuiTheme.contentLeft(), GuiTheme.contentWidth(width), height, GuiTheme.HEADER_HEIGHT + 2, height - 10);
		addRenderableWidget(optionList);
		rebuildList();

		setInitialFocus(filterField);
		filterField.setFocused(true);
	}

	/** Adds a control to the bottom of the sidebar column, and moves the column down past it. */
	private TransparentButton addSidebarButton(Component label, Button.OnPress onPress) {
		final TransparentButton button = addRenderableWidget(new TransparentButton(GuiTheme.SIDEBAR_CONTENT_X, sidebarY, GuiTheme.SIDEBAR_CONTENT_WIDTH, GuiTheme.BUTTON_HEIGHT, label, onPress));
		sidebarY += GuiTheme.BUTTON_SPACING;
		return button;
	}

}
