package com.chaosthedude.explorerscompass.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.neoforged.neoforge.common.ModConfigSpec.ConfigValue;

/**
 * Holds the settings screen to the settings.
 *
 * <p>Every setting is shown under a name and explained in a tooltip, both looked up in the language
 * files, and every setting has to be in one of the groups the screen lays out; a setting added to
 * the config without any of these would show up under its raw key, or not at all. None of that
 * breaks the build or the game, which is why it is worth a test.
 */
class ConfigTranslationsTest {

	private static final String PREFIX = "explorerscompass.configuration.";

	@Test
	void everySettingIsNamedAndExplainedInEnglish() {
		final Set<String> keys = englishKeys();
		final List<String> missing = new ArrayList<String>();
		for (ConfigValue<?> value : allValues()) {
			final String key = keyOf(value);
			if (!keys.contains(PREFIX + key)) {
				missing.add(PREFIX + key);
			}
			if (!keys.contains(PREFIX + key + ".tooltip")) {
				missing.add(PREFIX + key + ".tooltip");
			}
		}
		assertEquals(Collections.emptyList(), missing, "settings the language file does not name or explain");
	}

	@Test
	void everyChoiceOfEveryChoiceSettingIsNamed() {
		final Set<String> keys = englishKeys();
		final List<String> missing = new ArrayList<String>();
		for (ConfigValue<?> value : allValues()) {
			final Object current = value.getDefault();
			if (!(current instanceof Enum<?> constant)) {
				continue;
			}
			for (Object choice : constant.getDeclaringClass().getEnumConstants()) {
				final String key = PREFIX + "enum." + constant.getDeclaringClass().getSimpleName() + "." + ((Enum<?>) choice).name().toLowerCase(Locale.ROOT);
				if (!keys.contains(key)) {
					missing.add(key);
				}
			}
		}
		assertEquals(Collections.emptyList(), missing, "choices the language file does not name");
	}

	@Test
	void everySettingIsInExactlyOneGroupAndEveryGroupIsNamed() {
		final Set<String> keys = englishKeys();
		final List<ConfigHandler.Group> groups = new ArrayList<ConfigHandler.Group>(ConfigHandler.clientGroups());
		groups.addAll(ConfigHandler.generalGroups());

		final List<String> grouped = new ArrayList<String>();
		for (ConfigHandler.Group group : groups) {
			assertTrue(keys.contains(PREFIX + "group." + group.key()), "group " + group.key() + " is not named");
			for (ConfigValue<?> value : group.values()) {
				grouped.add(keyOf(value));
			}
		}

		final Set<String> all = new TreeSet<String>();
		for (ConfigValue<?> value : allValues()) {
			all.add(keyOf(value));
		}
		final Set<String> ungrouped = new TreeSet<String>(all);
		ungrouped.removeAll(grouped);
		assertEquals(Collections.emptySet(), ungrouped, "settings the screen would never show");

		final Set<String> seen = new TreeSet<String>();
		final List<String> twice = new ArrayList<String>();
		for (String key : grouped) {
			if (!seen.add(key)) {
				twice.add(key);
			}
		}
		assertEquals(Collections.emptyList(), twice, "settings the screen would show more than once");
	}

	@Test
	void noTwoSettingsShareAKeyAcrossTheTwoFiles() {
		// The screen names a setting by the last part of its path alone, so a key used in both files
		// would have the two share one name and one explanation
		final Set<String> seen = new TreeSet<String>();
		for (ConfigValue<?> value : allValues()) {
			assertTrue(seen.add(keyOf(value)), keyOf(value) + " is defined in both files");
		}
	}

	private static String keyOf(ConfigValue<?> value) {
		final List<String> path = value.getPath();
		return path.get(path.size() - 1);
	}

	private static List<ConfigValue<?>> allValues() {
		final List<ConfigValue<?>> values = new ArrayList<ConfigValue<?>>();
		collect(ConfigHandler.CLIENT_SPEC.getValues(), values);
		collect(ConfigHandler.GENERAL_SPEC.getValues(), values);
		assertTrue(values.size() > 40, "the specs hold far fewer settings than expected: " + values.size());
		return values;
	}

	/** Walks the spec's tree of values, which nests one level for each section the settings are pushed into. */
	private static void collect(UnmodifiableConfig config, List<ConfigValue<?>> out) {
		for (UnmodifiableConfig.Entry entry : config.entrySet()) {
			final Object held = entry.getValue();
			if (held instanceof UnmodifiableConfig nested) {
				collect(nested, out);
			} else if (held instanceof ConfigValue<?> value) {
				out.add(value);
			}
		}
	}

	private static Set<String> englishKeys() {
		final String path = "/assets/explorerscompass/lang/en_us.json";
		try (InputStream in = ConfigTranslationsTest.class.getResourceAsStream(path)) {
			assertNotNull(in, "There is no " + path + " on the classpath");
			final JsonElement root = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
			final JsonObject object = root.getAsJsonObject();
			return new TreeSet<String>(object.keySet());
		} catch (IOException e) {
			throw new AssertionError("Could not read " + path, e);
		}
	}

}
