package com.chaosthedude.explorerscompass.gui;

import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * A switch for a setting that is either on or off: a track that fills with the accent when it is on,
 * and a knob that slides across it rather than jumping, so that the change reads as something that
 * happened rather than as two pictures swapped.
 */
@OnlyIn(Dist.CLIENT)
public class ToggleSwitch extends AbstractButton {

	public static final int WIDTH = 22;
	public static final int HEIGHT = 12;

	private final Consumer<Boolean> onToggle;
	private final UiAnim.Value knobAnim = new UiAnim.Value();
	private final UiAnim.Value hoverAnim = new UiAnim.Value();
	private boolean on;

	public ToggleSwitch(int x, int y, Component message, boolean on, Consumer<Boolean> onToggle) {
		super(x, y, WIDTH, HEIGHT, message);
		this.on = on;
		this.onToggle = onToggle;
	}

	public boolean isOn() {
		return on;
	}

	/** Shows the given state without reporting it, for when the value was changed from elsewhere. */
	public void setOn(boolean on) {
		this.on = on;
	}

	@Override
	public void onPress() {
		on = !on;
		onToggle.accept(on);
	}

	@Override
	protected void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		final float t = knobAnim.approach(on ? 1.0F : 0.0F, UiAnim.SLIDE_SPEED);
		final boolean keyboardFocus = isFocused() && Minecraft.getInstance().getLastInputType().isKeyboard();
		final float hover = hoverAnim.hover(active && (isHovered() || keyboardFocus));
		final float x = getX();
		final float y = getY();
		final int offTrack = GuiTheme.withAlpha(GuiTheme.TEXT_MUTED, 0x80);
		int track = GuiTheme.lerpColor(offTrack, GuiTheme.opaque(GuiTheme.ACCENT), t);
		track = GuiTheme.lerpColor(track, GuiTheme.lerpColor(GuiTheme.withAlpha(GuiTheme.TEXT_SECONDARY, 0xA0), GuiTheme.opaque(GuiTheme.ACCENT_HOVER), t), hover * 0.6F);
		if (!active) {
			track = GuiTheme.scaleAlpha(track, 0.4F);
		}
		UiShapes.pill(guiGraphics, x, y, width, height, track);
		if (keyboardFocus) {
			UiShapes.roundRectStroke(guiGraphics, x - 1.5F, y - 1.5F, width + 3, height + 3, (height + 3) / 2.0F, 1.0F, GuiTheme.withAlpha(GuiTheme.ACCENT, 0xB0));
		}
		final float knob = height - 3.0F;
		final float cx = x + 1.5F + knob / 2 + t * (width - height);
		UiShapes.circle(guiGraphics, cx, y + height / 2.0F, knob / 2, active ? 0xFFFFFFFF : 0xFF9AA0A6);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
		narrationElementOutput.add(NarratedElementType.TITLE, CommonComponents.optionNameValue(getMessage(), CommonComponents.optionStatus(on)));
	}

}
