package com.chaosthedude.explorerscompass.gui;

import com.chaosthedude.explorerscompass.util.LootTableNames;
import com.chaosthedude.explorerscompass.util.RenderUtils;

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
 * both for the two to read as one interface.
 *
 * <p>A tile is a slot with the item in it, how many come at once in the corner where the game
 * writes a stack's size, and the chance written underneath at full size, so that neither has to be
 * squinted at. The slot is tinted by the chance's tier, so that a row of tiles reads at a glance
 * before any number is read, and a rare item's slot is outlined as well, so that the one thing
 * worth finding stands out among the bread. A certain item needs no label: its green slot says it,
 * and a row of nothing else is drawn shorter. An item that comes enchanted is drawn with the glint
 * the game gives an enchanted item, and a bottle as the potion in it, so that the strong
 * regeneration a vault rewards does not draw as a bottle of water.
 */
@OnlyIn(Dist.CLIENT)
final class LootSlots {

	static final int SLOT = 18;
	/** How wide a tile is: a slot with a little room either side, so that the labels of neighbours do not run together. */
	static final int TILE_W = 22;
	/** How far in from the left of its tile a slot is. */
	static final int SLOT_INSET_X = 2;
	/** How tall a row of tiles is with labels under its slots: the slot, then a line for the chance. */
	static final int ROW_LABELLED = 27;
	/** How tall a row of tiles is where none of them has a label to show. */
	static final int ROW_PLAIN = 19;
	/** How far right of the ribbon down a tier's group its tiles begin: the ribbon and a gap after it. */
	static final int RIBBON = 4;
	/** The space left between one tier's group and the next. */
	static final int BAND_GAP = 3;
	/** How far in front of the interface an item is drawn, and a little more, so that what is laid over one shows. */
	static final float OVER_ITEMS = 200.0F;

	static final int HOVER_FILL = 0x40FFFFFF;
	static final int HOVER_OUTLINE = 0xFFFFFFFF;
	static final int MORE_FILL = 0xC0000000;
	/** The count is white with a shadow, the way the game writes a stack's size, so that it reads as one. */
	static final int COUNT_COLOUR = 0xFFFFFFFF;
	/** The most hidden items the tile that stands for them counts, so that the count fits in the slot. */
	private static final int MORE_MAX = 99;

	private LootSlots() {
	}

	/**
	 * Draws one tile with its top left corner at the given point: the slot, tinted by the tier of the
	 * given chance and outlined where the tier asks for it or where the tile is lit, the item in it,
	 * how many come at once in its corner, and the chance under it unless the item is certain.
	 *
	 * <p>The game draws an item a long way in front of the slot it sits in, so the count is pushed
	 * in front of that in turn, where the game puts a stack's size, or the item would cover it. The
	 * slot, its outline and the label stay where they are: the item is meant to stand in front of the
	 * first two, and nothing stands in front of the last. A stack this side does not know is drawn
	 * as a question mark, so that the tile still holds its place and its chance.
	 */
	static void drawTile(GuiGraphics guiGraphics, Font font, int tileX, int tileY, ItemStack stack, int labelPermille, int minCount, int maxCount, boolean lit) {
		final LootTier tier = LootTier.of(labelPermille);
		final int slotX = tileX + SLOT_INSET_X;
		final int slotY = tileY;
		RenderUtils.drawRect(guiGraphics, slotX, slotY, slotX + SLOT, slotY + SLOT, tier.slotFill);
		if (lit) {
			RenderUtils.drawRect(guiGraphics, slotX, slotY, slotX + SLOT, slotY + SLOT, HOVER_FILL);
			RenderUtils.drawInnerOutline(guiGraphics, slotX, slotY, slotX + SLOT, slotY + SLOT, HOVER_OUTLINE);
		} else if (tier.outline != 0) {
			RenderUtils.drawInnerOutline(guiGraphics, slotX, slotY, slotX + SLOT, slotY + SLOT, tier.outline);
		}
		if (stack.isEmpty()) {
			final String unknown = "?";
			guiGraphics.drawString(font, unknown, slotX + SLOT / 2 - font.width(unknown) / 2, slotY + 5, GuiTheme.TEXT_MUTED, false);
		} else {
			guiGraphics.renderItem(stack, slotX + 1, slotY + 1);
		}
		final String count = LootMath.formatGridCount(minCount, maxCount, font::width);
		if (count != null) {
			guiGraphics.pose().pushPose();
			guiGraphics.pose().translate(0.0F, 0.0F, OVER_ITEMS);
			guiGraphics.drawString(font, count, slotX + SLOT - font.width(count), slotY + 10, COUNT_COLOUR, true);
			guiGraphics.pose().popPose();
		}
		final String label = LootMath.formatGridChance(labelPermille);
		if (label != null) {
			guiGraphics.drawString(font, label, tileX + TILE_W / 2 - font.width(label) / 2, tileY + SLOT + 1, tier.colour, false);
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
		RenderUtils.drawRect(guiGraphics, slotX, slotY, slotX + SLOT, slotY + SLOT, MORE_FILL);
		if (lit) {
			RenderUtils.drawInnerOutline(guiGraphics, slotX, slotY, slotX + SLOT, slotY + SLOT, HOVER_OUTLINE);
		}
		final String text = I18n.get("string.explorerscompass.lootMoreItems", String.valueOf(Math.min(hidden, MORE_MAX)));
		guiGraphics.drawString(font, text, slotX + SLOT / 2 - font.width(text) / 2, slotY + 5, GuiTheme.ACCENT, false);
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

}
