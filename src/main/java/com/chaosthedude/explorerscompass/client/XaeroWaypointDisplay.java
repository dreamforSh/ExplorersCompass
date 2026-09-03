package com.chaosthedude.explorerscompass.client;

/**
 * Where Xaero's Minimap shows the waypoints this mod mirrors into it. The direction strip marks them
 * either way; this is about how much of the minimap's own drawing of them is wanted on top of that.
 */
public enum XaeroWaypointDisplay {

	/** On the minimap, on the world map, and floating in the world as the minimap draws every waypoint. */
	MINIMAP_AND_WORLD,
	/** On the world map alone, which is the minimap's "world map only" visibility: nothing in the world or on the minimap. */
	WORLD_MAP_ONLY,
	/** Kept in the minimap's list but switched off there, so that the minimap draws it nowhere at all. */
	HIDDEN;

	/**
	 * The visibility the minimap's own setting for this reads as, by its index in the minimap's list:
	 * local, global, world map local, world map global.
	 */
	public int getXaeroVisibilityType() {
		return this == WORLD_MAP_ONLY ? 2 : 0;
	}

	public boolean isDisabledInXaero() {
		return this == HIDDEN;
	}

	public XaeroWaypointDisplay next() {
		return values()[(ordinal() + 1) % values().length];
	}

	public String getTranslationKey() {
		return "string.explorerscompass.xaeroDisplay." + name().toLowerCase(java.util.Locale.ROOT);
	}

}
