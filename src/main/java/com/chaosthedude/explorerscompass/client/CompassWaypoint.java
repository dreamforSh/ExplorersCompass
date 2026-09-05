package com.chaosthedude.explorerscompass.client;

import java.util.Objects;

import com.chaosthedude.explorerscompass.items.ExplorersCompassItem;
import com.chaosthedude.explorerscompass.util.SearchTarget;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.ChatFormatting;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * A place the compass located, kept on this client so that it can be marked on the HUD and managed
 * after the compass has moved on.
 *
 * <p>These are the mod's own waypoints. Where Xaero's Minimap is installed each is mirrored into it
 * as well, and {@link #getXaeroWorld} records which of the minimap's worlds it went into, so that
 * the mirror can be found again to be removed or hidden. Nothing here depends on the minimap
 * being there: without it the waypoints are still recorded and still marked on the HUD.
 *
 * <p>Which world a waypoint belongs to is a key of this mod's own making — see
 * {@link CompassWaypoints#currentWorldKey} — since the same server or save has to answer to the same
 * key whether or not the minimap is installed, and whatever the minimap decides a world is called.
 */
@OnlyIn(Dist.CLIENT)
public final class CompassWaypoint {

	/** How many colours the minimap offers, which is also how many the game's text formatting does. */
	public static final int COLOR_COUNT = 16;

	private final String worldKey;
	private final ResourceLocation dimension;
	private final int x;
	private final int y;
	private final int z;
	private final String name;
	private final SearchTarget searchTarget;
	private final ResourceLocation targetKey;
	private final long createdAt;
	/** An index into the minimap's list of colours, which is the game's list of text colours. */
	private final int colorIndex;
	private boolean shownOnHud;
	private String xaeroWorld;

	public CompassWaypoint(String worldKey, ResourceLocation dimension, int x, int y, int z, String name, SearchTarget searchTarget, ResourceLocation targetKey, long createdAt, int colorIndex) {
		this.worldKey = worldKey;
		this.dimension = dimension;
		this.x = x;
		this.y = y;
		this.z = z;
		this.name = name;
		this.searchTarget = searchTarget;
		this.targetKey = targetKey;
		this.createdAt = createdAt;
		this.colorIndex = Math.floorMod(colorIndex, COLOR_COUNT);
		shownOnHud = true;
	}

	public String getWorldKey() {
		return worldKey;
	}

	public ResourceLocation getDimension() {
		return dimension;
	}

	public int getX() {
		return x;
	}

	/** The structure's height, or {@link ExplorersCompassItem#UNKNOWN_Y} when it was never recorded. */
	public int getY() {
		return y;
	}

	public int getZ() {
		return z;
	}

	/** What the waypoint is called, which is the name of the structure or the biome it marks. */
	public String getName() {
		return name;
	}

	public SearchTarget getSearchTarget() {
		return searchTarget;
	}

	/** The structure or biome this marks, or null for a record that no longer names one. */
	public ResourceLocation getTargetKey() {
		return targetKey;
	}

	public long getCreatedAt() {
		return createdAt;
	}

	public int getColorIndex() {
		return colorIndex;
	}

	/** The colour this is drawn in, as red, green and blue with no transparency. */
	public int getColor() {
		final ChatFormatting formatting = ChatFormatting.getById(colorIndex);
		final Integer color = formatting != null ? formatting.getColor() : null;
		return color != null ? color.intValue() : 0xFFFFFF;
	}

	/** Whether this waypoint is marked on the direction strip. */
	public boolean isShownOnHud() {
		return shownOnHud;
	}

	void setShownOnHud(boolean shownOnHud) {
		this.shownOnHud = shownOnHud;
	}

	/** The minimap's own id for the world this was mirrored into, or null while it has not been. */
	public String getXaeroWorld() {
		return xaeroWorld;
	}

	void setXaeroWorld(String xaeroWorld) {
		this.xaeroWorld = xaeroWorld;
	}

	/** Whether this marks the given place, ignoring the height: nothing stands twice on one column. */
	public boolean isAt(String worldKey, ResourceLocation dimension, int x, int z) {
		return this.x == x && this.z == z && this.worldKey.equals(worldKey) && Objects.equals(this.dimension, dimension);
	}

	public boolean isInWorld(String worldKey) {
		return this.worldKey.equals(worldKey);
	}

	public boolean isIn(String worldKey, ResourceLocation dimension) {
		return this.worldKey.equals(worldKey) && Objects.equals(this.dimension, dimension);
	}

	public JsonObject toJson() {
		final JsonObject json = new JsonObject();
		json.addProperty("world", worldKey);
		json.addProperty("dimension", dimension.toString());
		json.addProperty("x", x);
		if (y != ExplorersCompassItem.UNKNOWN_Y) {
			json.addProperty("y", y);
		}
		json.addProperty("z", z);
		json.addProperty("name", name);
		json.addProperty("searchTarget", searchTarget.name());
		if (targetKey != null) {
			json.addProperty("targetKey", targetKey.toString());
		}
		json.addProperty("createdAt", createdAt);
		json.addProperty("color", colorIndex);
		json.addProperty("shownOnHud", shownOnHud);
		if (xaeroWorld != null) {
			json.addProperty("xaeroWorld", xaeroWorld);
		}
		return json;
	}

	/** Reads a waypoint back, or answers null for an entry that does not hold one. */
	public static CompassWaypoint fromJson(JsonElement element) {
		if (element == null || !element.isJsonObject()) {
			return null;
		}
		final JsonObject json = element.getAsJsonObject();
		final String worldKey = string(json, "world");
		final ResourceLocation dimension = location(json, "dimension");
		if (worldKey == null || dimension == null || !json.has("x") || !json.has("z")) {
			return null;
		}

		final String name = string(json, "name", "");
		final CompassWaypoint waypoint = new CompassWaypoint(worldKey, dimension, integer(json, "x", 0), integer(json, "y", ExplorersCompassItem.UNKNOWN_Y), integer(json, "z", 0), name, searchTarget(string(json, "searchTarget", "")), location(json, "targetKey"), json.has("createdAt") && json.get("createdAt").isJsonPrimitive() ? json.get("createdAt").getAsLong() : 0L, integer(json, "color", 6));
		waypoint.shownOnHud = !json.has("shownOnHud") || !json.get("shownOnHud").isJsonPrimitive() || json.get("shownOnHud").getAsBoolean();
		waypoint.xaeroWorld = string(json, "xaeroWorld");
		return waypoint;
	}

	private static SearchTarget searchTarget(String name) {
		for (SearchTarget candidate : SearchTarget.values()) {
			if (candidate.name().equals(name)) {
				return candidate;
			}
		}
		return SearchTarget.STRUCTURE;
	}

	private static String string(JsonObject json, String field) {
		return string(json, field, null);
	}

	/** A resource location, or null where the field is missing, empty or malformed: an empty string parses as a location of nothing. */
	private static ResourceLocation location(JsonObject json, String field) {
		final String text = string(json, field);
		return text == null || text.isEmpty() ? null : ResourceLocation.tryParse(text);
	}

	private static String string(JsonObject json, String field, String fallback) {
		final JsonElement element = json.get(field);
		return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString() ? element.getAsString() : fallback;
	}

	private static int integer(JsonObject json, String field, int fallback) {
		final JsonElement element = json.get(field);
		return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber() ? element.getAsInt() : fallback;
	}

}
