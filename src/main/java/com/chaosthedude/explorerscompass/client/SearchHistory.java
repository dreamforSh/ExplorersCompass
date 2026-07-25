package com.chaosthedude.explorerscompass.client;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * The player's favorite structures and their most recent searches, shown at the top of the
 * selection list. Kept in {@code config/explorerscompass/history.json}, so they survive across
 * sessions; structures from other worlds simply do not show while they are not in the list.
 */
@OnlyIn(Dist.CLIENT)
public class SearchHistory {

	private static final String FILE_NAME = "history.json";
	private static final String FAVORITES_FIELD = "favorites";
	private static final String RECENTS_FIELD = "recents";
	private static final int MAX_RECENTS = 8;

	private static boolean loaded;
	private static final Set<ResourceLocation> favorites = new LinkedHashSet<ResourceLocation>();
	// Most recent first
	private static final List<ResourceLocation> recents = new ArrayList<ResourceLocation>();

	public static boolean isFavorite(ResourceLocation key) {
		ensureLoaded();
		return favorites.contains(key);
	}

	public static void toggleFavorite(ResourceLocation key) {
		ensureLoaded();
		if (!favorites.remove(key)) {
			favorites.add(key);
		}
		save();
	}

	/** The structures searched for most recently, most recent first. */
	public static List<ResourceLocation> getRecents() {
		ensureLoaded();
		return recents;
	}

	public static void pushRecent(ResourceLocation key) {
		ensureLoaded();
		recents.remove(key);
		recents.add(0, key);
		while (recents.size() > MAX_RECENTS) {
			recents.remove(recents.size() - 1);
		}
		save();
	}

	private static void ensureLoaded() {
		if (loaded) {
			return;
		}
		loaded = true;

		final Path path = filePath();
		if (!Files.exists(path)) {
			return;
		}

		try (Reader reader = Files.newBufferedReader(path)) {
			final JsonElement root = JsonParser.parseReader(reader);
			if (!root.isJsonObject()) {
				throw new JsonParseException("Expected an object");
			}
			readKeys(root.getAsJsonObject().get(FAVORITES_FIELD), favorites::add);
			readKeys(root.getAsJsonObject().get(RECENTS_FIELD), recents::add);
			while (recents.size() > MAX_RECENTS) {
				recents.remove(recents.size() - 1);
			}
		} catch (IOException | JsonParseException e) {
			ExplorersCompass.LOGGER.warn("Failed to read " + path + ", favorites and recent searches start empty", e);
		}
	}

	private static void readKeys(JsonElement element, java.util.function.Consumer<ResourceLocation> out) {
		if (element == null || !element.isJsonArray()) {
			return;
		}
		for (JsonElement entry : element.getAsJsonArray()) {
			if (entry.isJsonPrimitive() && entry.getAsJsonPrimitive().isString()) {
				final ResourceLocation key = ResourceLocation.tryParse(entry.getAsString());
				if (key != null) {
					out.accept(key);
				}
			}
		}
	}

	private static void save() {
		final JsonObject root = new JsonObject();
		final JsonArray favoritesArray = new JsonArray();
		favorites.forEach((key) -> favoritesArray.add(key.toString()));
		root.add(FAVORITES_FIELD, favoritesArray);
		final JsonArray recentsArray = new JsonArray();
		recents.forEach((key) -> recentsArray.add(key.toString()));
		root.add(RECENTS_FIELD, recentsArray);

		final Path path = filePath();
		try {
			Files.createDirectories(path.getParent());
			try (Writer writer = Files.newBufferedWriter(path)) {
				writer.write(root.toString());
			}
		} catch (IOException e) {
			ExplorersCompass.LOGGER.warn("Failed to write " + path + ", favorites and recent searches will not persist", e);
		}
	}

	private static Path filePath() {
		return FMLPaths.CONFIGDIR.get().resolve(ExplorersCompass.MODID).resolve(FILE_NAME);
	}

}
