package com.chaosthedude.explorerscompass;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.google.gson.stream.JsonReader;

/**
 * Holds the language files to each other.
 *
 * <p>A missing key does not break the build and does not crash the game: it renders as the raw
 * translation key, or falls back to English, in whichever one screen happens to use it. That is
 * exactly the kind of fault nobody notices until a player playing in that language reports it, which
 * is why it is worth a test rather than a review.
 */
class LangFileTest {

	private static final String DIRECTORY = "/assets/explorerscompass/lang/";
	private static final String REFERENCE = "en_us";
	private static final List<String> TRANSLATIONS = List.of("de_de", "es_es", "ja_jp", "ru_ru", "zh_cn", "zh_tw");

	// Comments ride along as keys, since the format has nowhere else to put them. They are section
	// headers for whoever is translating rather than anything the game reads, so they are held to
	// nothing but being unique.
	private static final String COMMENT_PREFIX = "_comment";

	// Both the plain and the indexed forms, since a translation is free to reorder its arguments and
	// several of these already do
	private static final Pattern FORMAT_SPECIFIER = Pattern.compile("%(\\d+\\$)?[a-zA-Z]");

	@Test
	void everyFileIsReadableJsonWithNoRepeatedKey() {
		for (String locale : allLocales()) {
			final Map<String, String> entries = read(locale);
			assertTrue(entries.size() > 1, locale + " holds no entries");
		}
	}

	@Test
	void everyTranslationCarriesEveryKeyEnglishDoes() {
		final Set<String> reference = translatableKeys(read(REFERENCE));
		for (String locale : TRANSLATIONS) {
			final Set<String> missing = new TreeSet<String>(reference);
			missing.removeAll(translatableKeys(read(locale)));
			assertEquals(Collections.emptySet(), missing, locale + " is missing keys that " + REFERENCE + " defines");
		}
	}

	@Test
	void noTranslationCarriesAKeyEnglishDoesNot() {
		// A key only one language defines is either a typo or something the reference file was never
		// told about, and English is what every other language falls back to
		final Set<String> reference = translatableKeys(read(REFERENCE));
		for (String locale : TRANSLATIONS) {
			final Set<String> extra = new TreeSet<String>(translatableKeys(read(locale)));
			extra.removeAll(reference);
			assertEquals(Collections.emptySet(), extra, locale + " defines keys that " + REFERENCE + " does not");
		}
	}

	@Test
	void everyTranslationTakesTheSameArgumentsEnglishDoes() {
		// A line translated with an argument too few or too many throws where it is formatted, which is
		// while a tooltip or a chat message is being built rather than while the file is being read
		final Map<String, String> reference = read(REFERENCE);
		for (String locale : TRANSLATIONS) {
			final Map<String, String> entries = read(locale);
			for (Map.Entry<String, String> entry : entries.entrySet()) {
				final String expected = reference.get(entry.getKey());
				if (expected == null || entry.getKey().startsWith(COMMENT_PREFIX)) {
					continue;
				}
				assertEquals(specifiers(expected), specifiers(entry.getValue()),
						locale + " formats " + entry.getKey() + " with different arguments than " + REFERENCE);
			}
		}
	}

	@Test
	void theCompassNamesEveryDimensionItCanReport() {
		// The game itself translates no dimension, so without these every readout of one falls back to
		// prettifying its id, which leaves it in English in every language
		for (String locale : allLocales()) {
			final Set<String> keys = read(locale).keySet();
			for (String dimension : List.of("overworld", "the_nether", "the_end")) {
				assertTrue(keys.contains("dimension.minecraft." + dimension), locale + " does not name dimension.minecraft." + dimension);
			}
		}
	}

	private static List<String> allLocales() {
		final List<String> locales = new ArrayList<String>();
		locales.add(REFERENCE);
		locales.addAll(TRANSLATIONS);
		return locales;
	}

	/** Everything the game may look up, which is everything but the section headers. */
	private static Set<String> translatableKeys(Map<String, String> entries) {
		final Set<String> keys = new LinkedHashSet<String>();
		for (String key : entries.keySet()) {
			if (!key.startsWith(COMMENT_PREFIX)) {
				keys.add(key);
			}
		}
		return keys;
	}

	/**
	 * The arguments a line is formatted with, in the order they are written. Comparing these rather
	 * than counting them is what catches a translation that reorders its arguments without saying so.
	 */
	private static List<String> specifiers(String value) {
		final List<String> found = new ArrayList<String>();
		final Matcher matcher = FORMAT_SPECIFIER.matcher(value);
		while (matcher.find()) {
			found.add(matcher.group());
		}
		return found;
	}

	/**
	 * Reads a file entry by entry rather than into a tree, so that a key written twice is caught. A
	 * parser keeps the last of them and says nothing, which would quietly drop whichever section
	 * header or translation came first.
	 */
	private static Map<String, String> read(String locale) {
		final String path = DIRECTORY + locale + ".json";
		final String text;
		try (InputStream in = LangFileTest.class.getResourceAsStream(path)) {
			assertNotNull(in, "There is no " + path + " on the classpath");
			text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new AssertionError("Could not read " + path, e);
		}

		final Map<String, String> entries = new LinkedHashMap<String, String>();
		try (JsonReader reader = new JsonReader(new StringReader(text))) {
			reader.beginObject();
			while (reader.hasNext()) {
				final String key = reader.nextName();
				final String value = reader.nextString();
				assertEquals(null, entries.put(key, value), locale + " defines " + key + " more than once");
			}
			reader.endObject();
		} catch (IOException | IllegalStateException e) {
			throw new AssertionError(path + " is not a flat object of strings", e);
		}
		return entries;
	}

}
