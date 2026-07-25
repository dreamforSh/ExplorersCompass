package com.chaosthedude.explorerscompass.client;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.chaosthedude.explorerscompass.items.ExplorersCompassItem;
import com.chaosthedude.explorerscompass.util.StructureUtils;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.ModList;

/**
 * Adds a waypoint to Xaero's Minimap for every structure the compass locates.
 *
 * <p>That mod is not a dependency and publishes no artifact to build against, so it is reached
 * through reflection and switched off for good the first time anything it needs turns out to be
 * missing. The path used is the one its own waypoint screen takes:
 *
 * <pre>
 * XaeroMinimapSession.getCurrentSession().getWaypointsManager().getWaypoints().getList()
 *         .add(new Waypoint(x, y, z, name, symbol, color));
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
	private static Method getWaypoints;
	private static Method getList;
	private static Method updateWaypoints;
	private static Constructor<?> waypointConstructor;
	private static Method waypointGetX;
	private static Method waypointGetZ;

	// Where a waypoint was created last, so that holding the compass does not pile up copies
	private static int lastX;
	private static int lastZ;
	private static boolean hasLast;

	/**
	 * Creates a waypoint for the structure the given compass is pointing at, unless one was already
	 * created for that location.
	 */
	public static void createWaypointForLocatedStructure(Player player, ExplorersCompassItem compass, ItemStack stack) {
		if (unavailable || !ConfigHandler.CLIENT.createXaeroWaypoints.get() || !ModList.get().isLoaded(MOD_ID)) {
			return;
		}

		final int x = compass.getFoundStructureX(stack);
		final int z = compass.getFoundStructureZ(stack);
		if (hasLast && lastX == x && lastZ == z) {
			return;
		}

		final ResourceLocation structureKey = compass.getStructureKey(stack);
		final String name = StructureUtils.getPrettyStructureName(structureKey);
		try {
			if (!resolve()) {
				return;
			}

			final Object session = getCurrentSession.invoke(null);
			if (session == null) {
				return;
			}

			final Object manager = getWaypointsManager.invoke(session);
			final Object waypointSet = manager == null ? null : getWaypoints.invoke(manager);
			if (waypointSet == null) {
				// No world is loaded in the minimap yet, so try again later rather than giving up
				return;
			}

			final List<Object> waypoints = asWaypointList(getList.invoke(waypointSet));
			if (!containsWaypointAt(waypoints, x, z)) {
				waypoints.add(waypointConstructor.newInstance(x, player.getBlockY(), z, name, symbolFor(name), ConfigHandler.CLIENT.xaeroWaypointColor.get()));
				updateWaypoints.invoke(manager);
				ExplorersCompass.LOGGER.info("Created a waypoint for " + name + " at " + x + ", " + z);
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

	private static boolean resolve() throws ReflectiveOperationException {
		if (getCurrentSession != null) {
			return true;
		}

		final Class<?> sessionClass = Class.forName(SESSION_CLASS);
		final Class<?> waypointClass = Class.forName(WAYPOINT_CLASS);
		getCurrentSession = sessionClass.getMethod("getCurrentSession");
		getWaypointsManager = sessionClass.getMethod("getWaypointsManager");
		getWaypoints = getWaypointsManager.getReturnType().getMethod("getWaypoints");
		getList = getWaypoints.getReturnType().getMethod("getList");
		updateWaypoints = getWaypointsManager.getReturnType().getMethod("updateWaypoints");
		waypointConstructor = waypointClass.getConstructor(int.class, int.class, int.class, String.class, String.class, int.class);
		waypointGetX = waypointClass.getMethod("getX");
		waypointGetZ = waypointClass.getMethod("getZ");
		return true;
	}

	@SuppressWarnings("unchecked")
	private static List<Object> asWaypointList(Object list) {
		return (List<Object>) list;
	}

	private static boolean containsWaypointAt(List<Object> waypoints, int x, int z) throws ReflectiveOperationException {
		for (Object waypoint : waypoints) {
			if ((int) waypointGetX.invoke(waypoint) == x && (int) waypointGetZ.invoke(waypoint) == z) {
				return true;
			}
		}
		return false;
	}

	/** Xaero shows one or two characters on the waypoint marker itself. */
	private static String symbolFor(String name) {
		return name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase();
	}

}
