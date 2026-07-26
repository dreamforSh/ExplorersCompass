package com.chaosthedude.explorerscompass.gui;

import java.util.Collections;
import java.util.List;

import com.chaosthedude.explorerscompass.util.RenderUtils;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * A flat button drawn without the vanilla texture, so that it sits on the compass panels rather
 * than on top of them. What it is doing shows in three ways at once: the fill, the border, and the
 * bar along its leading edge, which is what makes a disabled button distinguishable from an
 * enabled one at a glance.
 */
@OnlyIn(Dist.CLIENT)
public class TransparentButton extends Button {

	private static final int BACKGROUND = 0x59000000;
	private static final int BACKGROUND_HOVERED = 0x99000000;
	private static final int BACKGROUND_DISABLED = 0x33000000;
	private static final int BORDER = 0x26FFFFFF;
	private static final int BORDER_HOVERED = 0x99FFC24B;
	private static final int BORDER_HIGHLIGHTED = 0x59FFC24B;

	/** Whether this button stands for a setting that is currently switched on. */
	private boolean highlighted;
	private List<Component> tooltipLines = Collections.emptyList();

	public TransparentButton(int x, int y, int width, int height, Component label, OnPress onPress) {
		super(x, y, width, height, label, onPress);
	}

	public TransparentButton setHighlighted(boolean highlighted) {
		this.highlighted = highlighted;
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

	@Override
	public void renderButton(PoseStack poseStack, int mouseX, int mouseY, float partialTicks) {
		if (!visible) {
			return;
		}

		final Minecraft mc = Minecraft.getInstance();
		final int background = !active ? BACKGROUND_DISABLED : (isHoveredOrFocused() ? BACKGROUND_HOVERED : BACKGROUND);
		RenderUtils.drawRect(x, y, x + width, y + height, background);

		final int border = !active ? BORDER : (isHoveredOrFocused() ? BORDER_HOVERED : (highlighted ? BORDER_HIGHLIGHTED : BORDER));
		RenderUtils.drawInnerOutline(x, y, x + width, y + height, border);

		// The leading edge carries the state that matters most: what is switched on, and what is being
		// pointed at, without either of them having to be read off the label
		if (active && (highlighted || isHoveredOrFocused())) {
			RenderUtils.drawRect(x, y, x + 2, y + height, isHoveredOrFocused() ? GuiTheme.ACCENT | 0xFF000000 : GuiTheme.ACCENT_DIM);
		}

		final int textColor = !active ? GuiTheme.TEXT_DISABLED : (highlighted ? GuiTheme.ACCENT : GuiTheme.TEXT_PRIMARY);
		// Labels grow with translation and with the values appended to them, so they are shortened
		// rather than allowed to run past the edges of the button
		final String label = RenderUtils.trimToWidth(getMessage().getString(), width - 10);
		drawCenteredString(poseStack, mc.font, label, x + width / 2 + 1, y + (height - 8) / 2, textColor);
	}

}
