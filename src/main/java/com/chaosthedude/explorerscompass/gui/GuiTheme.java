package com.chaosthedude.explorerscompass.gui;

import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.chaosthedude.explorerscompass.util.RenderUtils;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * The colours and the few shared pieces of chrome every screen of the compass is drawn out of.
 * Keeping them here rather than in each screen is what makes the selection list, the bookmarks and
 * the panels read as one interface instead of several.
 *
 * <p>The surfaces are a dark, slightly blue grey in steps, the deepest for the panels and the lighter
 * ones for what stands on them, with one warm accent for whatever the interface wants attention
 * drawn to. Everything is drawn rounded and anti-aliased through {@link UiShapes}.
 */
@OnlyIn(Dist.CLIENT)
public class GuiTheme {

	/** The one colour that marks whatever the interface wants attention drawn to. */
	public static final int ACCENT = 0xFFC24B;
	public static final int ACCENT_DIM = 0x80FFC24B;
	/** The accent under the pointer, a step lighter. */
	public static final int ACCENT_HOVER = 0xFFD47A;
	/** The accent as text that has to stay readable on a dark surface, such as a tooltip's title. */
	public static final int ACCENT_LIGHT = 0xFFE2A6;
	/** Text standing on a fill of the accent itself. */
	public static final int ACCENT_INK = 0x2A1E06;

	public static final int TEXT_PRIMARY = 0xF0F6FC;
	public static final int TEXT_SECONDARY = 0xB1BAC4;
	public static final int TEXT_MUTED = 0x8B949E;
	public static final int TEXT_DISABLED = 0x6E7681;
	public static final int TEXT_WARNING = 0xE07A5F;
	public static final int TEXT_SUCCESS = 0x3FB950;
	/** Whatever is wrong, or about to throw something away. */
	public static final int TEXT_DANGER = 0xF85149;

	public static final int PANEL_TOP = 0xD8161B22;
	public static final int PANEL_BOTTOM = 0xD80D1117;
	public static final int PANEL_BORDER = 0x2EFFFFFF;

	public static final int HEADER_TOP = 0xE0161B22;
	public static final int HEADER_BOTTOM = 0xC00D1117;

	/** What a control stands on: a step lighter than the panel under it. */
	public static final int WIDGET_BACKGROUND = 0xB021262D;
	public static final int WIDGET_BACKGROUND_HOVERED = 0xD830363D;
	public static final int WIDGET_BORDER = 0xC030363D;
	/** The well a field is typed into, a step darker than the panel instead. */
	public static final int INPUT_BACKGROUND = 0xC80D1117;

	public static final int CHIP_BACKGROUND = 0x388B949E;
	public static final int CHIP_ACCENT_BACKGROUND = 0x38FFC24B;

	public static final int ROW_HOVER = 0x16FFFFFF;
	/** Kept for the gradient a few older panels still fade a selection out with. */
	public static final int ROW_SELECTED_LEFT = 0x66FFC24B;
	public static final int ROW_SELECTED_RIGHT = 0x10FFC24B;
	public static final int ROW_SELECTED = 0x2CFFC24B;
	public static final int ROW_MULTI_SELECTED = 0x1AFFC24B;
	public static final int ROW_SEPARATOR = 0x12FFFFFF;
	/** Closes off the favorites and recent searches held at the top of a list. */
	public static final int ROW_SECTION_SEPARATOR = 0x55FFC24B;

	public static final int SCROLLBAR_TRACK = 0x30000000;
	public static final int SCROLLBAR_THUMB = 0xFF484F58;
	public static final int SCROLLBAR_THUMB_HOVERED = 0xFF6E7681;

	public static final int TOOLTIP_BACKGROUND = 0xFF161B22;
	public static final int TOOLTIP_BORDER = 0xA048505A;
	public static final int SHADOW = 0x50000000;

	/** How round the corners of the panels a screen is laid out on are. */
	public static final float PANEL_RADIUS = 6.0F;
	/** How round the corners of a card, a tooltip or a floating panel are. */
	public static final float CARD_RADIUS = 5.0F;
	/** How round the corners of a button, a field or a highlighted row are. */
	public static final float CONTROL_RADIUS = 4.0F;

	/** Height of the strip along the top that holds the title, the filter field and the filter chips. */
	public static final int HEADER_HEIGHT = 44;

	public static final int SIDEBAR_X = 6;
	public static final int SIDEBAR_WIDTH = 116;
	/** Where the column of controls in the sidebar begins. */
	public static final int SIDEBAR_CONTENT_X = SIDEBAR_X + 4;
	public static final int SIDEBAR_CONTENT_WIDTH = SIDEBAR_WIDTH - 8;
	/**
	 * Slightly shorter than a vanilla button, so that a full sidebar still clears the control at the
	 * bottom of it on the shortest screen the game scales itself down to.
	 */
	public static final int BUTTON_HEIGHT = 18;
	public static final int BUTTON_SPACING = 20;

	private GuiTheme() {
	}

	/** Where the list, and everything else that is not the sidebar, begins. */
	public static int contentLeft() {
		return SIDEBAR_X + SIDEBAR_WIDTH + 8;
	}

	public static int contentWidth(int screenWidth) {
		return Math.max(80, screenWidth - contentLeft() - 8);
	}

	public static void drawHeader(GuiGraphics guiGraphics, int screenWidth) {
		if (ConfigHandler.CLIENT.guiHeaderBackground.get()) {
			RenderUtils.drawVerticalGradient(guiGraphics, 0, 0, screenWidth, HEADER_HEIGHT, HEADER_TOP, HEADER_BOTTOM);
		}
		// The rule under the header stays either way: it is what separates the title and the filter
		// from the list below them
		UiShapes.hairline(guiGraphics, 0, screenWidth, HEADER_HEIGHT - UiShapes.pixel(), PANEL_BORDER);
	}

	public static void drawSidebar(GuiGraphics guiGraphics, int screenHeight) {
		drawScreenPanel(guiGraphics, SIDEBAR_X, HEADER_HEIGHT + 6, SIDEBAR_X + SIDEBAR_WIDTH, screenHeight - 6, ConfigHandler.CLIENT.guiSidebarBackground.get());
	}

	/**
	 * One of the panels a screen is laid out on. Filled in by default, and left outlined but see
	 * through where the player asked to keep the world behind that part of the screen in view.
	 */
	public static void drawScreenPanel(GuiGraphics guiGraphics, int left, int top, int right, int bottom, boolean filled) {
		if (filled) {
			UiShapes.roundRect(guiGraphics, left, top, right - left, bottom - top, PANEL_RADIUS, PANEL_TOP, PANEL_BOTTOM);
		}
		UiShapes.roundRectStroke(guiGraphics, left, top, right - left, bottom - top, PANEL_RADIUS, UiShapes.stroke(), PANEL_BORDER);
	}

	/**
	 * A panel floating over the rest of the screen, such as a list dropped down from a button: lifted
	 * off what it covers by a shadow, and filled more solidly than the panels the screen stands on.
	 */
	public static void drawFloatingPanel(GuiGraphics guiGraphics, int left, int top, int right, int bottom) {
		final int w = right - left;
		final int h = bottom - top;
		UiShapes.shadow(guiGraphics, left, top + 1, w, h, CARD_RADIUS, 6.0F, SHADOW);
		UiShapes.roundRect(guiGraphics, left, top, w, h, CARD_RADIUS, 0xFF161B22, 0xFF0D1117);
		UiShapes.roundRectStroke(guiGraphics, left, top, w, h, CARD_RADIUS, UiShapes.stroke(), withAlpha(0x48505A, 0xC0));
	}

	/** The title of a screen, with the number of things it is showing beside it. */
	public static void drawTitle(GuiGraphics guiGraphics, Font font, String title, String subtitle, int x, int y) {
		guiGraphics.drawString(font, title, x, y, TEXT_PRIMARY, true);
		if (subtitle != null && !subtitle.isEmpty()) {
			guiGraphics.drawString(font, subtitle, x, y + font.lineHeight + 2, TEXT_MUTED, false);
		}
	}

	/**
	 * The band behind a row that is pointed at or picked, rounded and inset from the edges of the
	 * list. A row that is picked carries a short bar of the accent along its leading edge as well.
	 *
	 * @param hover    how far the pointer's highlight has faded in, from 0 to 1
	 * @param selected how far the selection has faded in, from 0 to 1
	 */
	public static void drawRowBand(GuiGraphics guiGraphics, float left, float top, float right, float bottom, float hover, float selected, boolean multiSelected) {
		final float w = right - left;
		final float h = bottom - top;
		if (multiSelected) {
			UiShapes.roundRect(guiGraphics, left, top, w, h, CONTROL_RADIUS, ROW_MULTI_SELECTED);
			UiShapes.pill(guiGraphics, left + 2, top + 4, 2.5F, h - 8, ACCENT_DIM);
		}
		if (hover > 0.01F) {
			UiShapes.roundRect(guiGraphics, left, top, w, h, CONTROL_RADIUS, scaleAlpha(ROW_HOVER, hover));
		}
		if (selected > 0.01F) {
			UiShapes.roundRect(guiGraphics, left, top, w, h, CONTROL_RADIUS, scaleAlpha(ROW_SELECTED, selected));
			UiShapes.pill(guiGraphics, left + 2, top + 4, 2.5F, h - 8, scaleAlpha(0xFF000000 | ACCENT, selected));
		}
	}

	/** A slim rounded scrollbar standing in its track. */
	public static void drawScrollbar(GuiGraphics guiGraphics, float x, float top, float bottom, float width, float thumbTop, float thumbHeight, boolean hovered) {
		UiShapes.pill(guiGraphics, x, top, width, bottom - top, SCROLLBAR_TRACK);
		UiShapes.pill(guiGraphics, x, thumbTop, width, thumbHeight, hovered ? SCROLLBAR_THUMB_HOVERED : SCROLLBAR_THUMB);
	}

	/** The given colour with its alpha replaced. */
	public static int withAlpha(int color, int alpha) {
		return (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, alpha)) << 24);
	}

	/** The given colour with its alpha scaled by a fraction, as a fade does to it. */
	public static int scaleAlpha(int color, float fraction) {
		return withAlpha(color, Math.round((color >>> 24) * Math.max(0.0F, Math.min(1.0F, fraction))));
	}

	/** A colour given as red, green and blue, made fully opaque. */
	public static int opaque(int rgb) {
		return 0xFF000000 | rgb;
	}

	/** The colour the given fraction of the way from one colour to another, alpha included. */
	public static int lerpColor(int from, int to, float t) {
		t = Math.max(0.0F, Math.min(1.0F, t));
		final int a = (int) (((from >>> 24) & 0xFF) + (((to >>> 24) & 0xFF) - ((from >>> 24) & 0xFF)) * t);
		final int r = (int) (((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
		final int g = (int) (((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
		final int b = (int) ((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
		return (a << 24) | (r << 16) | (g << 8) | b;
	}

}
