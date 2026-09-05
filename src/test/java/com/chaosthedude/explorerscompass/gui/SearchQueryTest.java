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

	@Test
	void chineseDisplayNamesMatchBySubstring() {
		final SearchDocument village = new SearchDocument(
				"minecraft:village", "村庄", "Minecraft", "村庄结构", "主世界");
		assertTrue(SearchQuery.parse("村庄").matches(village));
		assertTrue(SearchQuery.parse("name:村庄").matches(village));
		assertFalse(SearchQuery.parse("传送门").matches(village));
	}

	@Test
	void processSearchTermContainsLoopAgreesWithMatches() {
		final SearchDocument[] documents = { VILLAGE, RUINED_PORTAL, MODDED_BIOME };
		final String[] queries = {
				"village", "name:\"ruined portal\"", "@minecraft dim:overworld -id:ruined",
				"dim:the_nether", "unknown:value", "村庄"
		};
		for (String input : queries) {
			final SearchQuery query = SearchQuery.parse(input);
			for (SearchDocument document : documents) {
				assertTrue(query.matches(document) == matchesTheWayTheScreenDoes(query, document));
			}
		}
	}

	/**
	 * The same {@link String#contains} loop {@code ExplorersCompassScreen.processSearchTerm}
	 * runs, so that a refactor cannot split the two without this test noticing.
	 */
	private static boolean matchesTheWayTheScreenDoes(SearchQuery query, SearchDocument document) {
		for (SearchQuery.SearchTerm term : query.terms()) {
			final boolean contains = document.textFor(term.field).contains(term.value);
			if (term.excluded ? contains : !contains) {
				return false;
			}
		}
		return true;
	}

}
