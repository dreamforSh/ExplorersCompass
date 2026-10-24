package com.chaosthedude.explorerscompass.gui;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * How likely a drop is, said in four words rather than a number: one the player can count on, one
 * that turns up in most containers, one that turns up now and then, and one worth hunting for.
 *
 * <p>Each tier is first a shape and only then a colour: a check for the guaranteed, a disc for the
 * common, a cut gem for the uncommon and a star for the rare. The shapes stay apart where colours
 * do not, for a player who cannot tell gold from grey and on item art as bright as the icon itself,
 * and the colour only says again what the shape already said. The same colour writes the tier's
 * percentages and fills its bars, so that a number reads as the tier its icon names.
 *
 * <p>The icons are sprites rather than drawn rectangles, made on a whole-pixel grid with a dark rim
 * of their own, so that they keep their outline on a light chest of sand or paper as well as on the
 * dark panels. They come in two sizes, the larger for rows that name the tier and the smaller for
 * the corner of a slot and the lines of a tooltip, and are drawn at exactly those sizes, since a
 * pixel sprite stretched by a single pixel blurs.
 */
@OnlyIn(Dist.CLIENT)
enum LootTier {

	GUARANTEED(1000, 0xFF8FCB81, "guaranteed"),
	COMMON(500, 0xFFBDC3CB, "common"),
	UNCOMMON(100, 0xFFFFC24B, "uncommon"),
	RARE(0, 0xFFD08CFF, "rare");

	/** The side of the larger icon, for chips, group headers and the tooltip's tier row. */
	static final int ICON_SIZE = 9;
	/** The side of the smaller icon, for the corner of a slot, tallies and the tooltip's sources. */
	static final int SMALL_ICON_SIZE = 7;
	/** How opaque an icon is drawn where its tier cannot be picked or is set aside by another. */
	static final float DIM_ALPHA = 0.35F;

	/** The fewest thousandths a chance needs to be in this tier rather than the next one down. */
	final int minPermille;
	/** The colour a chance in this tier is written and drawn as a bar in. */
	final int colour;
	/** The last part of the keys this tier's name and description are translated under. */
	final String key;
	/** The larger icon, in the interface's sprite atlas. */
	final ResourceLocation sprite;
	/** The smaller icon, the same shape drawn for two pixels less on a side. */
	final ResourceLocation smallSprite;

	LootTier(int minPermille, int colour, String key) {
		this.minPermille = minPermille;
		this.colour = colour;
		this.key = key;
		this.sprite = ResourceLocation.fromNamespaceAndPath(ExplorersCompass.MODID, "loot/tier_" + key);
		this.smallSprite = ResourceLocation.fromNamespaceAndPath(ExplorersCompass.MODID, "loot/tier_" + key + "_small");
	}

	/**
	 * The tier a chance in thousandths falls in. A chance of nothing at all is rare rather than a
	 * tier of its own: a table only names an item it can drop, and one that rounds to nothing is
	 * still there to be found.
	 */
	static LootTier of(int permille) {
		if (permille >= GUARANTEED.minPermille) {
			return GUARANTEED;
		}
		if (permille >= COMMON.minPermille) {
			return COMMON;
		}
		if (permille >= UNCOMMON.minPermille) {
			return UNCOMMON;
		}
		return RARE;
	}

	/** Draws this tier's icon fully opaque with its top-left corner at the given point. */
	void drawIcon(GuiGraphics guiGraphics, int x, int y, boolean small) {
		drawIcon(guiGraphics, x, y, small, 1.0F);
	}

	/**
	 * Draws this tier's icon with its top-left corner at the given point, as opaque as the given
	 * alpha. Blending is turned on for the icon alone, since its rim is meant to be part see-through
	 * and would otherwise be drawn as a solid black ring, and the colour is put back afterwards so
	 * that a dimmed icon dims nothing drawn after it.
	 */
	void drawIcon(GuiGraphics guiGraphics, int x, int y, boolean small, float alpha) {
		final int size = small ? SMALL_ICON_SIZE : ICON_SIZE;
		RenderSystem.enableBlend();
		if (alpha < 1.0F) {
			guiGraphics.setColor(1.0F, 1.0F, 1.0F, alpha);
		}
		guiGraphics.blitSprite(small ? smallSprite : sprite, x, y, size, size);
		if (alpha < 1.0F) {
			guiGraphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
		}
		RenderSystem.disableBlend();
	}

	/** What the tier is called. */
	Component displayName() {
		return Component.translatable("string.explorerscompass.lootTier." + key);
	}

	/** What range of chances the tier stands for, in words. */
	Component hint() {
		return Component.translatable("string.explorerscompass.lootTierHint." + key);
	}

}
