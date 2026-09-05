package com.chaosthedude.explorerscompass.client;

/**
 * The shape a waypoint is marked with on the direction strip, and beside its name in the list.
 * Every shape is a few pixels drawn in the waypoint's own colour; they differ in what they read as
 * at a glance, and in how much of the strip they take up.
 */
public enum WaypointMarkerStyle {

	/** A ribbon hanging from the top of the strip with a notched end, the way a bookmark hangs from a page. */
	BOOKMARK,
	/** A round head on a stem, standing on the bottom of the strip. */
	PIN,
	/** A pole with a pennant, standing on the bottom of the strip. */
	FLAG,
	/** A small diamond in the middle of the strip, which takes the least room of the four. */
	DIAMOND;

	public WaypointMarkerStyle next() {
		return values()[(ordinal() + 1) % values().length];
	}

}
