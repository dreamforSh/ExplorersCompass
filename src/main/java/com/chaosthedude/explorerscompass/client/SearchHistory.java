package com.chaosthedude.explorerscompass.client;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.util.SearchTarget;
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
 * The player's favorites and their most recent searches, shown at the top of the selection list.
 * Kept in {@code config/explorerscompass/history.json}, so they survive across sessions; entries
 * from other worlds simply do not show while they are not in the list.
 *
 * <p>Structures and biomes are kept apart, so that starring a biome does not pin a structure of the
 * same name and the recent searches of one do not crowd out the other's.
 */
@OnlyIn(Dist.CLIENT)
public class SearchHistory {

	private static final String FILE_NAME = "history.json";
	private static final String TARGET_FIELD = "searchTarget";
	private static final int MAX_RECENTS = 8;

	private static boolean loaded;
	private static final Map<SearchTarget, History> histories = new EnumMap<SearchTarget, History>(SearchTarget.class);
	// Which list the screen opens on. Named apart from the target every other method here takes as an
	// argument, since those act on the history of whichever kind they are handed rather than on this
	// one. Structures are what the compass is named for, so that is where a player who has never
	// switched starts.
	private static SearchTarget openOnTarget = SearchTarget.STRUCTURE;

	/** The favorites and recent searches of one kind of target, and what they are stored under. */
	private static class History {

		private final String favoritesField;
		private final String recentsField;
		private final Set<ResourceLocation> favorites = new LinkedHashSet<ResourceLocation>();
		// Most recent first
		private final List<ResourceLocation> recents = new ArrayList<ResourceLocation>();

		private History(String favoritesField, String recentsField) {
			this.favoritesField = favoritesField;
			this.recentsField = recentsField;
		}

	}

	static {
		// The structure fields keep the names they had before biomes could be searched for, so that an
		// existing history file is read rather than started over
		histories.put(SearchTarget.STRUCTURE, new History("favorites", "recents"));
		histories.put(SearchTarget.BIOME, new History("biomeFavorites", "biomeRecents"));
	}

	/**
	 * Which of the two lists the compass screen opens on: whichever one the player last left it
	 * showing. It is their choice rather than the compass's, so a compass that was last pointed at a
	 * biome still opens on structures for a player who searches for those.
	 */
	public static SearchTarget getSearchTarget() {
		ensureLoaded();
		return openOnTarget;
	}

	public static void setSearchTarget(SearchTarget target) {
		ensureLoaded();
		if (openOnTarget == target) {
			return;
		}
		openOnTarget = target;
		save();
	}

	public static boolean isFavorite(SearchTarget searchTarget, ResourceLocation key) {
		ensureLoaded();
		return histories.get(searchTarget).favorites.contains(key);
	}

	public static void toggleFavorite(SearchTarget searchTarget, ResourceLocation key) {
		ensureLoaded();
		final Set<ResourceLocation> favorites = histories.get(searchTarget).favorites;
		if (!favorites.remove(key)) {
			favorites.add(key);
		}
		save();
	}

	/** What was searched for most recently, most recent first. */
	public static List<ResourceLocation> getRecents(SearchTarget searchTarget) {
		ensureLoaded();
		return histories.get(searchTarget).recents;
	}

	public static void pushRecent(SearchTarget searchTarget, ResourceLocation key) {
		ensureLoaded();
		final List<ResourceLocation> recents = histories.get(searchTarget).recents;
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
			for (History history : histories.values()) {
				readKeys(root.getAsJsonObject().get(history.favoritesField), history.favorites::add);
				readKeys(root.getAsJsonObject().get(history.recentsField), history.recents::add);
				while (history.recents.size() > MAX_RECENTS) {
					history.recents.remove(history.recents.size() - 1);
				}
			}
			readSearchTarget(root.getAsJsonObject().get(TARGET_FIELD));
		} catch (IOException | JsonParseException e) {
			ExplorersCompass.LOGGER.warn("Failed to read " + path + ", favorites and recent searches start empty", e);
		}
	}

	/**
	 * Stored by name rather than by the id it is written to a compass under, so that the file stays
	 * readable and a name this version does not know simply leaves the default in place.
	 */
	private static void readSearchTarget(JsonElement element) {
		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
			return;
		}
		for (SearchTarget candidate : SearchTarget.values()) {
			if (candidate.name().equals(element.getAsString())) {
				openOnTarget = candidate;
				return;
			}
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
		for (History history : histories.values()) {
			root.add(history.favoritesField, toArray(history.favorites));
			root.add(history.recentsField, toArray(history.recents));
		}
		root.addProperty(TARGET_FIELD, openOnTarget.name());

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

	private static JsonArray toArray(Iterable<ResourceLocation> keys) {
		final JsonArray array = new JsonArray();
		keys.forEach((key) -> array.add(key.toString()));
		return array;
	}

	private static Path filePath() {
		return FMLPaths.CONFIGDIR.get().resolve(ExplorersCompass.MODID).resolve(FILE_NAME);
	}

}
