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
 *
 * <p>The model can be turned, zoomed and slid about with the mouse, looked at from a handful of set
 * angles, cut open layer by layer down the slider beside it, and drawn either as the blocks it is
 * built of or as flat colours where that is quicker.
 */
@OnlyIn(Dist.CLIENT)
public class StructurePreviewScreen extends Screen {

	/** The strip along the bottom that says what is being shown. */
	private static final int INFO_BAR_HEIGHT = 34;
	/** One cell to one block, written the way a scale is written in every language. */
	private static final String ONE_TO_ONE = "1:1";
	private static final int SLIDER_WIDTH = 8;
	private static final int SLIDER_INSET = 8;
	private static final int COMPASS_RADIUS = 11;
	/** Room between the compass and the corner of the panel for the letter that rides outside its rim. */
	private static final int COMPASS_MARGIN = 14;
	/** How far a press of an arrow key turns the model. */
	private static final float KEY_TURN_DEGREES = 15.0F;

	/** The angles the model can be looked at from at the press of a button. */
	private enum ViewPreset {
		ISOMETRIC("isometric", 45.0F, 30.0F),
		TOP("top", 0.0F, 89.0F),
		SOUTH("south", 0.0F, 15.0F),
		WEST("west", 90.0F, 15.0F),
		NORTH("north", 180.0F, 15.0F),
		EAST("east", 270.0F, 15.0F);

		private final String key;
		private final float yaw;
		private final float pitch;

		private ViewPreset(String key, float yaw, float pitch) {
			this.key = "string.explorerscompass.viewPreset." + key;
			this.yaw = yaw;
			this.pitch = pitch;
		}

		private ViewPreset next() {
			return values()[(ordinal() + 1) % values().length];
		}
	}

	private final Screen parentScreen;
	private final ResourceLocation structureKey;
	private final StructurePreviewView view = new StructurePreviewView();
	private TransparentButton searchButton;
	private TransparentButton spinButton;
	private TransparentButton resetButton;
	private TransparentButton viewButton;
	private TransparentButton modeButton;
	private TransparentButton backButton;
	private LayerSlider layerSlider;
	private ViewPreset viewPreset = ViewPreset.ISOMETRIC;
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

	/**
	 * The backdrop, the furniture standing on it, the model itself, and then the controls. The model
	 * goes down before the controls are drawn by the base screen, because the slider standing inside
	 * the panel has to be drawn over the model rather than under it.
	 */
	@Override
	public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTicks) {
		renderBackground(poseStack);
		GuiTheme.drawHeader(width);
		GuiTheme.drawSidebar(height);
		GuiTheme.drawTitle(poseStack, font, title.getString(), structureKey.toString(), GuiTheme.SIDEBAR_CONTENT_X, 10);

		final int left = panelLeft();
		final int right = panelRight();
		final int top = panelTop();
		final int bottom = panelBottom();
		GuiTheme.drawScreenPanel(left, top, right, bottom, ConfigHandler.CLIENT.guiSidebarBackground.get());

		final StructurePreview preview = StructurePreviewCache.get(structureKey);
		final boolean hasModel = preview != null && (preview.getCellCount() > 0 || preview.getComponentCount() > 0);
		if (hasModel) {
			// Inside the panel border, so that the model is never drawn over its own edges
			view.render(poseStack, preview, left + 1, top + 1, right - 1, bottom - 1);
			view.renderCompass(poseStack, font, left + COMPASS_MARGIN + COMPASS_RADIUS, top + COMPASS_MARGIN + COMPASS_RADIUS, COMPASS_RADIUS);
			renderBuildProgress(poseStack, left, bottom);
		} else {
			// A preview that arrived holding nothing has as little to draw as one that never arrived
			drawWaitingMessage(poseStack, left, top, right, bottom);
		}

		syncControls(preview, hasModel);
		renderInfoBar(poseStack, preview, left, right);

		super.render(poseStack, mouseX, mouseY, partialTicks);
		renderButtonTooltip(poseStack, mouseX, mouseY);
	}

	/** Keeps the controls saying what the view is doing, whichever of them it was changed through. */
	private void syncControls(StructurePreview preview, boolean hasModel) {
		layerSlider.visible = hasModel && preview.getGridY() > 1;
		layerSlider.active = layerSlider.visible;
		if (layerSlider.visible) {
			layerSlider.setRange(preview.getGridY(), view.layersShown(preview));
		}
		modeButton.active = hasModel;
		if (hasModel) {
			modeButton.setMessage(modeButtonLabel(preview));
			modeButton.setHighlighted(view.getRequestedMode() != null);
		}
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

	/**
	 * A small note in the corner of the panel while the textured model is still being built, with how
	 * far it has got, so that the flat colours standing in for it read as a stage rather than as the
	 * result.
	 */
	private void renderBuildProgress(PoseStack poseStack, int left, int bottom) {
		final int progress = view.getBuildProgress();
		if (progress < 0) {
			return;
		}
		final String text = I18n.get("string.explorerscompass.previewBuilding", String.valueOf(progress / 10));
		final int chipWidth = font.width(text) + 10;
		final int chipLeft = left + 6;
		final int chipTop = bottom - 20;
		RenderUtils.drawRect(chipLeft, chipTop, chipLeft + chipWidth, chipTop + 14, 0x90000000);
		RenderUtils.drawProgressBar(chipLeft, chipTop + 13, chipLeft + chipWidth, chipTop + 14, progress / 1000.0F, 0x00000000, GuiTheme.ACCENT | 0xFF000000);
		font.draw(poseStack, text, chipLeft + 5, chipTop + 3, GuiTheme.TEXT_SECONDARY);
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
		parts.add(labeled("string.explorerscompass.previewBlocks", String.format("%,d", preview.getCellCount() + preview.getInteriorCellCount())));
		parts.add(labeled("string.explorerscompass.previewPieces", String.valueOf(preview.getPieces())));
		// Said either way round: that a preview is one cell to one block is the thing worth knowing
		// about it, and its absence is what says the structure had to be shrunk to be shown
		parts.add(labeled("string.explorerscompass.previewScale", preview.getStep() > 1
				? I18n.get("string.explorerscompass.previewCell", String.valueOf(preview.getStep()))
				: ONE_TO_ONE));
		if (view.getDrawnComponents() > 0) {
			parts.add(labeled("string.explorerscompass.previewComponents", String.valueOf(view.getDrawnComponents())));
		}
		if (view.isSimplified()) {
			// Worth saying outright: a structure shown in flat colours is not one made of one material
			parts.add(I18n.get("string.explorerscompass.previewSimplified"));
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
			if (widget instanceof TransparentButton button && button.visible && button.isPointedAt() && !button.getTooltipLines().isEmpty()) {
				renderComponentTooltip(poseStack, button.getTooltipLines(), mouseX, mouseY);
				return;
			}
		}
		if (layerSlider.visible && layerSlider.isMouseOver(mouseX, mouseY)) {
			renderComponentTooltip(poseStack, List.of(Component.translatable("string.explorerscompass.tooltip.layerSlider")), mouseX, mouseY);
		}
	}

	private int panelLeft() {
		return GuiTheme.contentLeft();
	}

	private int panelRight() {
		return panelLeft() + GuiTheme.contentWidth(width);
	}

	private int panelTop() {
		return GuiTheme.HEADER_HEIGHT + 6;
	}

	private int panelBottom() {
		return infoBarTop() - 4;
	}

	private int infoBarTop() {
		return height - INFO_BAR_HEIGHT - 6;
	}

	/** Whether a point is inside the panel the model is drawn in, which is what the pointer turns. */
	private boolean isOverView(double mouseX, double mouseY) {
		return mouseX >= panelLeft() && mouseX < panelRight() && mouseY >= panelTop() && mouseY < panelBottom();
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		// The controls standing inside the panel take the click before the panel itself does
		if (super.mouseClicked(mouseX, mouseY, button)) {
			return true;
		}
		if (isOverView(mouseX, mouseY)) {
			if (button == 0) {
				view.setDragging(true);
				return true;
			}
			if (button == 1) {
				view.setPanning(true);
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		view.setDragging(false);
		view.setPanning(false);
		return super.mouseReleased(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
		if (button == 0 && view.isDragging()) {
			view.drag(dragX, dragY);
			return true;
		}
		if (button == 1 && view.isPanning()) {
			view.pan(dragX, dragY);
			return true;
		}
		return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
		if (layerSlider.visible && layerSlider.mouseScrolled(mouseX, mouseY, delta)) {
			return true;
		}
		if (!isOverView(mouseX, mouseY)) {
			return super.mouseScrolled(mouseX, mouseY, delta);
		}

		final StructurePreview preview = StructurePreviewCache.get(structureKey);
		if (hasShiftDown() && preview != null) {
			view.cut(delta, preview);
		} else {
			view.zoom(delta);
		}
		return true;
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		final StructurePreview preview = StructurePreviewCache.get(structureKey);
		switch (keyCode) {
			case GLFW.GLFW_KEY_R:
			case GLFW.GLFW_KEY_HOME:
				view.reset();
				viewPreset = ViewPreset.ISOMETRIC;
				viewButton.setMessage(viewButtonLabel());
				return true;
			case GLFW.GLFW_KEY_LEFT:
				view.rotate(-KEY_TURN_DEGREES, 0.0F);
				return true;
			case GLFW.GLFW_KEY_RIGHT:
				view.rotate(KEY_TURN_DEGREES, 0.0F);
				return true;
			case GLFW.GLFW_KEY_UP:
				view.rotate(0.0F, KEY_TURN_DEGREES);
				return true;
			case GLFW.GLFW_KEY_DOWN:
				view.rotate(0.0F, -KEY_TURN_DEGREES);
				return true;
			case GLFW.GLFW_KEY_EQUAL:
			case GLFW.GLFW_KEY_KP_ADD:
				view.zoom(1.0D);
				return true;
			case GLFW.GLFW_KEY_MINUS:
			case GLFW.GLFW_KEY_KP_SUBTRACT:
				view.zoom(-1.0D);
				return true;
			case GLFW.GLFW_KEY_PAGE_UP:
				if (preview != null) {
					view.cut(1.0D, preview);
				}
				return true;
			case GLFW.GLFW_KEY_PAGE_DOWN:
				if (preview != null) {
					view.cut(-1.0D, preview);
				}
				return true;
			default:
				return super.keyPressed(keyCode, scanCode, modifiers);
		}
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parentScreen);
	}

	@Override
	public void removed() {
		// The built model is memory on the graphics card, which nothing else would ever give back
		view.close();
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

		viewButton = addSidebarButton(viewButtonLabel(), (onPress) -> {
			viewPreset = viewPreset.next();
			view.lookFrom(viewPreset.yaw, viewPreset.pitch);
			viewButton.setMessage(viewButtonLabel());
		});
		viewButton.setTooltipLines(Component.translatable("string.explorerscompass.tooltip.viewPreset"));

		modeButton = addSidebarButton(Component.translatable("string.explorerscompass.renderMode"), (onPress) -> {
			final StructurePreview preview = StructurePreviewCache.get(structureKey);
			if (preview != null) {
				view.toggleMode(preview);
				modeButton.setMessage(modeButtonLabel(preview));
			}
		});
		modeButton.active = false;
		modeButton.setTooltipLines(Component.translatable("string.explorerscompass.tooltip.renderMode"), Component.translatable("string.explorerscompass.tooltip.renderMode.cost"));

		resetButton = addSidebarButton(Component.translatable("string.explorerscompass.resetView"), (onPress) -> {
			view.reset();
			viewPreset = ViewPreset.ISOMETRIC;
			viewButton.setMessage(viewButtonLabel());
		});
		resetButton.setTooltipLines(Component.translatable("string.explorerscompass.tooltip.resetView"));

		backButton = addRenderableWidget(new TransparentButton(GuiTheme.SIDEBAR_CONTENT_X, height - 26, GuiTheme.SIDEBAR_CONTENT_WIDTH, GuiTheme.BUTTON_HEIGHT, Component.translatable("string.explorerscompass.back"), (onPress) -> {
			onClose();
		}));
		backButton.setTooltipLines(Component.translatable("string.explorerscompass.tooltip.back"));

		// Stands inside the panel against its right edge, level with the model it cuts
		final int sliderHeight = panelBottom() - panelTop() - 2 * SLIDER_INSET;
		layerSlider = addRenderableWidget(new LayerSlider(panelRight() - SLIDER_INSET - SLIDER_WIDTH, panelTop() + SLIDER_INSET, SLIDER_WIDTH, sliderHeight, (layers) -> {
			final StructurePreview preview = StructurePreviewCache.get(structureKey);
			if (preview != null) {
				view.setLayersShown(layers, preview);
			}
		}));
		layerSlider.visible = false;
		layerSlider.active = false;
	}

	/** Adds a control to the bottom of the sidebar column, and moves the column down past it. */
	private TransparentButton addSidebarButton(Component label, Button.OnPress onPress) {
		final TransparentButton button = addRenderableWidget(new TransparentButton(GuiTheme.SIDEBAR_CONTENT_X, sidebarY, GuiTheme.SIDEBAR_CONTENT_WIDTH, GuiTheme.BUTTON_HEIGHT, label, onPress));
		sidebarY += GuiTheme.BUTTON_SPACING;
		return button;
	}

	private Component viewButtonLabel() {
		return Component.translatable("string.explorerscompass.viewPreset").append(Component.literal(": ")).append(Component.translatable(viewPreset.key));
	}

	private Component modeButtonLabel(StructurePreview preview) {
		final String modeKey = view.getMode(preview) == PreviewMeshBuilder.Mode.TEXTURED ? "string.explorerscompass.renderMode.textured" : "string.explorerscompass.renderMode.coloured";
		return Component.translatable("string.explorerscompass.renderMode").append(Component.literal(": ")).append(Component.translatable(modeKey));
	}

}
