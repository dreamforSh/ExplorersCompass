package com.chaosthedude.explorerscompass.gui;

import java.util.List;
import java.util.function.IntConsumer;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * A choice between a few options laid side by side in one track, the one in force filled with the
 * accent. The fill slides from one option to the next when the choice changes, and every option
 * stays in view, so that what else there is to choose from never has to be clicked through to find.
 * The arrow keys move the choice while the control has the focus.
 */
@OnlyIn(Dist.CLIENT)
public class SegmentedControl extends AbstractWidget {

	public static final int HEIGHT = 14;
	/** Room either side of an option's label. */
	private static final int PADDING = 6;

	private final List<Component> options;
	private final IntConsumer onSelect;
	private final UiAnim anim = new UiAnim();
	private final Object pillX = new Object();
	private final Object pillWidth = new Object();
	private int selected;

	public SegmentedControl(int x, int y, Component message, List<Component> options, int selected, IntConsumer onSelect) {
		super(x, y, naturalWidth(Minecraft.getInstance().font, options), HEIGHT, message);
		this.options = List.copyOf(options);
		this.selected = selected;
		this.onSelect = onSelect;
	}

	/** How wide the control comes out with each option as wide as its label needs. */
	public static int naturalWidth(Font font, List<Component> options) {
		int total = 2;
		for (Component option : options) {
			total += font.width(option) + PADDING * 2;
		}
		return total;
	}

	/** Shows the given option as chosen without reporting it, for when the value was changed from elsewhere. */
	public void setSelected(int selected) {
		this.selected = selected;
	}

	/** Where the given option begins, with whatever width the control has beyond its labels shared out evenly. */
	private float segmentLeft(Font font, int index) {
		final float extra = (width - naturalWidth(font, options)) / (float) options.size();
		float x = getX() + 1;
		for (int i = 0; i < index; i++) {
			x += font.width(options.get(i)) + PADDING * 2 + extra;
		}
		return x;
	}

	private float segmentWidth(Font font, int index) {
		final float extra = (width - naturalWidth(font, options)) / (float) options.size();
		return font.width(options.get(index)) + PADDING * 2 + extra;
	}

	@Override
	protected void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		final Font font = Minecraft.getInstance().font;
		anim.frame();
		final float x = getX();
		final float y = getY();
		final boolean keyboardFocus = isFocused() && Minecraft.getInstance().getLastInputType().isKeyboard();
		UiShapes.pill(guiGraphics, x, y, width, height, GuiTheme.WIDGET_BACKGROUND);
		UiShapes.roundRectStroke(guiGraphics, x, y, width, height, height / 2.0F, UiShapes.stroke(), keyboardFocus ? GuiTheme.withAlpha(GuiTheme.ACCENT, 0xB0) : GuiTheme.WIDGET_BORDER);

		if (selected >= 0 && selected < options.size()) {
			// Eased relative to the control, so that the control being moved, as a scrolled list moves
			// it, does not set the fill sliding after it
			final float sx = x + anim.approach(pillX, segmentLeft(font, selected) - x, UiAnim.SLIDE_SPEED);
			final float sw = anim.approach(pillWidth, segmentWidth(font, selected), UiAnim.SLIDE_SPEED);
			UiShapes.pill(guiGraphics, sx, y + 1, sw, height - 2, active ? GuiTheme.opaque(GuiTheme.ACCENT) : GuiTheme.withAlpha(GuiTheme.TEXT_MUTED, 0x80));
		}

		for (int i = 0; i < options.size(); i++) {
			final float left = segmentLeft(font, i);
			final float w = segmentWidth(font, i);
			final boolean hovered = active && isHovered() && mouseX >= left && mouseX < left + w;
			final float hover = anim.hover(i, hovered && i != selected);
			if (hover > 0.01F) {
				UiShapes.pill(guiGraphics, left, y + 1, w, height - 2, GuiTheme.withAlpha(0xFFFFFF, Math.round(0x18 * hover)));
			}
			final int color = !active ? GuiTheme.TEXT_DISABLED : i == selected ? GuiTheme.ACCENT_INK : GuiTheme.lerpColor(GuiTheme.TEXT_SECONDARY, GuiTheme.TEXT_PRIMARY, hover);
			final Component label = options.get(i);
			guiGraphics.drawString(font, label, Math.round(left + (w - font.width(label)) / 2), getY() + (height - 8) / 2, color, false);
		}
	}

	@Override
	public void onClick(double mouseX, double mouseY) {
		final Font font = Minecraft.getInstance().font;
		for (int i = 0; i < options.size(); i++) {
			final float left = segmentLeft(font, i);
			if (mouseX >= left && mouseX < left + segmentWidth(font, i)) {
				select(i);
				return;
			}
		}
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (!active || !visible) {
			return false;
		}
		if (keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_RIGHT) {
			select(Math.floorMod(selected + (keyCode == GLFW.GLFW_KEY_RIGHT ? 1 : -1), options.size()));
			return true;
		}
		return false;
	}

	private void select(int index) {
		if (index != selected) {
			selected = index;
			onSelect.accept(index);
		}
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
		if (selected >= 0 && selected < options.size()) {
			narrationElementOutput.add(NarratedElementType.TITLE, CommonComponents.optionNameValue(getMessage(), options.get(selected)));
		}
	}

}
