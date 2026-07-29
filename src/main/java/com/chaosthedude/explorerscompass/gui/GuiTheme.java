package com.chaosthedude.explorerscompass.gui;

import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.chaosthedude.explorerscompass.util.RenderUtils;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * The colours and the few shared pieces of chrome every screen of the compass is drawn out of.
 * Keeping them here rather than in each screen is what makes the selection list, the bookmarks and
 * the panels read as one interface instead of several.
 */
@OnlyIn(Dist.CLIENT)
public class GuiTheme {

	/** The one colour that marks whatever the interface wants attention drawn to. */
	public static final int ACCENT = 0xFFC24B;
	public static final int ACCENT_DIM = 0x80FFC24B;

	public static final int TEXT_PRIMARY = 0xFFFFFF;
	public static final int TEXT_SECONDARY = 0xA0A6AD;
	public static final int TEXT_MUTED = 0x6E747A;
	public static final int TEXT_DISABLED = 0x707070;
	public static final int TEXT_WARNING = 0xE07A5F;
	public static final int TEXT_SUCCESS = 0x8FCB81;

	public static final int PANEL_TOP = 0xB2101216;
	public static final int PANEL_BOTTOM = 0xB2070809;
	public static final int PANEL_BORDER = 0x30FFFFFF;

	public static final int HEADER_TOP = 0xC0141721;
	public static final int HEADER_BOTTOM = 0x900B0D12;

	public static final int CHIP_BACKGROUND = 0x40FFFFFF;
	public static final int CHIP_ACCENT_BACKGROUND = 0x40FFC24B;

	public static final int ROW_HOVER = 0x28FFFFFF;
	public static final int ROW_SELECTED_LEFT = 0x66FFC24B;
	public static final int ROW_SELECTED_RIGHT = 0x10FFC24B;
	public static final int ROW_MULTI_SELECTED = 0x28FFC24B;
	public static final int ROW_SEPARATOR = 0x18FFFFFF;
	/** Closes off the favorites and recent searches held at the top of a list. */
	public static final int ROW_SECTION_SEPARATOR = 0x66FFC24B;

	public static final int SCROLLBAR_TRACK = 0x40000000;
	public static final int SCROLLBAR_THUMB = 0x99FFFFFF;

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

	public static void drawHeader(int screenWidth) {
		if (ConfigHandler.CLIENT.guiHeaderBackground.get()) {
			RenderUtils.drawVerticalGradient(0, 0, screenWidth, HEADER_HEIGHT, HEADER_TOP, HEADER_BOTTOM);
		}
		// The rule under the header stays either way: it is what separates the title and the filter
		// from the list below them
		RenderUtils.drawRect(0, HEADER_HEIGHT - 1, screenWidth, HEADER_HEIGHT, PANEL_BORDER);
	}

	public static void drawSidebar(int screenHeight) {
		drawScreenPanel(SIDEBAR_X, HEADER_HEIGHT + 6, SIDEBAR_X + SIDEBAR_WIDTH, screenHeight - 6, ConfigHandler.CLIENT.guiSidebarBackground.get());
	}

	/**
	 * One of the panels a screen is laid out on. Filled in by default, and left outlined but see
	 * through where the player asked to keep the world behind that part of the screen in view.
	 */
	public static void drawScreenPanel(int left, int top, int right, int bottom, boolean filled) {
		if (filled) {
			RenderUtils.drawPanel(left, top, right, bottom, PANEL_TOP, PANEL_BOTTOM, PANEL_BORDER);
		} else {
			RenderUtils.drawInnerOutline(left, top, right, bottom, PANEL_BORDER);
		}
	}

	/** The title of a screen, with the number of things it is showing beside it. */
	public static void drawTitle(GuiGraphics guiGraphics, Font font, String title, String subtitle, int x, int y) {
		guiGraphics.drawString(font, title, x, y, TEXT_PRIMARY, true);
		if (subtitle != null && !subtitle.isEmpty()) {
			guiGraphics.drawString(font, subtitle, x, y + font.lineHeight + 2, TEXT_MUTED, false);
		}
	}

}
