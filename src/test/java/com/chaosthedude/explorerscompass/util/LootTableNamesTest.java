package com.chaosthedude.explorerscompass.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import net.minecraft.resources.ResourceLocation;

class LootTableNamesTest {

	@Test
	void theCategoryFolderIsDroppedAndTheRestIsRunTogether() {
		assertEquals("Trial Chambers Reward", LootTableNames.prettyName("chests/trial_chambers/reward"));
		assertEquals("Trial Chambers Corridor", LootTableNames.prettyName("pots/trial_chambers/corridor"));
	}

	@Test
	void aWordThatFollowsItselfIsSaidOnce() {
		// The village tables are filed as village/village_x, which would otherwise read "Village Village X"
		assertEquals("Village Weaponsmith", LootTableNames.prettyName("chests/village/village_weaponsmith"));
	}

	@Test
	void aTableInNoFolderIsNamedAsItIs() {
		assertEquals("Shipwreck Treasure", LootTableNames.prettyName("shipwreck_treasure"));
	}

	@Test
	void aFolderThatIsNotACategoryIsKept() {
		// A mod filing its tables under its own folders means the folders
		assertEquals("Dungeons Tower Top", LootTableNames.prettyName("dungeons/tower/top"));
	}

	@Test
	void theTranslationKeyFollowsTheGamesConvention() {
		assertEquals("loot_table.minecraft.chests.village.village_weaponsmith", LootTableNames.translationKey(ResourceLocation.fromNamespaceAndPath("minecraft", "chests/village/village_weaponsmith")));
	}

}
