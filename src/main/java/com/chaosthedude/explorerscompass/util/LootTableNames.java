package com.chaosthedude.explorerscompass.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import net.minecraft.resources.ResourceLocation;

/**
 * Names for loot tables, which the game itself has none of.
 *
 * <p>A loot table is only ever named by its id, and an id like
 * {@code minecraft:chests/village/village_weaponsmith} is the wrong thing to put in front of a
 * player. The mod's own language files name every table a vanilla structure puts in a container,
 * under {@code loot_table.<namespace>.<path with slashes as dots>}, which a mod or a resource pack
 * can add to for tables of its own; for anything not named there, this reads a name out of the id
 * the way the structure list reads one out of a structure's.
 */
public final class LootTableNames {

	/**
	 * The folders a table is filed under by what it is for. Left out of a name read from the id,
	 * since the container it stands on already says as much.
	 */
	private static final Set<String> CATEGORIES = Set.of("chests", "pots", "dispensers", "archaeology", "spawners", "blocks", "entities", "gameplay", "equipment", "shearing");

	private LootTableNames() {
	}

	/** The translation key the given table would be named under. */
	public static String translationKey(ResourceLocation id) {
		return "loot_table." + id.getNamespace() + "." + id.getPath().replace('/', '.');
	}

	/**
	 * A name read out of the path of a table's id: the category folder dropped, the rest of the
	 * folders and the name run together, underscores opened up, and each word capitalised. A word
	 * that follows itself, as in {@code village/village_weaponsmith}, is said once.
	 */
	public static String prettyName(String path) {
		final String[] segments = path.split("/");
		final List<String> words = new ArrayList<String>();
		for (int i = 0; i < segments.length; i++) {
			if (i == 0 && segments.length > 1 && CATEGORIES.contains(segments[i])) {
				continue;
			}
			for (String word : segments[i].split("_")) {
				if (word.isEmpty()) {
					continue;
				}
				if (!words.isEmpty() && words.get(words.size() - 1).equalsIgnoreCase(word)) {
					continue;
				}
				words.add(word);
			}
		}
		if (words.isEmpty()) {
			return path;
		}
		final StringBuilder name = new StringBuilder();
		for (String word : words) {
			if (name.length() > 0) {
				name.append(' ');
			}
			name.append(Character.toUpperCase(word.charAt(0)));
			name.append(word.substring(1).toLowerCase(Locale.ROOT));
		}
		return name.toString();
	}

}
