package com.chaosthedude.explorerscompass.client;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.SocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.chaosthedude.explorerscompass.items.ExplorersCompassItem;
import com.chaosthedude.explorerscompass.util.SearchTarget;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * The waypoints this client keeps: one for every place the compass has located, in every world it
 * has been in. Kept in {@code config/explorerscompass/waypoints.json}, so that they survive across
 * sessions and across compasses; the ones belonging to other worlds simply do not show while the
 * player is not in them.
 *
 * <p>Each is marked on the direction strip and can be looked after on the waypoints screen. Where
 * Xaero's Minimap is installed, each is mirrored into it as well — see
 * {@link XaeroMinimapIntegration} — but the record here is the one the mod works from.
 */
@OnlyIn(Dist.CLIENT)
public final class CompassWaypoints {

	private static final String FILE_NAME = "waypoints.json";

	private static final CompassWaypointStore store = new CompassWaypointStore();
	private static boolean loaded;
	/** The key of the level last asked about, since it is asked for on every tick and never changes for one level. */
	private static ClientLevel keyedLevel;
	private static String keyedWorldKey;

	private CompassWaypoints() {
	}

	/**
	 * The key this client files the world it is in under: the folder a save lives in, or the address
	 * a server is joined by. Neither changes between sessions, and neither depends on any other mod
	 * being installed to be worked out. Null while not in any world.
	 */
	public static String currentWorldKey() {
		final Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			keyedLevel = null;
			return null;
		}
		if (mc.level == keyedLevel) {
			return keyedWorldKey;
		}
		keyedWorldKey = workOutWorldKey(mc);
		keyedLevel = mc.level;
		return keyedWorldKey;
	}

	private static String workOutWorldKey(Minecraft mc) {
		final IntegratedServer server = mc.getSingleplayerServer();
		if (server != null) {
			final Path folder = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
			return "save:" + folder.getFileName();
		}
		final ServerData serverData = mc.getCurrentServer();
		if (serverData != null && serverData.ip != null && !serverData.ip.isEmpty()) {
			return "server:" + serverData.ip.toLowerCase(Locale.ROOT);
		}
		// A world joined over the network with no entry in the server list, such as one on the LAN
		final ClientPacketListener connection = mc.getConnection();
		final SocketAddress address = connection != null ? connection.getConnection().getRemoteAddress() : null;
		return address != null ? "server:" + address : "unknown";
	}

	/** Changes whenever the waypoints do, so that whatever is drawn from them can tell when to look again. */
	public static int getRevision() {
		ensureLoaded();
		return store.getRevision();
	}

	/** Every waypoint of the world the player is in, in every dimension of it, newest first. */
	public static List<CompassWaypoint> inCurrentWorld() {
		ensureLoaded();
		final String worldKey = currentWorldKey();
		return worldKey == null ? List.of() : store.inWorld(worldKey);
	}

	/** Every waypoint of the dimension the player is in, newest first. */
	public static List<CompassWaypoint> inCurrentDimension() {
		ensureLoaded();
		final Minecraft mc = Minecraft.getInstance();
		final String worldKey = currentWorldKey();
		return worldKey == null || mc.level == null ? List.of() : store.in(worldKey, mc.level.dimension().location());
	}

	/**
	 * Records the place the given compass is pointing at, unless it is recorded already, and answers
	 * with the record either way. Called on every tick a located compass is looked at, so it has to
	 * be cheap when there is nothing to do.
	 */
	public static CompassWaypoint record(Player player, ExplorersCompassItem compass, ItemStack stack) {
		ensureLoaded();
		final String worldKey = currentWorldKey();
		if (worldKey == null) {
			return null;
		}

		final int x = compass.getFoundStructureX(stack);
		final int z = compass.getFoundStructureZ(stack);
		final ResourceLocation dimension = compass.getFoundDimension(stack) != null ? compass.getFoundDimension(stack) : player.level.dimension().location();
		final CompassWaypoint existing = store.find(worldKey, dimension, x, z);
		if (existing != null) {
			return existing;
		}

		final SearchTarget searchTarget = compass.getSearchTarget(stack);
		final ResourceLocation targetKey = compass.getTargetKey(stack);
		final String name = searchTarget.getPrettyName(targetKey);
		final CompassWaypoint waypoint = new CompassWaypoint(worldKey, dimension, x, compass.getFoundStructureY(stack), z, name, searchTarget, targetKey, System.currentTimeMillis(), ConfigHandler.CLIENT.xaeroWaypointColor.get());
		store.add(waypoint, ConfigHandler.CLIENT.maxWaypointsPerWorld.get());
		save();
		return waypoint;
	}

	/**
	 * Forgets a waypoint, and takes its copy out of the minimap. A copy in a world the minimap does
	 * not have open right now is noted down and taken out when it does.
	 */
	public static void remove(CompassWaypoint waypoint) {
		ensureLoaded();
		if (!store.remove(waypoint)) {
			return;
		}
		removeMirrorOrDefer(waypoint);
		save();
	}

	/** Forgets every waypoint of the world the player is in, and answers with what was forgotten. */
	public static List<CompassWaypoint> clearCurrentWorld() {
		ensureLoaded();
		final String worldKey = currentWorldKey();
		if (worldKey == null) {
			return List.of();
		}
		final List<CompassWaypoint> removed = store.removeWorld(worldKey);
		if (!removed.isEmpty()) {
			for (CompassWaypoint waypoint : removed) {
				removeMirrorOrDefer(waypoint);
			}
			save();
		}
		return removed;
	}

	private static void removeMirrorOrDefer(CompassWaypoint waypoint) {
		if (waypoint.getXaeroWorld() != null && !XaeroMinimapIntegration.removeMirror(waypoint)) {
			store.addPendingRemoval(new CompassWaypointStore.PendingRemoval(waypoint.getXaeroWorld(), waypoint.getX(), waypoint.getZ()));
		}
	}

	/**
	 * Puts every waypoint of the dimension the player is in that the minimap has no copy of into it,
	 * and draws the copies the way the player currently asks. What the tick does for the place the
	 * compass is pointing at, done for all of them at once, at the player's asking.
	 */
	public static int mirrorCurrentDimension() {
		ensureLoaded();
		int mirrored = 0;
		for (CompassWaypoint waypoint : inCurrentDimension()) {
			final String before = waypoint.getXaeroWorld();
			if (XaeroMinimapIntegration.mirror(waypoint) && !Objects.equals(before, waypoint.getXaeroWorld())) {
				mirrored++;
			}
		}
		return mirrored;
	}

	/**
	 * Keeps the records and the minimap's copies in step, as far as the world the minimap has open:
	 * a copy the player deleted in the minimap takes its record with it, and a copy whose record was
	 * removed while its world was not open is taken out now. Cheap enough to run every few seconds,
	 * which is how often it is.
	 */
	public static void reconcileWithXaero() {
		reconcileWithXaero(false);
	}

	/**
	 * As above, and where asked, also draws every copy the way the player currently asks — done at the
	 * player's own asking, from the waypoints screen, rather than on the clock.
	 */
	public static void reconcileWithXaero(boolean applyDisplay) {
		ensureLoaded();
		if (!XaeroMinimapIntegration.isActive()) {
			return;
		}
		final String xaeroWorld = XaeroMinimapIntegration.currentWorldId();
		if (xaeroWorld == null) {
			return;
		}

		boolean changed = false;
		for (CompassWaypointStore.PendingRemoval pending : store.getPendingRemovals()) {
			if (xaeroWorld.equals(pending.xaeroWorld()) && XaeroMinimapIntegration.removeMirror(pending.xaeroWorld(), pending.x(), pending.z())) {
				store.removePendingRemoval(pending);
				changed = true;
			}
		}
		for (CompassWaypoint orphan : XaeroMinimapIntegration.reconcile(inCurrentDimension(), applyDisplay)) {
			// Deleted in the minimap, which is as much a deletion as one made here
			if (store.remove(orphan)) {
				ExplorersCompass.LOGGER.info("Forgot the waypoint for " + orphan.getName() + " at " + orphan.getX() + ", " + orphan.getZ() + ": its copy was deleted in Xaero's Minimap");
				changed = true;
			}
		}
		if (changed) {
			save();
		}
	}

	public static void setShownOnHud(CompassWaypoint waypoint, boolean shown) {
		ensureLoaded();
		if (waypoint.isShownOnHud() != shown) {
			store.setShownOnHud(waypoint, shown);
			save();
		}
	}

	/** Notes which of the minimap's worlds a waypoint was mirrored into. */
	public static void markMirrored(CompassWaypoint waypoint, String xaeroWorld) {
		ensureLoaded();
		if (xaeroWorld != null && !xaeroWorld.equals(waypoint.getXaeroWorld())) {
			store.setXaeroWorld(waypoint, xaeroWorld);
			save();
		}
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
			store.readFrom(JsonParser.parseReader(reader));
		} catch (IOException | JsonParseException e) {
			ExplorersCompass.LOGGER.warn("Failed to read " + path + ", the waypoints start empty", e);
		}
	}

	private static void save() {
		final Path path = filePath();
		try {
			Files.createDirectories(path.getParent());
			try (Writer writer = Files.newBufferedWriter(path)) {
				writer.write(store.toJson().toString());
			}
		} catch (IOException e) {
			ExplorersCompass.LOGGER.warn("Failed to write " + path + ", the waypoints will not persist", e);
		}
	}

	private static Path filePath() {
		return FMLPaths.CONFIGDIR.get().resolve(ExplorersCompass.MODID).resolve(FILE_NAME);
	}

}
