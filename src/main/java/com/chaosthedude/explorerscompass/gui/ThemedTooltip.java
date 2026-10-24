package com.chaosthedude.explorerscompass.gui;

import java.util.ArrayList;
import java.util.List;

import com.chaosthedude.explorerscompass.ExplorersCompass;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.event.RenderTooltipEvent;

/**
 * The tooltip every screen of the compass explains its controls and its rows with: a rounded card
 * lifted off the screen by a soft shadow, its first line set apart as a title in the accent and the
 * rest of it in a quieter grey, wrapped at a readable width and flipped to whichever side of the
 * pointer it fits on.
 *
 * <p>A line that carries a colour of its own keeps it, so a warning still reads as one; the title and
 * body colours only apply to text that has none. An item's tooltip is left to the game, so that a
 * mod restyling item tooltips restyles the ones on these screens too, and only takes the colours of
 * this one, through {@link #onTooltipColor}.
 */
@OnlyIn(Dist.CLIENT)
public final class ThemedTooltip {

	private static final int PADDING_X = 6;
	private static final int PADDING_Y = 5;
	private static final int LINE_HEIGHT = 10;
	/** Room above and below the rule under the title, counted from where the next row would start. */
	private static final int RULE_ABOVE = 1;
	private static final int RULE_BELOW = 4;
	private static final int MOUSE_OFFSET = 12;
	private static final int SCREEN_MARGIN = 4;
	/** The height the game draws its own tooltips at, in front of items and of everything else. */
	private static final float Z = 400.0F;

	private ThemedTooltip() {
	}

	/** Draws the lines as a tooltip at the pointer, with the first of them as its title. */
	public static void render(GuiGraphics guiGraphics, Font font, List<? extends Component> lines, int mouseX, int mouseY) {
		render(guiGraphics, font, lines, true, mouseX, mouseY);
	}

	/** Draws the tooltip of a button, headed with the button's name and explained by its lines. */
	public static void render(GuiGraphics guiGraphics, Font font, TransparentButton button, int mouseX, int mouseY) {
		final Component title = button.getTooltipTitle();
		if (title == null) {
			render(guiGraphics, font, button.getTooltipLines(), mouseX, mouseY);
			return;
		}
		final List<Component> lines = new ArrayList<Component>(button.getTooltipLines().size() + 1);
		lines.add(title);
		lines.addAll(button.getTooltipLines());
		render(guiGraphics, font, lines, mouseX, mouseY);
	}

	/**
	 * Draws the lines as a tooltip at the pointer.
	 *
	 * @param titled whether the first line is a title, set apart from the rest by colour and a rule
	 */
	public static void render(GuiGraphics guiGraphics, Font font, List<? extends Component> lines, boolean titled, int mouseX, int mouseY) {
		if (lines == null || lines.isEmpty()) {
			return;
		}
		final int screenWidth = guiGraphics.guiWidth();
		final int screenHeight = guiGraphics.guiHeight();
		final int wrapWidth = Math.max(140, Math.min(260, screenWidth / 2 - MOUSE_OFFSET - PADDING_X * 2));

		final List<FormattedCharSequence> rows = new ArrayList<FormattedCharSequence>();
		int titleRows = 0;
		for (int i = 0; i < lines.size(); i++) {
			final List<FormattedCharSequence> split = font.split(lines.get(i), wrapWidth);
			rows.addAll(split.isEmpty() ? List.of(FormattedCharSequence.EMPTY) : split);
			if (i == 0 && titled) {
				titleRows = rows.size();
			}
		}
		final boolean ruled = titleRows > 0 && rows.size() > titleRows;

		int textWidth = 0;
		for (FormattedCharSequence row : rows) {
			textWidth = Math.max(textWidth, font.width(row));
		}
		final int width = textWidth + PADDING_X * 2;
		final int height = rows.size() * LINE_HEIGHT - 2 + PADDING_Y * 2 + (ruled ? RULE_ABOVE + RULE_BELOW : 0);

		// To the right of the pointer where it fits there, and to the left where it does not
		int x = mouseX + MOUSE_OFFSET;
		if (x + width > screenWidth - SCREEN_MARGIN) {
			x = mouseX - MOUSE_OFFSET - width;
		}
		x = Math.max(SCREEN_MARGIN, Math.min(x, screenWidth - SCREEN_MARGIN - width));
		int y = mouseY - MOUSE_OFFSET;
		y = Math.max(SCREEN_MARGIN, Math.min(y, screenHeight - SCREEN_MARGIN - height));

		guiGraphics.pose().pushPose();
		guiGraphics.pose().translate(0.0F, 0.0F, Z);
		UiShapes.shadow(guiGraphics, x, y + 1, width, height, GuiTheme.CARD_RADIUS, 5.0F, GuiTheme.SHADOW);
		UiShapes.roundRect(guiGraphics, x, y, width, height, GuiTheme.CARD_RADIUS, GuiTheme.TOOLTIP_BACKGROUND);
		UiShapes.roundRectStroke(guiGraphics, x, y, width, height, GuiTheme.CARD_RADIUS, UiShapes.stroke(), GuiTheme.TOOLTIP_BORDER);

		int textY = y + PADDING_Y;
		for (int i = 0; i < rows.size(); i++) {
			if (ruled && i == titleRows) {
				textY += RULE_ABOVE;
				UiShapes.hairline(guiGraphics, x + PADDING_X, x + width - PADDING_X, textY, GuiTheme.PANEL_BORDER);
				textY += RULE_BELOW;
			}
			final int color = i < titleRows ? GuiTheme.ACCENT_LIGHT : GuiTheme.TEXT_SECONDARY;
			guiGraphics.drawString(font, rows.get(i), x + PADDING_X, textY, color, false);
			textY += LINE_HEIGHT;
		}
		guiGraphics.pose().popPose();
	}

	/**
	 * Gives an item's tooltip the colours of this one while it is drawn over a screen of the compass,
	 * leaving how it is laid out and drawn to the game and to whatever mod restyles it.
	 */
	public static void onTooltipColor(RenderTooltipEvent.Color event) {
		if (!isCompassScreen(Minecraft.getInstance().screen)) {
			return;
		}
		event.setBackgroundStart(0xF4161B22);
		event.setBackgroundEnd(0xF40D1117);
		event.setBorderStart(GuiTheme.withAlpha(GuiTheme.ACCENT, 0x90));
		event.setBorderEnd(GuiTheme.withAlpha(GuiTheme.ACCENT, 0x28));
	}

	private static boolean isCompassScreen(Screen screen) {
		return screen != null && screen.getClass().getName().startsWith(ExplorersCompass.class.getPackageName() + ".");
	}

}
