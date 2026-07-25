package com.chaosthedude.explorerscompass.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import net.minecraft.ResourceLocationException;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * Maps structures to the {@code CustomModelData} value the compass carries while it is pointing
 * at one, so that a resource pack can give the compass a different look per structure.
 *
 * <p>Read from {@code config/explorerscompass/config.json}, which holds an array of entries:
 *
 * <pre>
 * [{"key": "minecraft:village_plains", "value": 10}, {"key": "minecraft:village_desert", "value": 20}]
 * </pre>
 */
public class CustomModelDataConfig {

	private static final String DIRECTORY_NAME = ExplorersCompass.MODID;
	private static final String FILE_NAME = "config.json";
	private static final String KEY_FIELD = "key";
	private static final String VALUE_FIELD = "value";
	private static final String NBT_KEY = "CustomModelData";

	private static Map<ResourceLocation, Integer> customModelData = new HashMap<ResourceLocation, Integer>();

	/**
	 * Reads the file, creating an empty one if it does not exist yet so that it can be found and
	 * edited.
	 */
	public static void load() {
		final Path path = FMLPaths.CONFIGDIR.get().resolve(DIRECTORY_NAME).resolve(FILE_NAME);
		final Map<ResourceLocation, Integer> values = new HashMap<ResourceLocation, Integer>();
		try {
			if (!Files.exists(path)) {
				Files.createDirectories(path.getParent());
				try (Writer writer = Files.newBufferedWriter(path)) {
					writer.write("[]\n");
				}
			} else {
				try (Reader reader = Files.newBufferedReader(path)) {
					readEntries(JsonParser.parseReader(reader), values);
				}
			}
		} catch (IOException | JsonParseException e) {
			ExplorersCompass.LOGGER.error("Failed to read " + path + ", custom model data will not be applied", e);
			return;
		}

		customModelData = values;
		if (!values.isEmpty()) {
			ExplorersCompass.LOGGER.info("Loaded custom model data for " + values.size() + " structures");
		}
	}

	private static void readEntries(JsonElement root, Map<ResourceLocation, Integer> values) {
		if (!root.isJsonArray()) {
			throw new JsonParseException("Expected an array of entries");
		}

		final JsonArray array = root.getAsJsonArray();
		for (JsonElement element : array) {
			if (!element.isJsonObject()) {
				ExplorersCompass.LOGGER.warn("Ignoring custom model data entry that is not an object: " + element);
				continue;
			}

			final JsonObject entry = element.getAsJsonObject();
			if (!entry.has(KEY_FIELD) || !entry.has(VALUE_FIELD)) {
				ExplorersCompass.LOGGER.warn("Ignoring custom model data entry without a key and a value: " + entry);
				continue;
			}

			try {
				values.put(new ResourceLocation(entry.get(KEY_FIELD).getAsString()), entry.get(VALUE_FIELD).getAsInt());
			} catch (ResourceLocationException | NumberFormatException | UnsupportedOperationException | IllegalStateException e) {
				ExplorersCompass.LOGGER.warn("Ignoring malformed custom model data entry " + entry + ": " + e.getMessage());
			}
		}
	}

	/**
	 * Puts the value configured for the given structure or group on the compass, or takes any
	 * previous one back off when nothing is configured for it.
	 */
	public static void apply(ItemStack stack, ResourceLocation structureKey) {
		final Integer value = structureKey == null ? null : customModelData.get(structureKey);
		if (value == null) {
			remove(stack);
		} else if (stack.hasTag()) {
			stack.getTag().putInt(NBT_KEY, value);
		}
	}

	public static void remove(ItemStack stack) {
		if (stack.hasTag()) {
			stack.getTag().remove(NBT_KEY);
		}
	}

}
