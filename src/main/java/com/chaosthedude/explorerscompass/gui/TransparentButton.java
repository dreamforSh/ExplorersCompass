package com.chaosthedude.explorerscompass.gui;

import java.util.Collections;
import java.util.List;

import com.chaosthedude.explorerscompass.util.RenderUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * A flat, rounded button drawn without the vanilla texture, so that it sits on the compass panels
 * rather than on top of them. What it is doing shows in three ways at once: the fill, the border,
 * and the bar along its leading edge, which is what makes a setting that is switched on
 * distinguishable from one that is not at a glance. Hovering fades in rather than snapping, and a
 * button that is switched on slides its bar in the same way.
 *
 * <p>A primary button is filled with the accent, for the one action a screen is mostly there for; a
 * danger button lights up in red rather than in the accent, for one that throws something away.
 */
@OnlyIn(Dist.CLIENT)
public class TransparentButton extends Button {

	/** Whether this button stands for a setting that is currently switched on. */
	private boolean highlighted;
	private boolean primary;
	private boolean danger;
	private List<Component> tooltipLines = Collections.emptyList();
	private Component tooltipTitle;
	private final UiAnim.Value hoverAnim = new UiAnim.Value();
	private final UiAnim.Value highlightAnim = new UiAnim.Value();

	public TransparentButton(int x, int y, int width, int height, Component label, OnPress onPress) {
		// The button is now built through a builder, and the constructor behind it also wants to be
		// told how to narrate itself; the default is the same narration the old constructor supplied
		super(x, y, width, height, label, onPress, DEFAULT_NARRATION);
	}

	public TransparentButton setHighlighted(boolean highlighted) {
		this.highlighted = highlighted;
		return this;
	}

	/** Fills the button with the accent, for the action the screen is mostly there for. */
	public TransparentButton setPrimary(boolean primary) {
		this.primary = primary;
		return this;
	}

	/** Lights the button up in red rather than in the accent, for an action that throws something away. */
	public TransparentButton setDanger(boolean danger) {
		this.danger = danger;
		return this;
	}

	public TransparentButton setTooltipLines(Component... lines) {
		tooltipLines = List.of(lines);
		return this;
	}

	/** What to explain about this button while the pointer rests on it, empty when there is nothing to. */
	public List<Component> getTooltipLines() {
		return tooltipLines;
	}

	/** Names the button at the head of its tooltip, for a button whose label is a glyph rather than a word. */
	public TransparentButton setTooltipTitle(Component title) {
		tooltipTitle = title;
		return this;
	}

	/**
	 * What the tooltip is headed with: the name given for it, or else the button's own label, unless
	 * that label is a lone glyph, which names nothing, and the tooltip's first line heads it instead.
	 */
	public Component getTooltipTitle() {
		if (tooltipTitle != null) {
			return tooltipTitle;
		}
		final String label = getMessage().getString().strip();
		return label.codePointCount(0, label.length()) <= 1 ? null : getMessage();
	}

	/**
	 * Whether this button is the one being pointed at, and so the one to pick out and to explain.
	 *
	 * <p>Being focused is not the same thing and cannot stand in for it. A click leaves the focus on
	 * whatever it landed on and nothing takes it away again, so a button asked whether it is hovered
	 * or focused answers yes for the rest of the screen's life once it has been pressed: it stays lit
	 * as though the pointer were still on it, and it goes on explaining itself over everything else.
	 * Focus is only worth showing when it arrived by keyboard, there being no pointer to follow then,
	 * which is the same rule the game applies to its own widgets.
	 */
	public boolean isPointedAt() {
		return isHovered() || (isFocused() && Minecraft.getInstance().getLastInputType().isKeyboard());
	}

	@Override
	public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		if (!visible) {
			return;
		}

		final Minecraft mc = Minecraft.getInstance();
		final int x = getX();
		final int y = getY();
		final float hover = hoverAnim.hover(active && isPointedAt());
		final float lit = highlightAnim.approach(active && highlighted ? 1.0F : 0.0F, UiAnim.SLIDE_SPEED);
		final float radius = GuiTheme.CONTROL_RADIUS;
		final int tint = danger ? GuiTheme.TEXT_DANGER : GuiTheme.ACCENT;

		int textColor;
		if (primary && active) {
			UiShapes.roundRect(guiGraphics, x, y, width, height, radius, GuiTheme.lerpColor(GuiTheme.opaque(GuiTheme.ACCENT), GuiTheme.opaque(GuiTheme.ACCENT_HOVER), hover));
			textColor = GuiTheme.ACCENT_INK;
		} else {
			final int background = !active ? GuiTheme.scaleAlpha(GuiTheme.WIDGET_BACKGROUND, 0.5F) : GuiTheme.lerpColor(GuiTheme.WIDGET_BACKGROUND, GuiTheme.WIDGET_BACKGROUND_HOVERED, hover);
			UiShapes.roundRect(guiGraphics, x, y, width, height, radius, background);
			if (lit > 0.01F) {
				UiShapes.roundRect(guiGraphics, x, y, width, height, radius, GuiTheme.withAlpha(GuiTheme.ACCENT, Math.round(0x22 * lit)));
				// The leading edge carries what is switched on, without it having to be read off the label
				UiShapes.pill(guiGraphics, x + 2, y + 4, 2.5F, height - 8, GuiTheme.withAlpha(GuiTheme.ACCENT, Math.round(0xFF * lit)));
			}
			final int restingBorder = GuiTheme.lerpColor(GuiTheme.WIDGET_BORDER, GuiTheme.withAlpha(GuiTheme.ACCENT, 0x70), lit);
			final int border = !active ? GuiTheme.scaleAlpha(GuiTheme.WIDGET_BORDER, 0.5F) : GuiTheme.lerpColor(restingBorder, GuiTheme.withAlpha(tint, 0xD0), hover);
			UiShapes.roundRectStroke(guiGraphics, x, y, width, height, radius, UiShapes.stroke(), border);
			textColor = !active ? GuiTheme.TEXT_DISABLED : GuiTheme.lerpColor(GuiTheme.lerpColor(GuiTheme.TEXT_PRIMARY, GuiTheme.ACCENT, lit), danger ? GuiTheme.TEXT_DANGER : GuiTheme.TEXT_PRIMARY, danger ? hover : 0.0F);
		}

		// Labels grow with translation and with the values appended to them, so they are shortened
		// rather than allowed to run past the edges of the button
		final String label = RenderUtils.trimToWidth(getMessage().getString(), width - 10);
		final int textX = x + (width - mc.font.width(label)) / 2 + (lit > 0.5F && !primary ? 1 : 0);
		guiGraphics.drawString(mc.font, label, textX, y + (height - 8) / 2, textColor, false);
	}

}
