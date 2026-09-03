package com.chaosthedude.explorerscompass.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * The waypoints of every world this client has been in, and what can be done to them. Knows nothing
 * of the game or of the file it is kept in: {@link CompassWaypoints} reads it in and writes it out.
 */
@OnlyIn(Dist.CLIENT)
public final class CompassWaypointStore {

	private static final String WAYPOINTS_FIELD = "waypoints";
	private static final String PENDING_FIELD = "pendingXaeroRemovals";

	/**
	 * A copy left behind in the minimap when its record was removed while the minimap did not have the
	 * world it went into open, to be taken out when it next does.
	 */
	public record PendingRemoval(String xaeroWorld, int x, int z) {
	}

	private final List<CompassWaypoint> waypoints = new ArrayList<CompassWaypoint>();
	private final List<PendingRemoval> pendingRemovals = new ArrayList<PendingRemoval>();
	/** Changes whenever the list does, so that anything drawn from it can tell when to look again. */
	private int revision;

	public int getRevision() {
		return revision;
	}

	public int size() {
		return waypoints.size();
	}

	public List<PendingRemoval> getPendingRemovals() {
		return List.copyOf(pendingRemovals);
	}

	public void addPendingRemoval(PendingRemoval removal) {
		if (!pendingRemovals.contains(removal)) {
			pendingRemovals.add(removal);
		}
	}

	public void removePendingRemoval(PendingRemoval removal) {
		pendingRemovals.remove(removal);
	}

	/** Every waypoint of the given world, in every dimension of it, newest first. */
	public List<CompassWaypoint> inWorld(String worldKey) {
		final List<CompassWaypoint> found = new ArrayList<CompassWaypoint>();
		for (CompassWaypoint waypoint : waypoints) {
			if (waypoint.isInWorld(worldKey)) {
				found.add(waypoint);
			}
		}
		found.sort(Comparator.comparingLong(CompassWaypoint::getCreatedAt).reversed());
		return found;
	}

	/** Every waypoint of the given dimension of the given world, newest first. */
	public List<CompassWaypoint> in(String worldKey, ResourceLocation dimension) {
		final List<CompassWaypoint> found = new ArrayList<CompassWaypoint>();
		for (CompassWaypoint waypoint : waypoints) {
			if (waypoint.isIn(worldKey, dimension)) {
				found.add(waypoint);
			}
		}
		found.sort(Comparator.comparingLong(CompassWaypoint::getCreatedAt).reversed());
		return found;
	}

	/** The waypoint standing on the given column, or null where there is none. */
	public CompassWaypoint find(String worldKey, ResourceLocation dimension, int x, int z) {
		for (CompassWaypoint waypoint : waypoints) {
			if (waypoint.isAt(worldKey, dimension, x, z)) {
				return waypoint;
			}
		}
		return null;
	}

	/**
	 * Adds a waypoint, unless one already stands on its column, in which case that one is answered
	 * with instead: the same place located twice is one place. Once a world holds more than the given
	 * number the oldest of them go, so that a long run of searching does not fill the file for good.
	 *
	 * @return the waypoint now standing there, whether it was just added or was there already
	 */
	public CompassWaypoint add(CompassWaypoint waypoint, int maxPerWorld) {
		final CompassWaypoint existing = find(waypoint.getWorldKey(), waypoint.getDimension(), waypoint.getX(), waypoint.getZ());
		if (existing != null) {
			return existing;
		}
		waypoints.add(waypoint);
		revision++;

		if (maxPerWorld > 0) {
			final List<CompassWaypoint> inWorld = inWorld(waypoint.getWorldKey());
			// Newest first, so what runs past the limit is the oldest
			for (int i = maxPerWorld; i < inWorld.size(); i++) {
				waypoints.remove(inWorld.get(i));
			}
		}
		return waypoint;
	}

	public boolean remove(CompassWaypoint waypoint) {
		final boolean removed = waypoints.remove(waypoint);
		if (removed) {
			revision++;
		}
		return removed;
	}

	/** Takes every waypoint of the given world out, and answers with what was taken. */
	public List<CompassWaypoint> removeWorld(String worldKey) {
		final List<CompassWaypoint> removed = inWorld(worldKey);
		if (!removed.isEmpty()) {
			waypoints.removeAll(removed);
			revision++;
		}
		return removed;
	}

	public void setShownOnHud(CompassWaypoint waypoint, boolean shown) {
		if (waypoint.isShownOnHud() != shown) {
			waypoint.setShownOnHud(shown);
			revision++;
		}
	}

	public void setXaeroWorld(CompassWaypoint waypoint, String xaeroWorld) {
		waypoint.setXaeroWorld(xaeroWorld);
		revision++;
	}

	public JsonObject toJson() {
		final JsonArray array = new JsonArray();
		for (CompassWaypoint waypoint : waypoints) {
			array.add(waypoint.toJson());
		}
		final JsonArray pending = new JsonArray();
		for (PendingRemoval removal : pendingRemovals) {
			final JsonObject json = new JsonObject();
			json.addProperty("xaeroWorld", removal.xaeroWorld());
			json.addProperty("x", removal.x());
			json.addProperty("z", removal.z());
			pending.add(json);
		}
		final JsonObject root = new JsonObject();
		root.add(WAYPOINTS_FIELD, array);
		if (!pendingRemovals.isEmpty()) {
			root.add(PENDING_FIELD, pending);
		}
		return root;
	}

	/** Reads a file back in, leaving out whatever in it does not read as a waypoint. */
	public void readFrom(JsonElement root) {
		waypoints.clear();
		pendingRemovals.clear();
		revision++;
		if (root == null || !root.isJsonObject()) {
			return;
		}
		final JsonElement array = root.getAsJsonObject().get(WAYPOINTS_FIELD);
		if (array != null && array.isJsonArray()) {
			for (JsonElement element : array.getAsJsonArray()) {
				final CompassWaypoint waypoint = CompassWaypoint.fromJson(element);
				if (waypoint != null && find(waypoint.getWorldKey(), waypoint.getDimension(), waypoint.getX(), waypoint.getZ()) == null) {
					waypoints.add(waypoint);
				}
			}
		}
		final JsonElement pending = root.getAsJsonObject().get(PENDING_FIELD);
		if (pending != null && pending.isJsonArray()) {
			for (JsonElement element : pending.getAsJsonArray()) {
				if (!element.isJsonObject()) {
					continue;
				}
				final JsonObject json = element.getAsJsonObject();
				final JsonElement world = json.get("xaeroWorld");
				if (world != null && world.isJsonPrimitive() && json.has("x") && json.has("z") && json.get("x").isJsonPrimitive() && json.get("z").isJsonPrimitive()) {
					addPendingRemoval(new PendingRemoval(world.getAsString(), json.get("x").getAsInt(), json.get("z").getAsInt()));
				}
			}
		}
	}

}
