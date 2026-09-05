package com.chaosthedude.explorerscompass.client;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.chaosthedude.explorerscompass.items.ExplorersCompassItem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.ModList;

/**
 * Mirrors the waypoints the compass records into Xaero's Minimap, and keeps the two in step.
 *
 * <p>That mod is not a dependency and publishes no artifact to build against, so it is reached
 * through reflection and switched off for good the first time anything it needs turns out to be
 * missing. Its waypoints have since been rehoused under a different set of classes, so there are two
 * ways in and the one the installed version answers to is worked out once. The newer one is the path
 * the minimap takes to place its own death markers:
 *
 * <pre>
 * world = session.getWaypointsManager().getWorldManager().getCurrentWorld();
 * world.getCurrentWaypointSet().add(new Waypoint(x, y, z, name, symbol, color));
 * session.getWaypointsManager().getWorldManagerIO().saveWorld(world);
 * </pre>
 *
 * <p>and the older one is what its waypoint screen used to do:
 *
 * <pre>
 * manager = session.getWaypointsManager();
 * manager.getWaypoints().getList().add(new Waypoint(x, y, z, name, symbol, color));
 * manager.updateWaypoints();
 * </pre>
 *
 * <p>The record the compass works from is its own — see {@link CompassWaypoints} — and what goes
 * into the minimap is a copy of it. Each copy is made with whatever visibility the player asked for,
 * which by default keeps it off the minimap's own drawing of the world, since the direction strip
 * marks the same place already; a copy the player deletes in the minimap takes the record with it,
 * and a record removed here takes its copy out of the minimap, as soon as the minimap has the world
 * it went into open.
 */
@OnlyIn(Dist.CLIENT)
public class XaeroMinimapIntegration {

	private static final String MOD_ID = "xaerominimap";
	private static final String SESSION_CLASS = "xaero.common.XaeroMinimapSession";
	private static final String WAYPOINT_CLASS = "xaero.common.minimap.waypoints.Waypoint";

	/** Whether the minimap still holds the copy of a waypoint. */
	public enum MirrorState {
		PRESENT,
		MISSING,
		/** The minimap does not have the world the copy went into open, so nothing can be said. */
		UNKNOWN
	}

	private static boolean unavailable;
	private static Method getCurrentSession;
	private static Method getWaypointsManager;
	private static Method getCurrentContainerAndWorldID;
	private static Constructor<?> waypointConstructor;
	private static Method waypointGetX;
	private static Method waypointGetZ;
	private static Method waypointGetVisibilityType;
	private static Method waypointSetVisibilityType;
	private static Method waypointIsDisabled;
	private static Method waypointSetDisabled;

	// The way in the current versions answer to, and what tells the two of them apart
	private static Method getWorldManager;
	private static Method getCurrentWorld;
	private static Method getCurrentWaypointSet;
	private static Method getIterableWaypointSets;
	private static Method waypointSetGetWaypoints;
	private static Method waypointSetAdd;
	private static Method waypointSetRemove;
	private static Method getWorldManagerIO;
	private static Method saveWorld;

	// The way the versions before the waypoints were rehoused answer to
	private static Method managerGetWaypoints;
	private static Method managerGetCurrentWorld;
	private static Method worldGetSets;
	private static Method getList;
	private static Method updateWaypoints;

	private XaeroMinimapIntegration() {
	}

	/** Whether the minimap is installed, wanted, and has not yet turned out to be one this cannot work with. */
	public static boolean isActive() {
		return !unavailable && ConfigHandler.CLIENT.createXaeroWaypoints.get() && ModList.get().isLoaded(MOD_ID);
	}

	/** Whether the minimap is installed at all, whatever the player has asked of it. */
	public static boolean isInstalled() {
		return ModList.get().isLoaded(MOD_ID);
	}

	/**
	 * The minimap's own id for the world it currently has open, or null while it has none. Recorded
	 * on every waypoint mirrored, so that the copy can be found again in the right world.
	 */
	public static String currentWorldId() {
		if (!isActive()) {
			return null;
		}
		try {
			resolve();
			final Object manager = manager();
			return manager == null ? null : (String) getCurrentContainerAndWorldID.invoke(manager);
		} catch (Throwable t) {
			giveUp(t);
			return null;
		}
	}

	/**
	 * Puts a copy of the waypoint into the minimap's current set, unless one already stands where it
	 * does, and draws it the way the player asked. Answers whether the waypoint has been dealt with,
	 * rather than the minimap not being ready for it yet, in which case it is asked again later.
	 */
	public static boolean mirror(CompassWaypoint waypoint) {
		if (!isActive() || waypoint == null) {
			return true;
		}
		try {
			resolve();
			final Object manager = manager();
			final Object world = manager == null ? null : currentWorld(manager);
			if (world == null) {
				return false;
			}
			final String worldId = (String) getCurrentContainerAndWorldID.invoke(manager);
			if (worldId != null && worldId.equals(waypoint.getXaeroWorld())) {
				// Mirrored into this very world already; whether the copy is still there is the
				// reconciliation's business, not something to check on every tick
				return true;
			}

			// A waypoint already standing there is taken as the copy, and left as it is: it may well be
			// one the player made, and how they have it drawn is theirs to decide
			Object copy = findCopy(manager, world, waypoint);
			if (copy == null) {
				copy = newWaypoint(waypoint);
				applyDisplay(copy, ConfigHandler.CLIENT.xaeroWaypointDisplay.get());
				addToCurrentSet(manager, world, copy);
				save(manager, world);
				ExplorersCompass.LOGGER.info("Created a waypoint for " + waypoint.getName() + " at " + waypoint.getX() + ", " + waypoint.getZ() + " in Xaero's Minimap");
			}
			CompassWaypoints.markMirrored(waypoint, worldId);
			return true;
		} catch (Throwable t) {
			giveUp(t);
			return true;
		}
	}

	/**
	 * Takes the minimap's copy of the waypoint out, where the minimap has the world it went into
	 * open. Answers whether that could be done now; a copy in a world the minimap does not have open
	 * is left to be taken out when it does.
	 */
	public static boolean removeMirror(CompassWaypoint waypoint) {
		return removeMirror(waypoint.getXaeroWorld(), waypoint.getX(), waypoint.getZ());
	}

	/** Takes the copy standing on the given column of the given minimap world out, as above. */
	public static boolean removeMirror(String xaeroWorld, int x, int z) {
		if (xaeroWorld == null) {
			return true;
		}
		if (!isActive()) {
			return false;
		}
		try {
			resolve();
			final Object manager = manager();
			final Object world = manager == null ? null : currentWorld(manager);
			if (world == null || !xaeroWorld.equals(getCurrentContainerAndWorldID.invoke(manager))) {
				return false;
			}

			boolean removed = false;
			for (Object set : setsOf(manager, world)) {
				final Object copy = findIn(set, x, z);
				if (copy != null) {
					removeFromSet(set, copy);
					removed = true;
				}
			}
			if (removed) {
				save(manager, world);
			}
			return true;
		} catch (Throwable t) {
			giveUp(t);
			return true;
		}
	}

	/** Whether the minimap still holds the copy of the given waypoint. */
	public static MirrorState mirrorState(CompassWaypoint waypoint) {
		if (!isActive() || waypoint.getXaeroWorld() == null) {
			return MirrorState.UNKNOWN;
		}
		try {
			resolve();
			final Object manager = manager();
			final Object world = manager == null ? null : currentWorld(manager);
			if (world == null || !waypoint.getXaeroWorld().equals(getCurrentContainerAndWorldID.invoke(manager))) {
				return MirrorState.UNKNOWN;
			}
			return findCopy(manager, world, waypoint) != null ? MirrorState.PRESENT : MirrorState.MISSING;
		} catch (Throwable t) {
			giveUp(t);
			return MirrorState.UNKNOWN;
		}
	}

	/**
	 * Looks over the copies in the world the minimap has open, and answers with the records whose
	 * copies are gone: a copy the player deleted in the minimap takes its record with it. Asked to,
	 * it also draws every copy still there the way the player currently asks; that is only ever done
	 * at the player's own asking, so that a copy they have changed in the minimap is not changed back
	 * behind them every few seconds.
	 */
	public static List<CompassWaypoint> reconcile(List<CompassWaypoint> waypoints, boolean applyDisplay) {
		final List<CompassWaypoint> orphaned = new ArrayList<CompassWaypoint>();
		if (!isActive()) {
			return orphaned;
		}
		try {
			resolve();
			final Object manager = manager();
			final Object world = manager == null ? null : currentWorld(manager);
			if (world == null) {
				return orphaned;
			}
			final String worldId = (String) getCurrentContainerAndWorldID.invoke(manager);
			if (worldId == null) {
				return orphaned;
			}

			final XaeroWaypointDisplay display = ConfigHandler.CLIENT.xaeroWaypointDisplay.get();
			boolean changed = false;
			for (CompassWaypoint waypoint : waypoints) {
				if (!worldId.equals(waypoint.getXaeroWorld())) {
					continue;
				}
				final Object copy = findCopy(manager, world, waypoint);
				if (copy == null) {
					orphaned.add(waypoint);
				} else if (applyDisplay && applyDisplay(copy, display)) {
					changed = true;
				}
			}
			if (changed) {
				save(manager, world);
			}
		} catch (Throwable t) {
			giveUp(t);
		}
		return orphaned;
	}

	// The minimap, through whichever door it answers to

	private static Object manager() throws ReflectiveOperationException {
		final Object session = getCurrentSession.invoke(null);
		return session == null ? null : getWaypointsManager.invoke(session);
	}

	private static Object currentWorld(Object manager) throws ReflectiveOperationException {
		if (getWorldManager != null) {
			final Object worldManager = getWorldManager.invoke(manager);
			return worldManager == null ? null : getCurrentWorld.invoke(worldManager);
		}
		return managerGetCurrentWorld == null ? manager : managerGetCurrentWorld.invoke(manager);
	}

	/** Every set of waypoints the world holds, or the current one alone where the version lists no others. */
	@SuppressWarnings("unchecked")
	private static Iterable<Object> setsOf(Object manager, Object world) throws ReflectiveOperationException {
		if (getWorldManager != null) {
			return (Iterable<Object>) getIterableWaypointSets.invoke(world);
		}
		if (worldGetSets != null && managerGetCurrentWorld != null) {
			final Map<?, ?> sets = (Map<?, ?>) worldGetSets.invoke(world);
			return new ArrayList<Object>(sets.values());
		}
		final Object current = managerGetWaypoints.invoke(manager);
		return current == null ? List.of() : List.of(current);
	}

	@SuppressWarnings("unchecked")
	private static Iterable<Object> waypointsOf(Object set) throws ReflectiveOperationException {
		return (Iterable<Object>) (getWorldManager != null ? waypointSetGetWaypoints.invoke(set) : getList.invoke(set));
	}

	private static void addToCurrentSet(Object manager, Object world, Object copy) throws ReflectiveOperationException {
		if (getWorldManager != null) {
			waypointSetAdd.invoke(getCurrentWaypointSet.invoke(world), copy);
		} else {
			asList(getList.invoke(managerGetWaypoints.invoke(manager))).add(copy);
		}
	}

	private static void removeFromSet(Object set, Object copy) throws ReflectiveOperationException {
		if (getWorldManager != null) {
			waypointSetRemove.invoke(set, copy);
		} else {
			asList(getList.invoke(set)).remove(copy);
		}
	}

	/**
	 * Writes the world back out. Saving is a step of its own in the current versions: the call the
	 * older way ends on still exists in them but no longer does anything at all, so a waypoint added
	 * through it showed until the game was closed and was then gone, having never reached the file.
	 */
	private static void save(Object manager, Object world) throws ReflectiveOperationException {
		if (getWorldManager != null) {
			saveWorld.invoke(getWorldManagerIO.invoke(manager), world);
		} else {
			updateWaypoints.invoke(manager);
		}
	}

	private static Object findCopy(Object manager, Object world, CompassWaypoint waypoint) throws ReflectiveOperationException {
		for (Object set : setsOf(manager, world)) {
			final Object copy = findIn(set, waypoint.getX(), waypoint.getZ());
			if (copy != null) {
				return copy;
			}
		}
		return null;
	}

	private static Object findIn(Object set, int x, int z) throws ReflectiveOperationException {
		for (Object candidate : waypointsOf(set)) {
			if ((int) waypointGetX.invoke(candidate) == x && (int) waypointGetZ.invoke(candidate) == z) {
				return candidate;
			}
		}
		return null;
	}

	/** Draws the copy the given way, and answers whether anything about it had to change. */
	private static boolean applyDisplay(Object copy, XaeroWaypointDisplay display) throws ReflectiveOperationException {
		boolean changed = false;
		if ((int) waypointGetVisibilityType.invoke(copy) != display.getXaeroVisibilityType()) {
			waypointSetVisibilityType.invoke(copy, display.getXaeroVisibilityType());
			changed = true;
		}
		if ((boolean) waypointIsDisabled.invoke(copy) != display.isDisabledInXaero()) {
			waypointSetDisabled.invoke(copy, display.isDisabledInXaero());
			changed = true;
		}
		return changed;
	}

	/**
	 * Works out which of the two shapes the installed minimap has, once. Everything is resolved
	 * before any of it is published, so that a version answering to neither is retried from the top
	 * rather than left half described.
	 */
	private static void resolve() throws ReflectiveOperationException {
		if (getCurrentSession != null) {
			return;
		}

		final Class<?> sessionClass = Class.forName(SESSION_CLASS);
		final Class<?> waypointClass = Class.forName(WAYPOINT_CLASS);
		final Method currentSession = sessionClass.getMethod("getCurrentSession");
		final Method waypointsManager = sessionClass.getMethod("getWaypointsManager");
		final Class<?> managerClass = waypointsManager.getReturnType();

		final Method worldManager = findMethod(managerClass, "getWorldManager");
		if (worldManager != null) {
			getCurrentWorld = worldManager.getReturnType().getMethod("getCurrentWorld");
			final Class<?> worldClass = getCurrentWorld.getReturnType();
			getCurrentWaypointSet = worldClass.getMethod("getCurrentWaypointSet");
			getIterableWaypointSets = worldClass.getMethod("getIterableWaypointSets");
			final Class<?> waypointSetClass = getCurrentWaypointSet.getReturnType();
			waypointSetGetWaypoints = waypointSetClass.getMethod("getWaypoints");
			waypointSetAdd = waypointSetClass.getMethod("add", waypointClass);
			waypointSetRemove = waypointSetClass.getMethod("remove", waypointClass);
			getWorldManagerIO = managerClass.getMethod("getWorldManagerIO");
			saveWorld = getWorldManagerIO.getReturnType().getMethod("saveWorld", worldClass);
		} else {
			managerGetWaypoints = managerClass.getMethod("getWaypoints");
			getList = managerGetWaypoints.getReturnType().getMethod("getList");
			updateWaypoints = managerClass.getMethod("updateWaypoints");
			// Listing every set is worth having but not worth refusing the whole minimap over
			managerGetCurrentWorld = findMethod(managerClass, "getCurrentWorld");
			worldGetSets = managerGetCurrentWorld == null ? null : findMethod(managerGetCurrentWorld.getReturnType(), "getSets");
		}

		waypointConstructor = waypointClass.getConstructor(int.class, int.class, int.class, String.class, String.class, int.class);
		waypointGetX = waypointClass.getMethod("getX");
		waypointGetZ = waypointClass.getMethod("getZ");
		waypointGetVisibilityType = waypointClass.getMethod("getVisibilityType");
		waypointSetVisibilityType = waypointClass.getMethod("setVisibilityType", int.class);
		waypointIsDisabled = waypointClass.getMethod("isDisabled");
		waypointSetDisabled = waypointClass.getMethod("setDisabled", boolean.class);
		getCurrentContainerAndWorldID = managerClass.getMethod("getCurrentContainerAndWorldID");
		getWaypointsManager = waypointsManager;
		getWorldManager = worldManager;
		getCurrentSession = currentSession;
	}

	private static Method findMethod(Class<?> owner, String name) {
		try {
			return owner.getMethod(name);
		} catch (NoSuchMethodException e) {
			return null;
		}
	}

	/** A different version of the minimap, or none of this working at all, must not break the HUD. */
	private static void giveUp(Throwable t) {
		unavailable = true;
		ExplorersCompass.LOGGER.warn("Could not work with the waypoints of Xaero's Minimap, no further attempts will be made", t);
	}

	/**
	 * The colour is given as an index into the minimap's own list of them, not as a colour itself.
	 * The structure's own height where the compass recorded one, the player's otherwise.
	 */
	private static Object newWaypoint(CompassWaypoint waypoint) throws ReflectiveOperationException {
		final LocalPlayer player = Minecraft.getInstance().player;
		final int y = waypoint.getY() != ExplorersCompassItem.UNKNOWN_Y ? waypoint.getY() : (player != null ? player.getBlockY() : 64);
		return waypointConstructor.newInstance(waypoint.getX(), y, waypoint.getZ(), waypoint.getName(), symbolFor(waypoint.getName()), waypoint.getColorIndex());
	}

	@SuppressWarnings("unchecked")
	private static List<Object> asList(Object list) {
		return (List<Object>) list;
	}

	/** Xaero shows one or two characters on the waypoint marker itself. */
	private static String symbolFor(String name) {
		return name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase();
	}

}
