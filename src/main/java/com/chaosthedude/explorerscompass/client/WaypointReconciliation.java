package com.chaosthedude.explorerscompass.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Works out what keeps the compass's records and the minimap's copies of them in step, from one
 * look at both, without touching either.
 *
 * <p>The minimap is where the player edits waypoints — renames them, recolours them, drags them
 * somewhere else, deletes them, and makes new ones — and none of that reaches the compass on its
 * own. So the copies are read back and held against the records, column by column, since nothing
 * stands twice on one column in either:
 *
 * <ul>
 * <li>A record whose copy is still on its column is matched; where the copy's name or colour
 * differs, the record is <b>changed</b> to say what the copy now says.
 * <li>A record whose column is empty has lost its copy. Before it is given up, the copies on no
 * record's column are looked through for one of the same name, which is the copy having been
 * <b>moved</b>: the record follows it rather than being forgotten and the place being recorded again
 * as something that no longer says what it was.
 * <li>A record whose copy is gone and not to be found under its name is <b>orphaned</b>: a copy
 * deleted in the minimap takes its record with it.
 * <li>A record never marked as mirrored into this world, but standing on a copy's column, is
 * <b>adopted</b>: the copy is taken as its mirror from now on, and says what the record is called.
 * <li>A copy on no record's column at all is <b>unrecorded</b>: a waypoint the player made in the
 * minimap, which the compass may take in as one of its own.
 * </ul>
 *
 * <p>The minimap's word is taken over the compass's wherever they differ, since the minimap is
 * where the player did the editing. Nothing here reads the minimap or the file; it is handed
 * both and answers with what to do, so that it can be held to a table of cases.
 */
@OnlyIn(Dist.CLIENT)
final class WaypointReconciliation {

	/** One waypoint as the minimap has it, read out of it. */
	record MapWaypoint(int x, int y, int z, String name, int colorIndex, boolean yIncluded) {
	}

	/** Everything found to be out of step, and which way. Maps keep the order the records were given in. */
	record Result(List<CompassWaypoint> orphaned, Map<CompassWaypoint, MapWaypoint> moved, Map<CompassWaypoint, MapWaypoint> changed, Map<CompassWaypoint, MapWaypoint> adopted, List<MapWaypoint> unrecorded) {

		boolean isEmpty() {
			return orphaned.isEmpty() && moved.isEmpty() && changed.isEmpty() && adopted.isEmpty() && unrecorded.isEmpty();
		}

	}

	private WaypointReconciliation() {
	}

	/**
	 * Holds the given records against the given copies.
	 *
	 * @param records    the records of the dimension the minimap has open, mirrored into it or not
	 * @param xaeroWorld the minimap's id for the world it has open, which is what a record mirrored into it names
	 * @param copies     every waypoint the minimap holds for that world that the compass may take as a copy
	 */
	static Result reconcile(List<CompassWaypoint> records, String xaeroWorld, List<MapWaypoint> copies) {
		final Map<Long, MapWaypoint> unmatched = new LinkedHashMap<Long, MapWaypoint>();
		for (MapWaypoint copy : copies) {
			// Two copies on one column is the minimap's own confusion; the first is the one taken
			unmatched.putIfAbsent(column(copy.x(), copy.z()), copy);
		}

		final List<CompassWaypoint> orphaned = new ArrayList<CompassWaypoint>();
		final Map<CompassWaypoint, MapWaypoint> moved = new LinkedHashMap<CompassWaypoint, MapWaypoint>();
		final Map<CompassWaypoint, MapWaypoint> changed = new LinkedHashMap<CompassWaypoint, MapWaypoint>();
		final Map<CompassWaypoint, MapWaypoint> adopted = new LinkedHashMap<CompassWaypoint, MapWaypoint>();
		final List<CompassWaypoint> lostCopy = new ArrayList<CompassWaypoint>();

		for (CompassWaypoint record : records) {
			final MapWaypoint copy = unmatched.remove(column(record.getX(), record.getZ()));
			final boolean mirroredHere = xaeroWorld.equals(record.getXaeroWorld());
			if (copy == null) {
				if (mirroredHere) {
					lostCopy.add(record);
				}
				// A record never mirrored here that has no copy is nothing to do with the minimap yet
				continue;
			}
			if (!mirroredHere) {
				adopted.put(record, copy);
			}
			if (differs(record, copy)) {
				changed.put(record, copy);
			}
		}

		for (CompassWaypoint record : lostCopy) {
			final MapWaypoint movedTo = takeByName(unmatched, record.getName());
			if (movedTo != null) {
				moved.put(record, movedTo);
			} else {
				orphaned.add(record);
			}
		}

		return new Result(orphaned, moved, changed, adopted, new ArrayList<MapWaypoint>(unmatched.values()));
	}

	/** Whether the copy says something about the waypoint that the record does not: its name or its colour. */
	private static boolean differs(CompassWaypoint record, MapWaypoint copy) {
		return !record.getName().equals(copy.name()) || record.getColorIndex() != Math.floorMod(copy.colorIndex(), CompassWaypoint.COLOR_COUNT);
	}

	/** The first copy of the given name among those on no record's column, taken out of them. */
	private static MapWaypoint takeByName(Map<Long, MapWaypoint> unmatched, String name) {
		for (Map.Entry<Long, MapWaypoint> entry : unmatched.entrySet()) {
			if (entry.getValue().name().equals(name)) {
				unmatched.remove(entry.getKey());
				return entry.getValue();
			}
		}
		return null;
	}

	private static long column(int x, int z) {
		return ((long) x << 32) | (z & 0xFFFFFFFFL);
	}

}
