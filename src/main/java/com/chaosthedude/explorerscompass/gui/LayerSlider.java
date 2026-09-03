package com.chaosthedude.explorerscompass.gui;

import java.util.function.IntConsumer;

import com.chaosthedude.explorerscompass.util.RenderUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * A slider standing upright beside the model, for cutting layers off the top of it.
 *
 * <p>Upright because that is what it cuts: the handle stands level with the top of what is shown,
 * so dragging it down the side of the panel reads as slicing the building down. The whole height is
 * shown at the top and the ground layer alone at the bottom, and the reading beside the handle says
 * how many layers of how many are up.
 */
@OnlyIn(Dist.CLIENT)
public class LayerSlider extends AbstractWidget {

	private static final int HANDLE_HEIGHT = 7;
	private static final int TRACK_COLOR = 0x50000000;
	private static final int TRACK_BORDER = 0x28FFFFFF;
	private static final int TRACK_FILL = 0x50FFC24B;
	private static final int HANDLE_COLOR = 0xFFC9A24A;
	private static final int HANDLE_COLOR_HOVERED = 0xFFFFC24B;

	private final IntConsumer onChange;
	private int max = 1;
	private int value = 1;

	public LayerSlider(int x, int y, int width, int height, IntConsumer onChange) {
		super(x, y, width, height, Component.translatable("string.explorerscompass.layers"));
		this.onChange = onChange;
	}

	/** Tells the slider how many layers there are, and how many of them are up. Draws nothing until told. */
	public void setRange(int max, int value) {
		this.max = Math.max(1, max);
		this.value = Mth.clamp(value, 1, this.max);
	}

	public int getValue() {
		return value;
	}

	/** How far up the handle stands, from the ground layer at 0 to the whole height at 1. */
	private float fraction() {
		return max <= 1 ? 1.0F : (value - 1) / (float) (max - 1);
	}

	private int travel() {
		return Math.max(1, height - HANDLE_HEIGHT);
	}

	private int handleTop() {
		return getY() + Math.round((1.0F - fraction()) * travel());
	}

	@Override
	protected void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		final int x = getX();
		final int y = getY();
		final int trackLeft = x + width / 2 - 2;
		final int trackRight = trackLeft + 4;
		RenderUtils.drawRect(guiGraphics, trackLeft, y, trackRight, y + height, TRACK_COLOR);
		RenderUtils.drawInnerOutline(guiGraphics, trackLeft, y, trackRight, y + height, TRACK_BORDER);

		// The track fills from the ground up to the handle: what is filled is what is shown
		final int top = handleTop();
		RenderUtils.drawRect(guiGraphics, trackLeft + 1, top + HANDLE_HEIGHT / 2, trackRight - 1, y + height - 1, TRACK_FILL);

		final boolean lit = isHoveredOrFocused();
		RenderUtils.drawRect(guiGraphics, x, top, x + width, top + HANDLE_HEIGHT, lit ? HANDLE_COLOR_HOVERED : HANDLE_COLOR);
		RenderUtils.drawRect(guiGraphics, x + 1, top + 1, x + width - 1, top + 2, 0x60FFFFFF);

		// The reading stands to the left of the handle, kept inside the panel at either end
		final String reading = value + " / " + max;
		final int textWidth = Minecraft.getInstance().font.width(reading);
		final int textY = Mth.clamp(top + HANDLE_HEIGHT / 2 - 4, y, y + height - 8);
		guiGraphics.drawString(Minecraft.getInstance().font, reading, x - 4 - textWidth, textY, lit ? GuiTheme.TEXT_PRIMARY : GuiTheme.TEXT_SECONDARY, true);
	}

	@Override
	public void onClick(double mouseX, double mouseY, int button) {
		setFromMouse(mouseY);
	}

	@Override
	protected void onDrag(double mouseX, double mouseY, double dragX, double dragY) {
		setFromMouse(mouseY);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (!isMouseOver(mouseX, mouseY)) {
			return false;
		}
		setValue(value + (int) Math.signum(scrollY));
		return true;
	}

	/** The layer the pointer stands level with, so that the handle follows it exactly. */
	private void setFromMouse(double mouseY) {
		final float fraction = 1.0F - (float) ((mouseY - (getY() + HANDLE_HEIGHT / 2.0D)) / travel());
		setValue(1 + Math.round(Mth.clamp(fraction, 0.0F, 1.0F) * (max - 1)));
	}

	private void setValue(int layers) {
		final int clamped = Mth.clamp(layers, 1, max);
		if (clamped != value) {
			value = clamped;
			onChange.accept(value);
		}
	}

	/** Silent on the way down: a slider that clicked on every layer would rattle. */
	@Override
	public void playDownSound(SoundManager handler) {
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
		narrationElementOutput.add(NarratedElementType.TITLE, Component.translatable("gui.narrate.slider", getMessage()));
	}

}
