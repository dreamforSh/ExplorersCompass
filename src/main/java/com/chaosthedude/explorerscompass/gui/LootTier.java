package com.chaosthedude.explorerscompass.gui;

import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * How likely a drop is, said in four words rather than a number: one the player can count on, one
 * that turns up in most containers, one that turns up now and then, and one worth hunting for.
 *
 * <p>Every place a chance is shown colours it by its tier, so that the colour of a slot says as
 * much at a glance as the percentage under it says on a closer look. The rare tier alone also
 * outlines its slot, since a violet tint on its own is easy to miss among a chest of bread and
 * bones, and the rare item is the one the player opened the card to find.
 */
@OnlyIn(Dist.CLIENT)
enum LootTier {

	GUARANTEED(1000, 0xFF8FCB81, 0x2A8FCB81, 0, "guaranteed"),
	COMMON(500, 0xFFE4E7EB, 0x1EFFFFFF, 0, "common"),
	UNCOMMON(100, 0xFFFFC24B, 0x2AFFC24B, 0, "uncommon"),
	RARE(0, 0xFFD08CFF, 0x38D08CFF, 0x90D08CFF, "rare");

	/** The fewest thousandths a chance needs to be in this tier rather than the next one down. */
	final int minPermille;
	/** The colour a chance in this tier is written and drawn as a bar in. */
	final int colour;
	/** What a slot holding a drop of this tier is filled with. */
	final int slotFill;
	/** The outline drawn just inside such a slot, or 0 where the tier draws none. */
	final int outline;
	/** The last part of the keys this tier's name and description are translated under. */
	final String key;

	LootTier(int minPermille, int colour, int slotFill, int outline, String key) {
		this.minPermille = minPermille;
		this.colour = colour;
		this.slotFill = slotFill;
		this.outline = outline;
		this.key = key;
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

	/**
	 * The band drawn down the side of this tier's group in the overview: the tier's own colour, a
	 * little see-through, so that it marks the group without outshouting the slots beside it.
	 */
	int ribbonColour() {
		return (colour & 0xFFFFFF) | 0xB0000000;
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
