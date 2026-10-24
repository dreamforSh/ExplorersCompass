package com.chaosthedude.explorerscompass.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.registry.ExplorersCompassRegistry;
import com.chaosthedude.explorerscompass.util.CompassData;
import com.chaosthedude.explorerscompass.util.CompassState;
import com.chaosthedude.explorerscompass.util.ItemUtils;
import com.chaosthedude.explorerscompass.util.SearchTarget;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;

/**
 * How a search keeps track of the compass it answers on, and what it leaves on one it can no longer
 * answer on: the stack it was started on can be split, replaced, or pointed elsewhere while it runs.
 */
class SearchCompassTest {

	private static final BlockPos START = new BlockPos(0, 64, 0);
	private static final ResourceLocation TARGET = ResourceLocation.fromNamespaceAndPath("test", "target");
	private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-00000000c0de");

	@BeforeAll
	static void resolveCompass() {
		// Resolved by common setup in the game; the registries are already filled here
		if (ExplorersCompass.explorersCompass == null) {
			ExplorersCompass.explorersCompass = ExplorersCompassRegistry.EXPLORERS_COMPASS.get();
		}
	}

	@AfterEach
	void forgetSearches() {
		SearchService.forgetPlayer(PLAYER);
	}

	/**
	 * A compass marked as running the given search, the way starting one leaves it, or as searching
	 * with no id at all when given none, the way one saved mid-search loads.
	 */
	private static ItemStack searchingCompass(String searchId) {
		final ItemStack stack = new ItemStack(ExplorersCompass.explorersCompass);
		ItemUtils.setData(stack, CompassData.EMPTY.searching(SearchTarget.STRUCTURE, TARGET));
		if (searchId != null) {
			ItemUtils.setSearchId(stack, searchId);
		}
		return stack;
	}

	private static SearchContext contextOn(ItemStack stack, List<BlockPos> collected) {
		final SearchContext context = new SearchContext(null, null, stack, START, new ArrayList<BlockPos>(collected), false, false, 10000, false);
		ItemUtils.setSearchId(stack, context.getId());
		return context;
	}

	/** A worker with nothing to do, standing in for one a search is still running. */
	private static final class IdleWorker extends SearchWorker {

		private IdleWorker(SearchContext context) {
			super(context, 1000);
		}

		@Override
		protected boolean doSample() {
			return false;
		}

		@Override
		protected String getName() {
			return "IdleWorker";
		}

		@Override
		protected boolean shouldLogRadius() {
			return false;
		}

	}

	private static SearchWorkerManager runningSearch(SearchWorkerManager manager, SearchContext context) {
		manager.createWorkers(context, List.of(new IdleWorker(context)));
		return manager;
	}

	private static CompassState stateOf(ItemStack stack) {
		return ItemUtils.getData(stack).state();
	}

	@Test
	void theStackASearchWasStartedOnIsAnsweredOnWhileItIsHeld() {
		final ItemStack original = searchingCompass("cafe0001");
		final ItemStack duplicate = original.copy();
		final SimpleContainer inventory = new SimpleContainer(9);
		inventory.setItem(1, duplicate);
		inventory.setItem(4, original);

		assertSame(original, SearchContext.findCompass(original, "cafe0001", inventory, ItemStack.EMPTY));
	}

	@Test
	void aSplitStackIsFollowedToWhereItWasMoved() {
		// Shift-clicking, dropping and picking a stack up onto the cursor all split it: the new slot is
		// handed a copy, and the stack the search was started on is left empty
		final ItemStack original = searchingCompass("cafe0002");
		final SimpleContainer inventory = new SimpleContainer(9);
		inventory.setItem(0, original);
		final ItemStack moved = original.split(1);
		inventory.setItem(6, moved);

		assertTrue(original.isEmpty());
		assertSame(moved, SearchContext.findCompass(original, "cafe0002", inventory, ItemStack.EMPTY));

		// Held on the cursor counts as well
		inventory.setItem(6, ItemStack.EMPTY);
		assertSame(moved, SearchContext.findCompass(original, "cafe0002", inventory, moved));
	}

	@Test
	void aCopyHandedOverByACreativeClientIsFollowed() {
		// The stack the search holds is left intact, but in no slot
		final ItemStack original = searchingCompass("cafe0003");
		final ItemStack copy = original.copy();
		final SimpleContainer inventory = new SimpleContainer(9);
		inventory.setItem(2, copy);

		assertSame(copy, SearchContext.findCompass(original, "cafe0003", inventory, ItemStack.EMPTY));
	}

	@Test
	void theOriginalIsKeptWhileNoCopyHasTurnedUpYet() {
		// A creative client takes the stack out of its slot while it is being moved, and only hands its
		// own copy back once it is let go of
		final ItemStack original = searchingCompass("cafe0004");

		assertSame(original, SearchContext.findCompass(original, "cafe0004", new SimpleContainer(9), ItemStack.EMPTY));
	}

	@Test
	void nothingIsAnsweredOnOnceTheCompassIsGoneOrHasMovedOn() {
		final SimpleContainer inventory = new SimpleContainer(9);

		// Dropped: the copy is out in the world, and the original was emptied
		final ItemStack dropped = searchingCompass("cafe0005");
		inventory.setItem(0, dropped);
		dropped.split(1);
		assertNull(SearchContext.findCompass(dropped, "cafe0005", inventory, ItemStack.EMPTY));

		// Pointed somewhere else, which takes the search's id off it
		final ItemStack repointed = searchingCompass("cafe0006");
		inventory.setItem(1, repointed);
		ItemUtils.clearSearchId(repointed);
		assertNull(SearchContext.findCompass(repointed, "cafe0006", inventory, ItemStack.EMPTY));

		// Running another search by now: neither that compass nor its search are this one's
		final ItemStack other = searchingCompass("cafe0007");
		inventory.setItem(2, other);
		assertNull(SearchContext.findCompass(repointed, "cafe0006", inventory, ItemStack.EMPTY));
	}

	@Test
	void stoppingARunningSearchTakesItsCompassBackToIdle() {
		final ItemStack stack = searchingCompass(null);
		final SearchContext context = contextOn(stack, List.of());
		final SearchWorkerManager manager = runningSearch(new SearchWorkerManager(), context);

		manager.stop();

		assertEquals(CompassState.INACTIVE, stateOf(stack));
		assertNull(ItemUtils.getSearchId(stack));
		// What it was aimed at stays, since a stopped search has nothing to say about that
		assertEquals(TARGET, ItemUtils.getData(stack).targetKeyOrNull());
	}

	@Test
	void stoppingLeavesACompassThatHasMovedOnAlone() {
		final ItemStack stack = searchingCompass(null);
		final SearchContext context = contextOn(stack, List.of());
		final SearchWorkerManager manager = runningSearch(new SearchWorkerManager(), context);
		// A newer search was started on it since
		ItemUtils.setSearchId(stack, "cafe0008");

		manager.stop();

		assertEquals(CompassState.SEARCHING, stateOf(stack));
		assertEquals("cafe0008", ItemUtils.getSearchId(stack));
	}

	@Test
	void stoppingASearchThatHasEndedWritesNothing() {
		final ItemStack stack = searchingCompass(null);
		final SearchContext context = contextOn(stack, List.of());
		final SearchWorkerManager manager = runningSearch(new SearchWorkerManager(), context);
		manager.clear();
		// What the search ended on, as succeeding writes it
		ItemUtils.setData(stack, ItemUtils.getData(stack).found(TARGET, 100, 100, 64, null, 1));
		ItemUtils.setSearchId(stack, context.getId());

		manager.stop();

		assertEquals(CompassState.FOUND, stateOf(stack));
	}

	@Test
	void onlyARunningSearchIsReportedAsRunning() {
		final ItemStack stack = searchingCompass(null);
		final SearchContext context = contextOn(stack, List.of());
		final SearchWorkerManager manager = runningSearch(SearchService.getWorkerManager(PLAYER), context);

		assertSame(context, SearchService.getRunningSearch(PLAYER, context.getId()));
		assertNull(SearchService.getRunningSearch(PLAYER, "cafe0009"));
		assertNull(SearchService.getRunningSearch(UUID.randomUUID(), context.getId()));

		manager.clear();
		assertNull(SearchService.getRunningSearch(PLAYER, context.getId()));
	}

	@Test
	void aCompassLeftSearchingWithNoSearchBehindItIsRecovered() {
		final ItemStack stack = searchingCompass(null);
		final SearchContext context = contextOn(stack, List.of());
		final SearchWorkerManager manager = runningSearch(SearchService.getWorkerManager(PLAYER), context);

		// Still running, so it is left to finish
		assertFalse(ExplorersCompass.explorersCompass.recoverOrphanedSearch(stack, PLAYER));
		assertEquals(CompassState.SEARCHING, stateOf(stack));

		// Its search stopped without reaching it, as it does once the compass was dropped or put away
		manager.clear();
		assertTrue(ExplorersCompass.explorersCompass.recoverOrphanedSearch(stack, PLAYER));
		assertEquals(CompassState.INACTIVE, stateOf(stack));
		assertNull(ItemUtils.getSearchId(stack));

		// Saved mid-search and loaded again, which leaves no id behind since none is saved
		final ItemStack reloaded = searchingCompass(null);
		assertTrue(ExplorersCompass.explorersCompass.recoverOrphanedSearch(reloaded, PLAYER));
		assertEquals(CompassState.INACTIVE, stateOf(reloaded));

		// Anything not searching is not touched
		final ItemStack found = new ItemStack(ExplorersCompass.explorersCompass);
		ItemUtils.setData(found, CompassData.EMPTY.found(TARGET, 1, 1, 64, null, 1));
		assertFalse(ExplorersCompass.explorersCompass.recoverOrphanedSearch(found, PLAYER));
		assertEquals(CompassState.FOUND, stateOf(found));
	}

	@Test
	void forgettingTheCollectedLocationsKeepsThemOutOfTheAnswer() {
		final BlockPos collected = new BlockPos(4000, 0, 4000);
		final ItemStack stack = searchingCompass(null);
		final SearchContext context = contextOn(stack, List.of(collected));

		context.forgetCollected();

		// What the search settles on is no longer added to them, so they are not written back
		assertTrue(context.getAlreadyLocated().isEmpty());
		// The search goes on passing over them, since that was settled when it started
		assertTrue(context.isAlreadyLocated(collected, 32));
	}

}
