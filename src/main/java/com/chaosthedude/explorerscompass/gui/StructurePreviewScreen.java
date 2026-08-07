package com.chaosthedude.explorerscompass.gui;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.glfw.GLFW;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.client.SearchHistory;
import com.chaosthedude.explorerscompass.client.StructurePreviewCache;
import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.chaosthedude.explorerscompass.network.CompassSearchPacket;
import com.chaosthedude.explorerscompass.preview.StructurePreview;
import com.chaosthedude.explorerscompass.util.RenderUtils;
import com.chaosthedude.explorerscompass.util.SearchTarget;
import com.chaosthedude.explorerscompass.util.StructureUtils;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Shows what a structure looks like before a search is spent on finding one.
 *
 * <p>What is drawn is assembled by the server the way world generation would assemble it, so this
 * screen only ever waits for it, draws it, and says what is worth knowing about what it is showing:
 * how large the structure is, how much of it one cell of the model stands for, and whether any of
 * its pieces could only be outlined rather than read block by block.
 */
@OnlyIn(Dist.CLIENT)
public class StructurePreviewScreen extends Screen {

	/** The strip along the bottom that says what is being shown. */
	private static final int INFO_BAR_HEIGHT = 34;

	private final Screen parentScreen;
	private final ResourceLocation structureKey;
	private final StructurePreviewView view = new StructurePreviewView();
	private TransparentButton searchButton;
	private TransparentButton spinButton;
	private TransparentButton resetButton;
	private TransparentButton backButton;
	/** Where the sidebar has room for the next control. */
	private int sidebarY;

	public StructurePreviewScreen(Screen parentScreen, ResourceLocation structureKey) {
		super(Component.literal(StructureUtils.getPrettyStructureName(structureKey)));
		this.parentScreen = parentScreen;
		this.structureKey = structureKey;
	}

	@Override
	protected void init() {
		StructurePreviewCache.request(structureKey);
		setupWidgets();
	}

	@Override
	public void tick() {
		// Asked for again on every tick rather than once: a request that arrived while the server was
		// still answering the last one is dropped, and this is what notices
		StructurePreviewCache.request(structureKey);
	}

	@Override
	public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTicks) {
		renderBackground(poseStack);
		GuiTheme.drawHeader(width);
		GuiTheme.drawSidebar(height);
		GuiTheme.drawTitle(poseStack, font, title.getString(), structureKey.toString(), GuiTheme.SIDEBAR_CONTENT_X, 10);

		final int left = GuiTheme.contentLeft();
		final int right = left + GuiTheme.contentWidth(width);
		final int top = GuiTheme.HEADER_HEIGHT + 6;
		final int bottom = infoBarTop() - 4;
		GuiTheme.drawScreenPanel(left, top, right, bottom, ConfigHandler.CLIENT.guiSidebarBackground.get());

		final StructurePreview preview = StructurePreviewCache.get(structureKey);
		if (preview != null && preview.getCellCount() > 0) {
			// Inside the panel border, so that the model is never drawn over its own edges
			view.render(poseStack, preview, left + 1, top + 1, right - 1, bottom - 1);
		} else {
			// A preview that arrived holding nothing has as little to draw as one that never arrived
			drawWaitingMessage(poseStack, left, top, right, bottom);
		}

		renderInfoBar(poseStack, preview, left, right);

		super.render(poseStack, mouseX, mouseY, partialTicks);
		renderButtonTooltip(poseStack, mouseX, mouseY);
	}

	/** Says whether the preview is still coming or is not going to, in place of the model. */
	private void drawWaitingMessage(PoseStack poseStack, int left, int top, int right, int bottom) {
		final int centerX = (left + right) / 2;
		final int centerY = (top + bottom) / 2;
		if (StructurePreviewCache.stateOf(structureKey) == StructurePreviewCache.State.PENDING) {
			drawCenteredString(poseStack, font, Component.translatable("string.explorerscompass.previewLoading"), centerX, centerY - 4, GuiTheme.TEXT_SECONDARY);
			return;
		}
		drawCenteredString(poseStack, font, Component.translatable("string.explorerscompass.previewUnavailable"), centerX, centerY - 10, GuiTheme.TEXT_SECONDARY);
		drawCenteredString(poseStack, font, Component.translatable("string.explorerscompass.previewUnavailableHint"), centerX, centerY + 2, GuiTheme.TEXT_MUTED);
	}

	/** What is being shown, and how it is turned around, along the bottom. */
	private void renderInfoBar(PoseStack poseStack, StructurePreview preview, int left, int right) {
		final int top = infoBarTop();
		final int textLeft = left + 6;
		final int available = right - 6 - textLeft;
		GuiTheme.drawScreenPanel(left, top, right, top + INFO_BAR_HEIGHT, ConfigHandler.CLIENT.guiStatusBarBackground.get());

		// The second line is the one thing that is worth saying whether or not a preview has arrived
		font.draw(poseStack, RenderUtils.trimToWidth(I18n.get("string.explorerscompass.previewHint"), available), textLeft, top + 19, GuiTheme.TEXT_MUTED);

		if (preview == null) {
			return;
		}

		// Whatever there is to warn about goes against the right edge, and the readings take whatever
		// width that leaves, so that neither of them is ever drawn over the other
		int readingsWidth = available;
		final String note = note(preview);
		if (!note.isEmpty()) {
			final String trimmed = RenderUtils.trimToWidth(note, available / 2);
			font.draw(poseStack, trimmed, right - 6 - font.width(trimmed), top + 6, GuiTheme.TEXT_WARNING);
			readingsWidth -= font.width(trimmed) + 8;
		}

		final List<String> parts = new ArrayList<String>();
		parts.add(labeled("string.explorerscompass.previewSize", preview.getBlockX() + " × " + preview.getBlockY() + " × " + preview.getBlockZ()));
		parts.add(labeled("string.explorerscompass.previewPieces", String.valueOf(preview.getPieces())));
		if (preview.getStep() > 1) {
			parts.add(labeled("string.explorerscompass.previewScale", I18n.get("string.explorerscompass.previewCell", String.valueOf(preview.getStep()))));
		}
		if (view.isCutAway(preview)) {
			parts.add(I18n.get("string.explorerscompass.previewCutaway", String.valueOf(view.layersShown(preview)), String.valueOf(preview.getGridY())));
		}

		font.drawShadow(poseStack, RenderUtils.trimToWidth(String.join(" · ", parts), Math.max(0, readingsWidth)), textLeft, top + 6, GuiTheme.TEXT_PRIMARY);
	}

	/** Whatever there is to say about how faithful what is being shown actually is. */
	private String note(StructurePreview preview) {
		if (preview.isOutlineOnly()) {
			return I18n.get("string.explorerscompass.previewOutlineOnly");
		}
		if (preview.getOutlinedPieces() > 0) {
			return I18n.get("string.explorerscompass.previewPartialOutline", String.valueOf(preview.getOutlinedPieces()));
		}
		if (preview.isTruncated()) {
			return I18n.get("string.explorerscompass.previewTruncated");
		}
		return "";
	}

	/** A labelled value, punctuated the way the player's own language punctuates one. */
	private static String labeled(String labelKey, String value) {
		return I18n.get("string.explorerscompass.labeledValue", I18n.get(labelKey), value);
	}

	private void renderButtonTooltip(PoseStack poseStack, int mouseX, int mouseY) {
		for (Object widget : renderables) {
			if (widget instanceof TransparentButton button && button.visible && button.isHoveredOrFocused() && !button.getTooltipLines().isEmpty()) {
				renderComponentTooltip(poseStack, button.getTooltipLines(), mouseX, mouseY);
				return;
			}
		}
	}

	private int infoBarTop() {
		return height - INFO_BAR_HEIGHT - 6;
	}

	/** Whether a point is inside the panel the model is drawn in, which is what the pointer turns. */
	private boolean isOverView(double mouseX, double mouseY) {
		final int left = GuiTheme.contentLeft();
		return mouseX >= left && mouseX < left + GuiTheme.contentWidth(width) && mouseY >= GuiTheme.HEADER_HEIGHT + 6 && mouseY < infoBarTop() - 4;
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button == 0 && isOverView(mouseX, mouseY)) {
			view.setDragging(true);
			return true;
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		view.setDragging(false);
		return super.mouseReleased(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
		if (button == 0 && isOverView(mouseX, mouseY)) {
			view.drag(dragX, dragY);
			return true;
		}
		return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		if (!isOverView(mouseX, mouseY)) {
			return super.mouseScrolled(mouseX, mouseY, amount);
		}

		final StructurePreview preview = StructurePreviewCache.get(structureKey);
		if (hasShiftDown() && preview != null) {
			view.cut(amount, preview);
		} else {
			view.zoom(amount);
		}
		return true;
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (keyCode == GLFW.GLFW_KEY_R) {
			view.reset();
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parentScreen);
	}

	/** Searches for this structure, which is what a preview is looked at in order to decide. */
	private void search() {
		SearchHistory.pushRecent(SearchTarget.STRUCTURE, structureKey);
		ExplorersCompass.network.sendToServer(CompassSearchPacket.forTargets(SearchTarget.STRUCTURE, List.of(structureKey)));
		minecraft.setScreen(null);
	}

	private void setupWidgets() {
		clearWidgets();
		sidebarY = GuiTheme.HEADER_HEIGHT + 8;

		searchButton = addSidebarButton(Component.translatable("string.explorerscompass.search"), (onPress) -> {
			search();
		});
		searchButton.setTooltipLines(Component.translatable("string.explorerscompass.tooltip.search"));

		spinButton = addSidebarButton(Component.translatable("string.explorerscompass.autoSpin"), (onPress) -> {
			final boolean spinning = !ConfigHandler.CLIENT.structurePreviewAutoSpin.get();
			ConfigHandler.CLIENT.structurePreviewAutoSpin.set(Boolean.valueOf(spinning));
			// Written out as well as applied, so that a preview opened in the next session opens the
			// way this one was left rather than the way it was configured
			ConfigHandler.CLIENT.structurePreviewAutoSpin.save();
			spinButton.setHighlighted(spinning);
		});
		spinButton.setHighlighted(ConfigHandler.CLIENT.structurePreviewAutoSpin.get());
		spinButton.setTooltipLines(Component.translatable("string.explorerscompass.tooltip.autoSpin"));

		resetButton = addSidebarButton(Component.translatable("string.explorerscompass.resetView"), (onPress) -> {
			view.reset();
		});
		resetButton.setTooltipLines(Component.translatable("string.explorerscompass.tooltip.resetView"));

		backButton = addRenderableWidget(new TransparentButton(GuiTheme.SIDEBAR_CONTENT_X, height - 26, GuiTheme.SIDEBAR_CONTENT_WIDTH, GuiTheme.BUTTON_HEIGHT, Component.translatable("string.explorerscompass.back"), (onPress) -> {
			onClose();
		}));
		backButton.setTooltipLines(Component.translatable("string.explorerscompass.tooltip.back"));
	}

	/** Adds a control to the bottom of the sidebar column, and moves the column down past it. */
	private TransparentButton addSidebarButton(Component label, Button.OnPress onPress) {
		final TransparentButton button = addRenderableWidget(new TransparentButton(GuiTheme.SIDEBAR_CONTENT_X, sidebarY, GuiTheme.SIDEBAR_CONTENT_WIDTH, GuiTheme.BUTTON_HEIGHT, label, onPress));
		sidebarY += GuiTheme.BUTTON_SPACING;
		return button;
	}

}
