package com.chaosthedude.explorerscompass.gui;

import java.util.function.IntConsumer;
import java.util.function.IntUnaryOperator;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * A whole number picked off a track: the track fills with the accent as far as the knob, the knob
 * eases to wherever the value is put, and the value itself is written to the right of the track.
 * Dragging picks a value, and the arrow keys nudge it by one, or by ten with Shift held, while the
 * slider has the focus.
 *
 * <p>What is picked is reported as it changes, so that it can be seen to take effect, and reported
 * again once the knob is let go of, which is when it is worth writing anywhere.
 */
@OnlyIn(Dist.CLIENT)
public class ValueSlider extends AbstractWidget {

	public static final int HEIGHT = 14;
	private static final int TRACK_HEIGHT = 3;
	private static final int KNOB_RADIUS = 4;
	/** Between the end of the track and the value written beside it. */
	private static final int VALUE_GAP = 6;
	/** How wide the colour swatch standing in front of the value is, where the value has a colour. */
	private static final int SWATCH = 8;

	private final int min;
	private final int max;
	private final IntConsumer onChange;
	private final Runnable onCommit;
	/** The colour a value stands for, or 0 where values stand for none. */
	private final IntUnaryOperator swatch;
	private final UiAnim.Value knobAnim = new UiAnim.Value();
	private final UiAnim.Value hoverAnim = new UiAnim.Value();
	private int value;
	private boolean dragging;

	public ValueSlider(int x, int y, int width, Component message, int min, int max, int value, IntUnaryOperator swatch, IntConsumer onChange, Runnable onCommit) {
		super(x, y, width, HEIGHT, message);
		this.min = min;
		this.max = Math.max(min + 1, max);
		this.value = Mth.clamp(value, min, this.max);
		this.swatch = swatch;
		this.onChange = onChange;
		this.onCommit = onCommit;
	}

	/** How wide a slider for the given range comes out with a track of the given length. */
	public static int widthFor(Font font, int min, int max, int trackWidth, boolean swatch) {
		return trackWidth + VALUE_GAP + valueWidth(font, min, max) + (swatch ? SWATCH + 3 : 0);
	}

	private static int valueWidth(Font font, int min, int max) {
		return Math.max(font.width(Integer.toString(min)), font.width(Integer.toString(max)));
	}

	/** Shows the given value without reporting it, for when the value was changed from elsewhere. */
	public void setValue(int value) {
		if (!dragging) {
			this.value = Mth.clamp(value, min, max);
		}
	}

	private boolean hasSwatch() {
		return swatch.applyAsInt(value) != 0;
	}

	private float trackLeft() {
		return getX() + KNOB_RADIUS;
	}

	private float trackWidth() {
		final Font font = Minecraft.getInstance().font;
		return Math.max(10, width - KNOB_RADIUS - VALUE_GAP - valueWidth(font, min, max) - (hasSwatch() ? SWATCH + 3 : 0) - KNOB_RADIUS);
	}

	@Override
	protected void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		final Font font = Minecraft.getInstance().font;
		final boolean keyboardFocus = isFocused() && Minecraft.getInstance().getLastInputType().isKeyboard();
		final float hover = hoverAnim.hover(active && (isHovered() || dragging || keyboardFocus));
		final float t = knobAnim.approach((value - min) / (float) (max - min), 22.0F);
		final float trackX = trackLeft();
		final float trackW = trackWidth();
		final float midY = getY() + height / 2.0F;

		UiShapes.pill(guiGraphics, trackX, midY - TRACK_HEIGHT / 2.0F, trackW, TRACK_HEIGHT, GuiTheme.withAlpha(GuiTheme.TEXT_MUTED, 0x60));
		final int fill = active ? GuiTheme.lerpColor(GuiTheme.opaque(GuiTheme.ACCENT), GuiTheme.opaque(GuiTheme.ACCENT_HOVER), hover) : GuiTheme.withAlpha(GuiTheme.TEXT_MUTED, 0x80);
		UiShapes.pill(guiGraphics, trackX, midY - TRACK_HEIGHT / 2.0F, Math.max(TRACK_HEIGHT, trackW * t), TRACK_HEIGHT, fill);
		final float knobX = trackX + trackW * t;
		final float radius = KNOB_RADIUS + hover;
		UiShapes.circle(guiGraphics, knobX, midY, radius + 0.8F, fill);
		UiShapes.circle(guiGraphics, knobX, midY, radius - 0.6F, 0xFFFFFFFF);

		float textX = trackX + trackW + KNOB_RADIUS + VALUE_GAP;
		final int color = swatch.applyAsInt(value);
		if (color != 0) {
			UiShapes.roundRect(guiGraphics, textX, midY - SWATCH / 2.0F, SWATCH, SWATCH, 2.0F, 0xFF000000 | color);
			UiShapes.roundRectStroke(guiGraphics, textX, midY - SWATCH / 2.0F, SWATCH, SWATCH, 2.0F, UiShapes.stroke(), 0x50FFFFFF);
			textX += SWATCH + 3;
		}
		final String text = Integer.toString(value);
		final int right = Math.round(textX) + valueWidth(font, min, max);
		guiGraphics.drawString(font, text, right - font.width(text), getY() + (height - 8) / 2, active ? GuiTheme.lerpColor(GuiTheme.TEXT_SECONDARY, GuiTheme.TEXT_PRIMARY, hover) : GuiTheme.TEXT_DISABLED, false);
	}

	@Override
	public void onClick(double mouseX, double mouseY) {
		dragging = true;
		setFromMouse(mouseX);
	}

	@Override
	protected void onDrag(double mouseX, double mouseY, double dragX, double dragY) {
		setFromMouse(mouseX);
	}

	@Override
	public void onRelease(double mouseX, double mouseY) {
		if (dragging) {
			dragging = false;
			onCommit.run();
		}
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (!active || !visible) {
			return false;
		}
		if (keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_RIGHT) {
			final int step = (Screen.hasShiftDown() ? 10 : 1) * (keyCode == GLFW.GLFW_KEY_RIGHT ? 1 : -1);
			set(value + step);
			onCommit.run();
			return true;
		}
		return false;
	}

	private void setFromMouse(double mouseX) {
		final double fraction = (mouseX - trackLeft()) / trackWidth();
		set(min + (int) Math.round(Mth.clamp(fraction, 0.0D, 1.0D) * (max - min)));
	}

	private void set(int newValue) {
		final int clamped = Mth.clamp(newValue, min, max);
		if (clamped != value) {
			value = clamped;
			onChange.accept(value);
		}
	}

	/** Silent on the way down: a slider that clicked on every value it passed would rattle. */
	@Override
	public void playDownSound(SoundManager handler) {
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
		narrationElementOutput.add(NarratedElementType.TITLE, CommonComponents.optionNameValue(getMessage(), Component.literal(Integer.toString(value))));
	}

}
