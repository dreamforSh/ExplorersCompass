package com.chaosthedude.explorerscompass.worker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.commons.lang3.RandomStringUtils;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.chaosthedude.explorerscompass.items.ExplorersCompassItem;
import com.chaosthedude.explorerscompass.util.ItemUtils;
import com.chaosthedude.explorerscompass.util.StructureUtils;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
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

	/**
	 * The compass this search answers on. Replaced when it turns out to have been swapped for a copy
	 * of itself — see {@link #compassStack} — so that the search goes on answering on the one the
	 * player actually holds.
	 */
	private ItemStack stack;

	/** The inventory slot the compass was last found in, or -1 when it was found in none. */
	private int heldSlot = -1;

	private final BlockPos startPos;

	/**
	 * The locations already located, which this search passes over, and which the location it settles
	 * on is added to.
	 */
	private final List<BlockPos> prevPos;

	/**
	 * How many locations this search started out passing over. Kept apart from {@link #prevPos}, which
	 * the compass can be told to forget while the search runs: what the search passes over was settled
	 * when it started, and that is what decides how it ends when it finds nothing.
	 */
	private final int collectedAtStart;

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
		collectedAtStart = prevPos.size();
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

	/**
	 * The locations earlier searches already answered with, which this one passes over. Read when a
	 * search is being set up, before it has anything of its own to add to them.
	 */
	public List<BlockPos> getAlreadyLocated() {
		return Collections.unmodifiableList(prevPos);
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
	 * the search runs, or pointed somewhere else, and everything written to it after that would either
	 * go nowhere or undo what the player chose.
	 */
	public boolean holdsCompass() {
		return compassStack() != null;
	}

	/**
	 * The compass this search answers on, wherever the player now holds it, or null when there is none
	 * left for it to answer on.
	 *
	 * <p>Moving a stack between slots — dropping it, shift-clicking it, picking it up onto the cursor
	 * — splits it, which hands the new slot a copy and empties the stack this search was started on. A
	 * client in creative mode replaces it outright with a copy of its own, leaving the one this search
	 * holds intact but held by nobody. Either way the compass the player holds is a different stack
	 * from then on, and the search id it carries is what tells which one it is.
	 *
	 * <p>The id is also what says the compass is still this search's to write to. Pointing it somewhere
	 * else takes the id off, and from then on what this search settles on is not written onto it.
	 */
	ItemStack compassStack() {
		if (player == null) {
			return carriesSearch(stack, id) ? stack : null;
		}

		// Asked before every turn the search takes, and the compass is almost always still where it was
		// found the last time, so that is looked at before the whole inventory is
		final Inventory inventory = player.getInventory();
		if (heldSlot >= 0 && heldSlot < inventory.getContainerSize() && inventory.getItem(heldSlot) == stack && carriesSearch(stack, id)) {
			return stack;
		}

		final ItemStack found = findCompass(stack, id, inventory, player.containerMenu.getCarried());
		if (found != null) {
			stack = found;
		}
		heldSlot = -1;
		for (int slot = 0; found != null && slot < inventory.getContainerSize(); slot++) {
			if (inventory.getItem(slot) == found) {
				heldSlot = slot;
				break;
			}
		}
		return found;
	}

	/**
	 * Finds the compass the given search answers on among what a player holds: of the compasses
	 * carrying the search's id, the stack it was started on when that is one of them, and otherwise the
	 * first. Failing both, the stack it was started on is still answered on as long as it carries the
	 * id, the way it always was: a client in creative mode takes a stack out of its slot while it is
	 * being moved, and only puts its own copy of it back once it is let go of.
	 */
	static ItemStack findCompass(ItemStack original, String searchId, Container inventory, ItemStack carried) {
		ItemStack copy = null;
		for (int slot = 0; slot <= inventory.getContainerSize(); slot++) {
			final ItemStack held = slot < inventory.getContainerSize() ? inventory.getItem(slot) : carried;
			if (!carriesSearch(held, searchId)) {
				continue;
			}
			if (held == original) {
				return original;
			}
			if (copy == null) {
				copy = held;
			}
		}
		if (copy != null) {
			return copy;
		}
		return carriesSearch(original, searchId) ? original : null;
	}

	/** Whether a stack is a compass the given search is running on. */
	private static boolean carriesSearch(ItemStack stack, String searchId) {
		return stack != null && searchId.equals(ItemUtils.getSearchId(stack));
	}

	/** Keeps the radius the compass reports in step with how far the search has looked. */
	void reportRadius(int radius) {
		final ItemStack compass = compassStack();
		if (compass != null) {
			compass().setSearchRadius(compass, radius, player);
		}
	}

	/** Puts the location the search settled on onto the compass. */
	void reportLocated(BlockPos pos, ResourceLocation key, int totalSamples) {
		// Remember this location, so that searching again looks for a different instance. Only the
		// location the search answers with is remembered: one a worker turned up and another beat is
		// not somewhere the compass ever pointed.
		prevPos.add(pos);
		final ItemStack compass = compassStack();
		if (compass == null) {
			ExplorersCompass.LOGGER.error("Search " + id + ": found invalid compass after successful search");
			return;
		}

		compass().succeed(player, compass, key, isGroup, pos.getX(), pos.getZ(), pos.getY(), level.dimension().location(), prevPos, totalSamples, displayCoordinates);
	}

	/** Tells the compass the search located nothing. */
	void reportNotFound(int radius, int totalSamples) {
		final ItemStack compass = compassStack();
		if (compass == null) {
			ExplorersCompass.LOGGER.error("Search " + id + ": found invalid compass after failed search");
			return;
		}

		if (collectedAtStart > 0) {
			// A search that was passing over what was already collected and still found nothing has
			// collected everything within its reach, so the only further instance there is to offer is
			// the closest one over again. Ending on "not found" instead would leave the compass with
			// nothing located, and one with nothing located cannot even be asked for a further
			// instance, so it would dead-end there for as long as the collected locations were kept.
			// Decided by what the search started out passing over rather than by what is still
			// remembered: a compass told to forget its collected locations partway through has had them
			// forgotten already, and starting over is what it asked for.
			ExplorersCompass.LOGGER.info("Search " + id + ": located nothing beyond the " + collectedAtStart + " location(s) already collected, so they are forgotten and the search starts over from the closest one");
			compass().startOver(level, player, startPos, compass);
			return;
		}

		compass().fail(player, compass, radius, totalSamples);
	}

	/**
	 * Tells the compass the search was stopped before it had an answer, so that it does not go on
	 * reading as searching. Only a compass this search is still running on is touched: one that has
	 * moved on to something else since is not this search's to change.
	 */
	void reportStopped() {
		final ItemStack compass = compassStack();
		if (compass != null) {
			ExplorersCompass.LOGGER.info("Search " + id + ": stopped before it located anything");
			compass().interrupt(compass);
		}
	}

	/**
	 * Forgets the locations already collected, after the compass was told to forget them while this
	 * search was running. The search goes on passing over them, since that was settled when it
	 * started, but what it settles on is no longer added to them: otherwise it would write the very
	 * list the compass was just told to forget back onto it.
	 */
	public void forgetCollected() {
		prevPos.clear();
	}

	private ExplorersCompassItem compass() {
		return ExplorersCompass.explorersCompass;
	}

}
