package com.chaosthedude.explorerscompass.client;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.chaosthedude.explorerscompass.items.ExplorersCompassItem;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.ModList;

/**
 * Adds a waypoint to Xaero's Minimap for every location the compass finds.
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
 */
@OnlyIn(Dist.CLIENT)
public class XaeroMinimapIntegration {

	private static final String MOD_ID = "xaerominimap";
	private static final String SESSION_CLASS = "xaero.common.XaeroMinimapSession";
	private static final String WAYPOINT_CLASS = "xaero.common.minimap.waypoints.Waypoint";

	private static boolean unavailable;
	private static Method getCurrentSession;
	private static Method getWaypointsManager;
	private static Constructor<?> waypointConstructor;
	private static Method waypointGetX;
	private static Method waypointGetZ;

	// The way in the current versions answer to, and what tells the two of them apart
	private static Method getWorldManager;
	private static Method getCurrentWorld;
	private static Method getCurrentWaypointSet;
	private static Method waypointSetGetWaypoints;
	private static Method waypointSetAdd;
	private static Method getWorldManagerIO;
	private static Method saveWorld;

	// The way the versions before the waypoints were rehoused answer to
	private static Method managerGetWaypoints;
	private static Method getList;
	private static Method updateWaypoints;

	// Where a waypoint was created last, so that holding the compass does not pile up copies
	private static int lastX;
	private static int lastZ;
	private static boolean hasLast;

	private XaeroMinimapIntegration() {
	}

	/**
	 * Creates a waypoint for whatever the given compass is pointing at, unless one was already
	 * created for that location.
	 */
	public static void createWaypointForLocation(Player player, ExplorersCompassItem compass, ItemStack stack) {
		if (unavailable || !ConfigHandler.CLIENT.createXaeroWaypoints.get() || !ModList.get().isLoaded(MOD_ID)) {
			return;
		}

		final int x = compass.getFoundStructureX(stack);
		final int z = compass.getFoundStructureZ(stack);
		if (hasLast && lastX == x && lastZ == z) {
			return;
		}

		final String name = compass.getSearchTarget(stack).getPrettyName(compass.getTargetKey(stack));
		try {
			resolve();

			final Object session = getCurrentSession.invoke(null);
			final Object manager = session == null ? null : getWaypointsManager.invoke(session);
			if (manager == null) {
				return;
			}

			// The structure's own height when the compass recorded one, the player's otherwise
			final int structureY = compass.getFoundStructureY(stack);
			final int y = structureY != ExplorersCompassItem.UNKNOWN_Y ? structureY : player.getBlockY();

			final boolean settled = getWorldManager != null
					? addToCurrentWorld(manager, x, y, z, name)
					: addToCurrentWaypointSet(manager, x, y, z, name);
			if (!settled) {
				// No world is loaded in the minimap yet, so try again later rather than giving up
				return;
			}

			lastX = x;
			lastZ = z;
			hasLast = true;
		} catch (Throwable t) {
			// A different version of the minimap, or none of this working at all, must not break the HUD
			unavailable = true;
			ExplorersCompass.LOGGER.warn("Could not create a waypoint in Xaero's Minimap, no further attempts will be made", t);
		}
	}

	/** Forgets which location a waypoint was last created for, so a repeat of it is allowed again. */
	public static void reset() {
		hasLast = false;
	}

	/**
	 * Adds the waypoint to the set the minimap is currently showing and writes the world it belongs
	 * to back out.
	 *
	 * <p>Saving is a step of its own here. The call the older way ends on still exists in these
	 * versions but no longer does anything at all, so a waypoint added through it showed until the
	 * game was closed and was then gone, having never reached the file it belongs in.
	 *
	 * @return whether the location has been dealt with, rather than the minimap not being ready for it
	 */
	private static boolean addToCurrentWorld(Object manager, int x, int y, int z, String name) throws ReflectiveOperationException {
		final Object worldManager = getWorldManager.invoke(manager);
		final Object world = worldManager == null ? null : getCurrentWorld.invoke(worldManager);
		final Object waypointSet = world == null ? null : getCurrentWaypointSet.invoke(world);
		if (waypointSet == null) {
			return false;
		}

		if (!containsWaypointAt((Iterable<?>) waypointSetGetWaypoints.invoke(waypointSet), x, z)) {
			waypointSetAdd.invoke(waypointSet, newWaypoint(x, y, z, name));
			saveWorld.invoke(getWorldManagerIO.invoke(manager), world);
			logCreated(name, x, z);
		}
		return true;
	}

	/**
	 * Adds the waypoint straight to the list the minimap keeps them in, the way the versions before
	 * the waypoints were rehoused expect.
	 *
	 * @return whether the location has been dealt with, rather than the minimap not being ready for it
	 */
	private static boolean addToCurrentWaypointSet(Object manager, int x, int y, int z, String name) throws ReflectiveOperationException {
		final Object waypointSet = managerGetWaypoints.invoke(manager);
		if (waypointSet == null) {
			return false;
		}

		final List<Object> waypoints = asWaypointList(getList.invoke(waypointSet));
		if (!containsWaypointAt(waypoints, x, z)) {
			waypoints.add(newWaypoint(x, y, z, name));
			updateWaypoints.invoke(manager);
			logCreated(name, x, z);
		}
		return true;
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
			final Class<?> waypointSetClass = getCurrentWaypointSet.getReturnType();
			waypointSetGetWaypoints = waypointSetClass.getMethod("getWaypoints");
			waypointSetAdd = waypointSetClass.getMethod("add", waypointClass);
			getWorldManagerIO = managerClass.getMethod("getWorldManagerIO");
			saveWorld = getWorldManagerIO.getReturnType().getMethod("saveWorld", worldClass);
		} else {
			managerGetWaypoints = managerClass.getMethod("getWaypoints");
			getList = managerGetWaypoints.getReturnType().getMethod("getList");
			updateWaypoints = managerClass.getMethod("updateWaypoints");
		}

		waypointConstructor = waypointClass.getConstructor(int.class, int.class, int.class, String.class, String.class, int.class);
		waypointGetX = waypointClass.getMethod("getX");
		waypointGetZ = waypointClass.getMethod("getZ");
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

	/** The colour is given as an index into the minimap's own list of them, not as a colour itself. */
	private static Object newWaypoint(int x, int y, int z, String name) throws ReflectiveOperationException {
		return waypointConstructor.newInstance(x, y, z, name, symbolFor(name), ConfigHandler.CLIENT.xaeroWaypointColor.get());
	}

	@SuppressWarnings("unchecked")
	private static List<Object> asWaypointList(Object list) {
		return (List<Object>) list;
	}

	private static boolean containsWaypointAt(Iterable<?> waypoints, int x, int z) throws ReflectiveOperationException {
		for (Object waypoint : waypoints) {
			if ((int) waypointGetX.invoke(waypoint) == x && (int) waypointGetZ.invoke(waypoint) == z) {
				return true;
			}
		}
		return false;
	}

	private static void logCreated(String name, int x, int z) {
		ExplorersCompass.LOGGER.info("Created a waypoint for " + name + " at " + x + ", " + z);
	}

	/** Xaero shows one or two characters on the waypoint marker itself. */
	private static String symbolFor(String name) {
		return name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase();
	}

}
