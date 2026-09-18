package com.chaosthedude.explorerscompass.gui;

import com.chaosthedude.explorerscompass.preview.StructurePreview;
import com.chaosthedude.explorerscompass.util.LootTableNames;
import com.chaosthedude.explorerscompass.util.RenderUtils;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * How a loot table's drops are drawn, wherever they are drawn: the card beside a badge and the
 * overview of a whole structure both lay items out as slots, and a slot has to look the same in
 * both for the two to read as one interface.
 *
 * <p>A slot is an item with its chance written small in the corner, coloured by how likely it is:
 * ordinary in white, uncommon in the accent, and rare in violet, so that the one thing worth
 * finding stands out among the bread. An item that comes enchanted is drawn with the glint the
 * game gives an enchanted item, and a bottle as the potion in it, so that the strong regeneration a
 * vault rewards does not draw as a bottle of water.
 */
@OnlyIn(Dist.CLIENT)
final class LootSlots {

	static final int SLOT = 18;
	/** How far in front of the interface an item is drawn, and a little more, so that what is laid over one shows. */
	static final float OVER_ITEMS = 200.0F;

	static final int SLOT_BACKGROUND = 0x1EFFFFFF;
	static final int SLOT_HOVER = 0x50FFC24B;
	private static final int CHANCE_BACKDROP = 0xB0000000;
	private static final int CHANCE_COMMON = 0xFFE4E7EB;
	private static final int CHANCE_UNCOMMON = 0xFFFFC24B;
	private static final int CHANCE_RARE = 0xFFD08CFF;
	/** Below this many thousandths an item is uncommon, and below the next it is rare. */
	private static final int UNCOMMON_PERMILLE = 500;
	private static final int RARE_PERMILLE = 100;

	private LootSlots() {
	}

	/**
	 * The stack a drop is drawn and named as: the item, holding its potion where it is a bottle of
	 * one, and made to glint where it comes enchanted. Empty for an item this side does not know.
	 */
	static ItemStack stackFor(StructurePreview preview, int table, int item) {
		final Item resolved = Item.byId(preview.getLootTableItems(table)[item]);
		if (resolved == null || resolved == Items.AIR) {
			return ItemStack.EMPTY;
		}
		final ItemStack stack = new ItemStack(resolved);
		final int potionId = preview.getLootTableItemPotion(table, item);
		if (potionId >= 0) {
			final Potion potion = BuiltInRegistries.POTION.byId(potionId);
			if (potion != null) {
				stack.set(DataComponents.POTION_CONTENTS, new PotionContents(BuiltInRegistries.POTION.wrapAsHolder(potion)));
			}
		}
		if (preview.isLootTableItemEnchanted(table, item)) {
			stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, Boolean.TRUE);
		}
		return stack;
	}

	/** Draws one slot with its top left corner at the given point. */
	static void drawSlot(GuiGraphics guiGraphics, Font font, int x, int y, ItemStack stack, int permille, boolean lit) {
		RenderUtils.drawRect(guiGraphics, x, y, x + SLOT, y + SLOT, lit ? SLOT_HOVER : SLOT_BACKGROUND);
		if (lit) {
			RenderUtils.drawInnerOutline(guiGraphics, x, y, x + SLOT, y + SLOT, GuiTheme.ACCENT | 0xFF000000);
		}
		if (stack.isEmpty()) {
			return;
		}
		guiGraphics.renderItem(stack, x + 1, y + 1);
		drawChance(guiGraphics, font, permille, x, y);
	}

	/**
	 * The chance, small, in the bottom right corner of the slot, on a dark backdrop so that it
	 * reads over whatever the item's own texture puts behind it.
	 */
	private static void drawChance(GuiGraphics guiGraphics, Font font, int permille, int slotX, int slotY) {
		final String text = formatChance(permille);
		final int textWidth = font.width(text);
		guiGraphics.pose().pushPose();
		// Over the item, which is drawn a little way above the slot itself
		guiGraphics.pose().translate(slotX + SLOT - 1, slotY + SLOT - 1, OVER_ITEMS);
		guiGraphics.pose().scale(0.5F, 0.5F, 1.0F);
		RenderUtils.drawRect(guiGraphics, -textWidth - 2, -font.lineHeight - 1, 1, 1, CHANCE_BACKDROP);
		guiGraphics.drawString(font, text, -textWidth, -font.lineHeight, chanceColour(permille), false);
		guiGraphics.pose().popPose();
	}

	/**
	 * The line that says what a slot holds: how many at once, when that is more than one, and how
	 * likely, drawn against the right edge of the given span with the stack's name filling what is
	 * left to the left of it.
	 */
	static void drawDetail(GuiGraphics guiGraphics, Font font, int left, int right, int y, ItemStack stack, int permille, int minCount, int maxCount) {
		final String chance = formatChance(permille);
		int x = right - font.width(chance);
		guiGraphics.drawString(font, chance, x, y, chanceColour(permille), false);
		final String count = formatCount(minCount, maxCount);
		if (!count.isEmpty()) {
			final String joined = I18n.get("string.explorerscompass.lootDetailSeparator");
			x -= font.width(joined);
			guiGraphics.drawString(font, joined, x, y, GuiTheme.TEXT_MUTED, false);
			x -= font.width(count);
			guiGraphics.drawString(font, count, x, y, GuiTheme.TEXT_SECONDARY, false);
		}
		guiGraphics.drawString(font, RenderUtils.trimToWidth(stack.getHoverName().getString(), x - 6 - left), left, y, GuiTheme.TEXT_PRIMARY, false);
	}

	static int chanceColour(int permille) {
		if (permille < RARE_PERMILLE) {
			return CHANCE_RARE;
		}
		if (permille < UNCOMMON_PERMILLE) {
			return CHANCE_UNCOMMON;
		}
		return CHANCE_COMMON;
	}

	/** A chance in thousandths, written as a percentage with a decimal only where it says something. */
	static String formatChance(int permille) {
		if (permille >= 1000) {
			return "100%";
		}
		if (permille <= 0) {
			return "0%";
		}
		if (permille % 10 == 0) {
			return (permille / 10) + "%";
		}
		return (permille / 10) + "." + (permille % 10) + "%";
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
