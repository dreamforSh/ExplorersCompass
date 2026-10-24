package com.chaosthedude.explorerscompass.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * A small round button marked with a single glyph, for an action that belongs to one row rather than
 * to the screen, such as putting a setting's default back. It sits as a faint disc that brightens
 * under the pointer, so that a row full of them does not shout.
 */
@OnlyIn(Dist.CLIENT)
public class GlyphButton extends AbstractButton {

	public static final int SIZE = 12;

	private final String glyph;
	private final Runnable onPress;
	private final UiAnim.Value hoverAnim = new UiAnim.Value();

	public GlyphButton(int x, int y, String glyph, Component message, Runnable onPress) {
		super(x, y, SIZE, SIZE, message);
		this.glyph = glyph;
		this.onPress = onPress;
	}

	@Override
	public void onPress() {
		onPress.run();
	}

	@Override
	protected void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		final Font font = Minecraft.getInstance().font;
		final boolean keyboardFocus = isFocused() && Minecraft.getInstance().getLastInputType().isKeyboard();
		final float hover = hoverAnim.hover(isHovered() || keyboardFocus);
		final float cx = getX() + width / 2.0F;
		final float cy = getY() + height / 2.0F;
		UiShapes.circle(guiGraphics, cx, cy, width / 2.0F, GuiTheme.withAlpha(GuiTheme.ACCENT, 0x30 + Math.round(0x40 * hover)));
		guiGraphics.drawString(font, glyph, Math.round(cx - font.width(glyph) / 2.0F), getY() + (height - 8) / 2, GuiTheme.lerpColor(GuiTheme.ACCENT_LIGHT, GuiTheme.TEXT_PRIMARY, hover), false);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
		defaultButtonNarrationText(narrationElementOutput);
	}

}
