package com.chaosthedude.explorerscompass.gui;

import com.chaosthedude.explorerscompass.util.LootTableNames;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * How a loot table's drops are drawn, wherever they are drawn: the card beside a badge and the
 * overview of a whole structure both lay items out as tiles, and a tile has to look the same in
 * both for the two to read as one interface. The small pieces both build their headings from, the
 * count pill, the key cap, the hairline, the tally and the tier group header, live here for the
 * same reason, along with the colours they are all drawn in.
 *
 * <p>A tile is a slot with the item in it, how many come at once in the corner where the game
 * writes a stack's size, and the chance written underneath at full size, so that neither has to be
 * squinted at. Every slot is filled alike, and the tier is told once, by its icon in the slot's top
 * left corner, so that a full chest reads as items rather than as a patchwork. Every tier has an
 * icon of its own shape, the common one included, so that a tile says its tier the same way
 * whichever tier it is, without its colour; a rare item's slot is outlined as well, so that the one
 * thing worth finding stands out among the bread. Where a group header already names the tier, the
 * tiles under it go unmarked.
 *
 * <p>A tile writes a count only where every stack comes the same size, since a range does not fit
 * a corner and its top alone would promise more than most stacks hold; the tooltip gives the
 * range. A certain item needs no label: its check says it, and a row of nothing else is drawn
 * shorter. Labels are a quiet grey, and only a rare chance is written in the tier's violet, so
 * that the numbers do not compete with the items. An item that comes enchanted is drawn with the
 * glint the game gives an enchanted item, and a bottle as the potion in it, so that the strong
 * regeneration a vault rewards does not draw as a bottle of water.
 */
@OnlyIn(Dist.CLIENT)
final class LootSlots {

	static final int SLOT = 18;
	/** How wide a tile is: a slot with room either side, so that the labels of neighbours keep a few pixels apart. */
	static final int TILE_W = 24;
	/** How far in from the left of its tile a slot is. */
	static final int SLOT_INSET_X = 3;
	/** How tall a row of tiles is with labels under its slots: the slot, a pixel, a line for the chance, and room after it. */
	static final int ROW_LABELLED = 29;
	/** How tall a row of tiles is where none of them has a label to show: the slot and the same room after it. */
	static final int ROW_PLAIN = 21;
	/** How tall a tier's group header is, with the gap under it before the group's first row. */
	static final int GROUP_HEAD = 13;
	/** The space left between one tier's group and the next, on top of the room every row already leaves after itself. */
	static final int BAND_GAP = 1;
	/** How far in front of the interface an item is drawn, and a little more, so that what is laid over one shows. */
	static final float OVER_ITEMS = 200.0F;
	/** How tall a count pill is. */
	static final int PILL_HEIGHT = 11;
	/** How tall a key cap is, its lip included. */
	static final int KEYCAP_HEIGHT = 11;

	/** The top of the gradient behind the card and the overview, the surfaces every floating panel is drawn in. */
	static final int PANEL_TOP = 0xFC1A1F27;
	/** The bottom of the gradient behind the card and the overview. */
	static final int PANEL_BOTTOM = 0xFC0E1116;
	/** The outline just inside the card and the overview. */
	static final int PANEL_BORDER = 0xC048505A;
	/** How round a slot's corners are, a little less than a control's, since a slot is smaller than one. */
	static final float SLOT_RADIUS = 3.0F;
	/** The bar down the left edge of a panel, in the colour that says this is the mod's. */
	static final int ACCENT_BAR = 0xFF000000 | GuiTheme.ACCENT;
	/** The faint rule under a header, between sections, and under the overview's controls, a screen pixel thick. */
	static final int HAIRLINE = 0x30FFFFFF;
	/** What every slot is filled with, and the plate behind the card's icon. */
	static final int SLOT_FILL = 0x1AFFFFFF;
	/** The rim just inside a slot, which keeps a slot readable as one behind a light item. */
	static final int SLOT_RIM = 0x14FFFFFF;
	/** The outline just inside a rare item's slot, and the faint glow of its colour filling it. */
	static final int RARE_OUTLINE = 0xC0D08CFF;
	static final int RARE_GLOW = 0x20D08CFF;
	/** The light laid over a slot under the pointer, neutral, so that a lit tile is not taken for a tier. */
	static final int HOVER_FILL = 0x30FFFFFF;
	static final int HOVER_OUTLINE = 0xE6FFFFFF;
	static final int MORE_FILL = 0xC00D1117;
	/** What a count pill is filled with. */
	static final int PILL_BG = 0x22FFFFFF;
	/** A segmented control's part, a tab or a chip at rest. */
	static final int SEG_TRACK = 0x14FFFFFF;
	/** A segmented control's part, a tab or a chip that is picked. */
	static final int SEG_ON = 0x30FFFFFF;
	/** A segmented control's part, a tab or a chip under the pointer. */
	static final int SEG_HOVER = 0x24FFFFFF;
	/** How opaque the tier's colour is in a picked tier chip's fill, for {@link #tint}. */
	static final int CHIP_PICKED_ALPHA = 0x30;
	/** The overview's close box at rest; under the pointer it reddens. */
	static final int CLOSE_BG = 0x1CFFFFFF;
	/** How opaque the tier's colour is in the rule across a tier group header, for {@link #tint}. */
	static final int GROUP_RULE_ALPHA = 0x60;
	/** The track a tooltip's meter fills. */
	static final int TIP_TRACK = 0x2EFFFFFF;
	/** The rule between a tooltip's parts. */
	static final int TIP_RULE = 0x40FFFFFF;
	/** What a key cap is filled with, and the darker lip under it that makes it read as a key. */
	static final int KEYCAP_FILL = 0x3AFFFFFF;
	static final int KEYCAP_LIP = 0x70000000;
	/** The count is white with a shadow, the way the game writes a stack's size, so that it reads as one. */
	static final int COUNT_COLOUR = 0xFFFFFFFF;
	/** The most hidden items the tile that stands for them counts, so that the count fits in the slot. */
	private static final int MORE_MAX = 99;
	/** How far right of a tally's icon its number starts. */
	private static final int TALLY_ICON_STEP = 8;
	/** How far the next icon of a tally is from the number before it. */
	private static final int TALLY_GAP = 6;
	/** How far right of a group header's icon the tier's name starts. */
	private static final int HEADER_NAME_X = 12;
	/** The room kept between the text of a group header and its rule. */
	private static final int HEADER_RULE_GAP = 5;
	/** The room kept between the rule of a group header and its pill. */
	private static final int HEADER_PILL_GAP = 4;

	private LootSlots() {
	}

	/**
	 * Draws one tile with its top left corner at the given point: the slot, lit where the pointer is
	 * on it and outlined where the item is rare, the item in it, its tier's icon in the corner where
	 * marks are asked for, how many come at once where that never changes, and the chance under it
	 * unless the item is certain.
	 *
	 * <p>The game draws an item a long way in front of the slot it sits in, so the corner icon and
	 * the count are pushed in front of that in turn, where the game puts a stack's size, or the item
	 * would cover them. The slot, its outline and the label stay where they are: the item is meant to
	 * stand in front of the first two, and nothing stands in front of the last. A stack this side
	 * does not know is drawn as a question mark, so that the tile still holds its place and its
	 * chance.
	 */
	static void drawTile(GuiGraphics guiGraphics, Font font, int tileX, int tileY, ItemStack stack, int labelPermille, int minCount, int maxCount, boolean lit, boolean mark) {
		final LootTier tier = LootTier.of(labelPermille);
		final int slotX = tileX + SLOT_INSET_X;
		final int slotY = tileY;
		drawSlot(guiGraphics, slotX, slotY, tier == LootTier.RARE && !lit ? RARE_GLOW : SLOT_FILL);
		if (lit) {
			UiShapes.roundRect(guiGraphics, slotX, slotY, SLOT, SLOT, SLOT_RADIUS, HOVER_FILL);
			UiShapes.roundRectStroke(guiGraphics, slotX, slotY, SLOT, SLOT, SLOT_RADIUS, 1.0F, HOVER_OUTLINE);
		} else if (tier == LootTier.RARE) {
			UiShapes.roundRectStroke(guiGraphics, slotX, slotY, SLOT, SLOT, SLOT_RADIUS, 1.0F, RARE_OUTLINE);
		}
		if (stack.isEmpty()) {
			final String unknown = "?";
			guiGraphics.drawString(font, unknown, slotX + SLOT / 2 - font.width(unknown) / 2, slotY + 5, GuiTheme.TEXT_MUTED, false);
		} else {
			guiGraphics.renderItem(stack, slotX + 1, slotY + 1);
		}
		final String count = LootMath.formatGridCount(minCount, maxCount);
		if (mark || count != null) {
			guiGraphics.pose().pushPose();
			guiGraphics.pose().translate(0.0F, 0.0F, OVER_ITEMS);
			if (mark) {
				tier.drawIcon(guiGraphics, slotX - 1, slotY - 1, true);
			}
			if (count != null) {
				guiGraphics.drawString(font, count, slotX + SLOT - font.width(count), slotY + 10, COUNT_COLOUR, true);
			}
			guiGraphics.pose().popPose();
		}
		final String label = LootMath.formatGridChance(labelPermille);
		if (label != null) {
			final int colour = tier == LootTier.RARE ? tier.colour : GuiTheme.TEXT_SECONDARY;
			guiGraphics.drawString(font, label, tileX + TILE_W / 2 - font.width(label) / 2, tileY + SLOT + 1, colour, false);
		}
	}

	/**
	 * Draws the tile that stands for the items a grid had no room for, with its top left corner at
	 * the given point: a dark slot saying how many more there are, and no label, since it holds no
	 * one chance. Lit like any other tile, since clicking it is how the rest are reached.
	 */
	static void drawMoreTile(GuiGraphics guiGraphics, Font font, int tileX, int tileY, int hidden, boolean lit) {
		final int slotX = tileX + SLOT_INSET_X;
		final int slotY = tileY;
		drawSlot(guiGraphics, slotX, slotY, MORE_FILL);
		if (lit) {
			UiShapes.roundRectStroke(guiGraphics, slotX, slotY, SLOT, SLOT, SLOT_RADIUS, 1.0F, HOVER_OUTLINE);
		}
		final String text = I18n.get("string.explorerscompass.lootMoreItems", String.valueOf(Math.min(hidden, MORE_MAX)));
		guiGraphics.drawString(font, text, slotX + SLOT / 2 - font.width(text) / 2, slotY + 5, GuiTheme.TEXT_PRIMARY, false);
	}

	/** How wide a count pill holding the given text is drawn, with room for its rounded ends. */
	static int pillWidth(Font font, String text) {
		return font.width(text) + 6;
	}

	/**
	 * Draws a count pill with its top left corner at the given point and returns how wide it is: the
	 * text in a quiet grey on a faint rounded plate, so that a count beside a heading reads as a
	 * number about the heading rather than as part of its name.
	 */
	static int drawPill(GuiGraphics guiGraphics, Font font, int x, int y, String text) {
		final int width = pillWidth(font, text);
		UiShapes.pill(guiGraphics, x, y, width, PILL_HEIGHT, PILL_BG);
		guiGraphics.drawString(font, text, x + 3, y + 2, GuiTheme.TEXT_SECONDARY, false);
		return width;
	}

	/** How wide a key cap naming the given key is drawn. */
	static int keycapWidth(Font font, Component key) {
		return font.width(key) + 5;
	}

	/**
	 * Draws a key cap with its top left corner at the given point and returns how wide it is: the
	 * key's name in white on a rounded plate with a dark lip along its bottom,
	 * so that a hint to hold or click reads as a key to press rather than as more text.
	 */
	static int drawKeycap(GuiGraphics guiGraphics, Font font, int x, int y, Component key) {
		final int width = keycapWidth(font, key);
		// The lip is the whole key a pixel lower and darker, showing only along its bottom
		UiShapes.roundRect(guiGraphics, x, y + 1, width, KEYCAP_HEIGHT - 1, 2.5F, KEYCAP_LIP);
		UiShapes.roundRect(guiGraphics, x, y, width, KEYCAP_HEIGHT - 1, 2.5F, KEYCAP_FILL);
		guiGraphics.drawString(font, key, x + 3, y + 1, GuiTheme.TEXT_PRIMARY, false);
		return width;
	}

	/** Draws a rule one screen pixel thick from the first x up to the second, in the faint colour the panels are ruled in. */
	static void drawHairline(GuiGraphics guiGraphics, int left, int right, int y) {
		drawHairline(guiGraphics, left, right, y, HAIRLINE);
	}

	/** Draws a rule one screen pixel thick from the first x up to the second in the given colour, or nothing where it would have no length. */
	static void drawHairline(GuiGraphics guiGraphics, int left, int right, int y, int colour) {
		if (right > left) {
			UiShapes.hairline(guiGraphics, left, right, y, colour);
		}
	}

	/**
	 * Draws how many drops fall in each tier, as each tier's small icon followed by its count in the
	 * tier's colour, and returns where the last count ends. Tiers with none are left out rather than
	 * shown as a nought, so that the tally says what a table holds and nothing else. The counts are
	 * given in the order of {@link LootTier#values()}.
	 */
	static int drawTally(GuiGraphics guiGraphics, Font font, int x, int y, int[] countsByTier) {
		int cursor = x;
		int end = x;
		for (LootTier tier : LootTier.values()) {
			final int count = tier.ordinal() < countsByTier.length ? countsByTier[tier.ordinal()] : 0;
			if (count <= 0) {
				continue;
			}
			tier.drawIcon(guiGraphics, cursor, y, true);
			cursor += TALLY_ICON_STEP;
			final String text = String.valueOf(count);
			guiGraphics.drawString(font, text, cursor, y, tier.colour, false);
			end = cursor + font.width(text);
			cursor = end + TALLY_GAP;
		}
		return end;
	}

	/**
	 * How wide {@link #drawTally} draws the given counts, from the first icon to the end of the last
	 * count, so that a panel can make room for its tally before drawing it.
	 */
	static int tallyWidth(Font font, int[] countsByTier) {
		int width = 0;
		for (LootTier tier : LootTier.values()) {
			final int count = tier.ordinal() < countsByTier.length ? countsByTier[tier.ordinal()] : 0;
			if (count > 0) {
				width += (width > 0 ? TALLY_GAP : 0) + TALLY_ICON_STEP + font.width(String.valueOf(count));
			}
		}
		return width;
	}

	/**
	 * Draws the header a tier's group starts with, its top left corner at the given point and its
	 * right edge at the given x: the tier's icon and name, the range of chances it stands for where
	 * that is asked for, a rule in the tier's colour across the room left, and the group's count in
	 * a pill at the right.
	 *
	 * <p>The range is the first thing to go when room runs short, and it stays only where some of
	 * the rule after it would still show, so that the header keeps reading as a line across the
	 * panel rather than as a sentence. The name and the count always stay, since they are what the
	 * header is for.
	 */
	static void drawGroupHeader(GuiGraphics guiGraphics, Font font, int x, int y, int right, LootTier tier, int count, boolean hint) {
		tier.drawIcon(guiGraphics, x, y + 1, false);
		final Component name = tier.displayName();
		final int nameX = x + HEADER_NAME_X;
		guiGraphics.drawString(font, name, nameX, y + 2, tier.colour, true);
		int textEnd = nameX + font.width(name);
		final String countText = String.valueOf(count);
		final int pillX = right - pillWidth(font, countText);
		final int ruleEnd = pillX - HEADER_PILL_GAP;
		if (hint) {
			final Component range = tier.hint();
			final int rangeX = textEnd + HEADER_RULE_GAP;
			final int rangeEnd = rangeX + font.width(range);
			if (rangeEnd + HEADER_RULE_GAP < ruleEnd) {
				guiGraphics.drawString(font, range, rangeX, y + 2, GuiTheme.TEXT_MUTED, false);
				textEnd = rangeEnd;
			}
		}
		drawHairline(guiGraphics, textEnd + HEADER_RULE_GAP, ruleEnd, y + 5, tint(tier, GROUP_RULE_ALPHA));
		drawPill(guiGraphics, font, pillX, y, countText);
	}

	/** The given tier's colour at the given opacity out of 255, for fills and rules that should only hint at the tier. */
	static int tint(LootTier tier, int alpha) {
		return (alpha << 24) | (tier.colour & 0xFFFFFF);
	}

	/**
	 * How many of an item come at once, as a count or a range, or nothing at all for the single
	 * item that needs no saying.
	 */
	static String formatCount(int minCount, int maxCount) {
		if (maxCount <= 1) {
			return "";
		}
		if (minCount == maxCount) {
			return I18n.get("string.explorerscompass.lootCount", String.valueOf(maxCount));
		}
		return I18n.get("string.explorerscompass.lootCountRange", String.valueOf(minCount), String.valueOf(maxCount));
	}

	/** What a table is called on the screen: its translated name where there is one, and a name read off its id otherwise. */
	static Component displayName(String id) {
		final ResourceLocation location = ResourceLocation.tryParse(id);
		if (location == null) {
			return Component.literal(id);
		}
		final String key = LootTableNames.translationKey(location);
		if (I18n.exists(key)) {
			return Component.translatable(key);
		}
		return Component.literal(LootTableNames.prettyName(location.getPath()));
	}

	/** Fills a slot, rounded, with a faint rim inside its edge so that it still reads as a slot behind a light item. */
	static void drawSlot(GuiGraphics guiGraphics, int x, int y, int fill) {
		UiShapes.roundRect(guiGraphics, x, y, SLOT, SLOT, SLOT_RADIUS, fill);
		UiShapes.roundRectStroke(guiGraphics, x, y, SLOT, SLOT, SLOT_RADIUS, UiShapes.stroke(), SLOT_RIM);
	}

	/**
	 * Draws the surface the card and the overview stand on: a rounded panel lifted off the model by a
	 * soft shadow, with a short bar of the accent down its leading edge, which is how everything picked
	 * out on these screens is marked.
	 *
	 * @param gutter how wide the strip down the left is that the bar stands in
	 */
	static void drawPanel(GuiGraphics guiGraphics, int left, int top, int right, int bottom, float radius, int gutter) {
		final int w = right - left;
		final int h = bottom - top;
		UiShapes.shadow(guiGraphics, left, top + 1, w, h, radius, 6.0F, GuiTheme.SHADOW);
		drawSheet(guiGraphics, left, top, right, bottom, radius, gutter);
	}

	/** The panel of {@link #drawPanel} without the shadow, for a panel set into another rather than floating over it. */
	static void drawSheet(GuiGraphics guiGraphics, int left, int top, int right, int bottom, float radius, int gutter) {
		final int w = right - left;
		final int h = bottom - top;
		UiShapes.roundRect(guiGraphics, left, top, w, h, radius, PANEL_TOP, PANEL_BOTTOM);
		UiShapes.roundRectStroke(guiGraphics, left, top, w, h, radius, UiShapes.stroke(), PANEL_BORDER);
		UiShapes.pill(guiGraphics, left + gutter / 2.0F - 0.25F, top + radius + 3, 2.5F, Math.max(2.5F, h - 2 * radius - 6), ACCENT_BAR);
	}

}
