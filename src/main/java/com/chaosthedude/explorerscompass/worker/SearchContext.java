package com.chaosthedude.explorerscompass.worker;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.RandomStringUtils;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.chaosthedude.explorerscompass.items.ExplorersCompassItem;
import com.chaosthedude.explorerscompass.util.StructureUtils;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Everything one search is fixed by: where it started, who asked for it, what it may cover, and the
 * compass it answers on.
 *
 * <p>All of that is the same for every worker the search is made of, so it lives here instead of
 * being copied into each of them, and the compass is written to from here rather than through
 * whichever worker happened to finish last.
 *
 * <p>A context is created for each search and thrown away with it, so it is also what identifies
 * one: the manager that runs the workers is kept per player and reused, and an id belonging to it
 * would read the same for every search that player ever starts.
 */
public class SearchContext {

	// How wide the cells the already located places are bucketed into are, in blocks, and the shift
	// that divides by that. Wide enough that the furthest two places can be apart and still count as
	// the same one fits inside a cell and the ones around it.
	private static final int LOCATED_CELL_SIZE = 512;
	private static final int LOCATED_CELL_SHIFT = 9;

	private final String id = RandomStringUtils.random(8, "0123456789abcdef");

	private final ServerLevel level;
	private final Player player;
	private final ItemStack stack;
	private final BlockPos startPos;

	/**
	 * The locations already located, which this search passes over, and which the location it settles
	 * on is added to.
	 */
	private final List<BlockPos> prevPos;

	/**
	 * The same locations, bucketed by where they are, so that asking whether one of them is already
	 * known walks the handful near the location asked about rather than all of them. A search asks
	 * this for every location it samples, and there can be thousands of locations to walk.
	 *
	 * <p>Built once, from the locations the search started with, and read from every thread the search
	 * runs on. The location the search settles on is added to {@link #prevPos} for the compass to
	 * remember, but not to this: by then there is nothing left to ask.
	 */
	private final Long2ObjectMap<List<BlockPos>> locatedCells;

	private final boolean isGroup;
	private final boolean ignoreNearStart;

	// Snapshots of the settings this search runs under. The sampling loop reads the radius for every
	// location, and a config lookup walks the config tree on every call, which is far too slow for
	// that; a search also ought to finish under the settings it was started with.
	private final int maxRadius;
	private final boolean displayCoordinates;

	public SearchContext(ServerLevel level, Player player, ItemStack stack, BlockPos startPos, List<BlockPos> prevPos, boolean isGroup, boolean ignoreNearStart) {
		this(level, player, stack, startPos, prevPos, isGroup, ignoreNearStart, ConfigHandler.GENERAL.maxRadius.get(), ConfigHandler.GENERAL.displayCoordinates.get());
	}

	/** Takes the settings ready made rather than reading them, for callers that have no config. */
	SearchContext(ServerLevel level, Player player, ItemStack stack, BlockPos startPos, List<BlockPos> prevPos, boolean isGroup, boolean ignoreNearStart, int maxRadius, boolean displayCoordinates) {
		this.level = level;
		this.player = player;
		this.stack = stack;
		this.startPos = startPos;
		this.prevPos = prevPos;
		this.isGroup = isGroup;
		this.ignoreNearStart = ignoreNearStart;
		this.maxRadius = maxRadius;
		this.displayCoordinates = displayCoordinates;

		locatedCells = new Long2ObjectOpenHashMap<List<BlockPos>>();
		for (BlockPos pos : prevPos) {
			final long cell = cellKey(pos.getX() >> LOCATED_CELL_SHIFT, pos.getZ() >> LOCATED_CELL_SHIFT);
			List<BlockPos> located = locatedCells.get(cell);
			if (located == null) {
				located = new ArrayList<BlockPos>();
				locatedCells.put(cell, located);
			}
			located.add(pos);
		}
	}

	/** Identifies this search in the log. */
	public String getId() {
		return id;
	}

	public ServerLevel getLevel() {
		return level;
	}

	public BlockPos getStartPos() {
		return startPos;
	}

	public int getMaxRadius() {
		return maxRadius;
	}

	/** Squared horizontal distance in blocks from where this search started. */
	public long distanceSqrFromStart(int x, int z) {
		return StructureUtils.getHorizontalDistanceSqrToLocation(startPos, x, z);
	}

	/**
	 * Whether a location has already been located by an earlier search, and should be passed over so
	 * that searching again finds a different instance. Locations right where this search started
	 * count as well, so that a search for a further instance does not answer with what is being
	 * stood in.
	 *
	 * @param sameLocationDistance how near a location has to be to an already located one to count
	 *     as the same find, which what is being searched for decides: a structure is a single place,
	 *     while a biome is a region a search can sample thousands of times over.
	 */
	public boolean isAlreadyLocated(BlockPos pos, int sameLocationDistance) {
		if (ignoreNearStart && isSameLocation(startPos, pos, sameLocationDistance)) {
			return true;
		}

		if (locatedCells.isEmpty()) {
			return false;
		}

		// Only the cells the location asked about can reach into are walked. Worked out from the corners
		// of what it reaches rather than assumed to be the cell it is in and the ones around it, so that
		// this stays right whatever distance counts as the same location.
		final int minCellX = (int) (((long) pos.getX() - sameLocationDistance) >> LOCATED_CELL_SHIFT);
		final int maxCellX = (int) (((long) pos.getX() + sameLocationDistance) >> LOCATED_CELL_SHIFT);
		final int minCellZ = (int) (((long) pos.getZ() - sameLocationDistance) >> LOCATED_CELL_SHIFT);
		final int maxCellZ = (int) (((long) pos.getZ() + sameLocationDistance) >> LOCATED_CELL_SHIFT);
		for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
			for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
				final List<BlockPos> located = locatedCells.get(cellKey(cellX, cellZ));
				if (located == null) {
					continue;
				}

				for (BlockPos prev : located) {
					if (isSameLocation(prev, pos, sameLocationDistance)) {
						return true;
					}
				}
			}
		}

		return false;
	}

	private boolean isSameLocation(BlockPos other, BlockPos pos, int distance) {
		return StructureUtils.getHorizontalDistanceSqrToLocation(other, pos.getX(), pos.getZ()) <= (long) distance * distance;
	}

	private static long cellKey(int cellX, int cellZ) {
		return ((long) cellX << 32) | (cellZ & 0xFFFFFFFFL);
	}

	/**
	 * Whether the compass this search answers on is still there to answer on. It can be emptied while
	 * the search runs, and everything written to it after that would go nowhere.
	 */
	public boolean holdsCompass() {
		return !stack.isEmpty() && stack.getItem() == ExplorersCompass.explorersCompass;
	}

	/** Keeps the radius the compass reports in step with how far the search has looked. */
	void reportRadius(int radius) {
		if (holdsCompass()) {
			compass().setSearchRadius(stack, radius, player);
		}
	}

	/** Puts the location the search settled on onto the compass. */
	void reportLocated(BlockPos pos, ResourceLocation key, int totalSamples) {
		// Remember this location, so that searching again looks for a different instance. Only the
		// location the search answers with is remembered: one a worker turned up and another beat is
		// not somewhere the compass ever pointed.
		prevPos.add(pos);
		if (!holdsCompass()) {
			ExplorersCompass.LOGGER.error("Search " + id + ": found invalid compass after successful search");
			return;
		}

		compass().succeed(player, stack, key, isGroup, pos.getX(), pos.getZ(), pos.getY(), level.dimension().location(), prevPos, totalSamples, displayCoordinates);
	}

	/** Tells the compass the search located nothing. */
	void reportNotFound(int radius, int totalSamples) {
		if (!holdsCompass()) {
			ExplorersCompass.LOGGER.error("Search " + id + ": found invalid compass after failed search");
			return;
		}

		compass().fail(player, stack, radius, totalSamples);
	}

	private ExplorersCompassItem compass() {
		return (ExplorersCompassItem) stack.getItem();
	}

}
