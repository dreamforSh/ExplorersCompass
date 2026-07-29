package com.chaosthedude.explorerscompass.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SearchQueryTest {

	private static final SearchDocument VILLAGE = new SearchDocument(
			"minecraft:village", "Village", "Minecraft", "Village structures",
			"Overworld minecraft:overworld");
	private static final SearchDocument RUINED_PORTAL = new SearchDocument(
			"minecraft:ruined_portal", "Ruined Portal", "Minecraft", "Portal structures",
			"Overworld minecraft:overworld Nether minecraft:the_nether");
	private static final SearchDocument MODDED_BIOME = new SearchDocument(
			"examplemod:crystal_caves", "Crystal Caves", "Example Biomes",
			"Underground biomes", "Overworld minecraft:overworld");

	@Test
	void plainTermsMatchNamesAndRegistryIds() {
		assertTrue(SearchQuery.parse("village").matches(VILLAGE));
		assertTrue(SearchQuery.parse("minecraft:village").matches(VILLAGE));
		assertTrue(SearchQuery.parse("crystal_caves").matches(MODDED_BIOME));
		assertFalse(SearchQuery.parse("village").matches(RUINED_PORTAL));
	}

	@Test
	void fieldPrefixesRestrictWhichTextCanMatch() {
		assertTrue(SearchQuery.parse("id:ruined_portal").matches(RUINED_PORTAL));
		assertTrue(SearchQuery.parse("name:\"ruined portal\"").matches(RUINED_PORTAL));
		assertTrue(SearchQuery.parse("@example").matches(MODDED_BIOME));
		assertTrue(SearchQuery.parse("mod:\"Example Biomes\"").matches(MODDED_BIOME));
		assertTrue(SearchQuery.parse("#underground").matches(MODDED_BIOME));
		assertTrue(SearchQuery.parse("group:portal").matches(RUINED_PORTAL));
		assertTrue(SearchQuery.parse("dim:the_nether").matches(RUINED_PORTAL));
		assertFalse(SearchQuery.parse("dim:the_nether").matches(VILLAGE));
	}

	@Test
	void positiveTermsUseAndAndNegativeTermsExclude() {
		assertTrue(SearchQuery.parse("@minecraft dim:overworld -id:ruined").matches(VILLAGE));
		assertFalse(SearchQuery.parse("@minecraft dim:overworld -id:ruined")
				.matches(RUINED_PORTAL));
		assertFalse(SearchQuery.parse("@minecraft dim:the_nether").matches(VILLAGE));
	}

	@Test
	void partialAndUnknownSyntaxRemainTypingFriendly() {
		assertTrue(SearchQuery.parse("@ id: dim: -").matches(VILLAGE));
		assertTrue(SearchQuery.parse("\"ruined portal").matches(RUINED_PORTAL));
		assertTrue(SearchQuery.parse("minecraft:village").matches(VILLAGE));
		assertFalse(SearchQuery.parse("unknown:value").matches(VILLAGE));
	}

}
