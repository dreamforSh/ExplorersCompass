package com.chaosthedude.explorerscompass.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.chaosthedude.explorerscompass.items.ExplorersCompassItem;
import com.chaosthedude.explorerscompass.util.SearchTarget;
import com.google.gson.JsonParser;

import net.minecraft.resources.ResourceLocation;

/**
 * Holds the waypoint store to what it says about itself: what goes in comes back out of the file
 * the same, one place is one waypoint however often it is located, and a world never keeps more
 * than it was told to.
 */
class CompassWaypointStoreTest {

	private static final String WORLD = "save:New World";
	private static final String OTHER_WORLD = "server:play.example.org";
	private static final ResourceLocation OVERWORLD = ResourceLocation.withDefaultNamespace("overworld");
	private static final ResourceLocation NETHER = ResourceLocation.withDefaultNamespace("the_nether");
	private static final ResourceLocation VILLAGE = ResourceLocation.withDefaultNamespace("village_plains");

	@Test
	void whatIsWrittenIsWhatIsReadBack() {
		final CompassWaypointStore store = new CompassWaypointStore();
		final CompassWaypoint sent = new CompassWaypoint(WORLD, OVERWORLD, 120, 71, -340, "Plains Village", SearchTarget.STRUCTURE, VILLAGE, 1700000000000L, 6);
		store.add(sent, 0);
		store.setShownOnHud(sent, false);
		store.setXaeroWorld(sent, "Multiplayer_example/dim%0");
		store.addPendingRemoval(new CompassWaypointStore.PendingRemoval("Multiplayer_example/dim%-1", 5, 6));

		final CompassWaypointStore read = new CompassWaypointStore();
		read.readFrom(JsonParser.parseString(store.toJson().toString()));

		final CompassWaypoint received = read.find(WORLD, OVERWORLD, 120, -340);
		assertNotNull(received, "the waypoint did not come back");
		assertEquals(71, received.getY());
		assertEquals("Plains Village", received.getName());
		assertEquals(SearchTarget.STRUCTURE, received.getSearchTarget());
		assertEquals(VILLAGE, received.getTargetKey());
		assertEquals(1700000000000L, received.getCreatedAt());
		assertEquals(6, received.getColorIndex());
		assertFalse(received.isShownOnHud(), "being taken off the strip was not kept");
		assertEquals("Multiplayer_example/dim%0", received.getXaeroWorld());
		assertEquals(List.of(new CompassWaypointStore.PendingRemoval("Multiplayer_example/dim%-1", 5, 6)), read.getPendingRemovals());
	}

	@Test
	void aHeightNeverRecordedStaysUnrecorded() {
		final CompassWaypointStore store = new CompassWaypointStore();
		store.add(new CompassWaypoint(WORLD, OVERWORLD, 1, ExplorersCompassItem.UNKNOWN_Y, 2, "Igloo", SearchTarget.STRUCTURE, VILLAGE, 1L, 6), 0);
		final CompassWaypointStore read = new CompassWaypointStore();
		read.readFrom(JsonParser.parseString(store.toJson().toString()));
		assertEquals(ExplorersCompassItem.UNKNOWN_Y, read.find(WORLD, OVERWORLD, 1, 2).getY());
	}

	@Test
	void onePlaceIsOneWaypointHoweverOftenItIsLocated() {
		final CompassWaypointStore store = new CompassWaypointStore();
		final CompassWaypoint first = store.add(new CompassWaypoint(WORLD, OVERWORLD, 10, 64, 10, "Village", SearchTarget.STRUCTURE, VILLAGE, 1L, 6), 0);
		final CompassWaypoint again = store.add(new CompassWaypoint(WORLD, OVERWORLD, 10, 70, 10, "Village", SearchTarget.STRUCTURE, VILLAGE, 2L, 3), 0);
		assertSame(first, again, "locating the same column again made a second waypoint");
		assertEquals(1, store.size());

		// The same column in another dimension, or another world, is another place
		store.add(new CompassWaypoint(WORLD, NETHER, 10, 64, 10, "Fortress", SearchTarget.STRUCTURE, VILLAGE, 3L, 6), 0);
		store.add(new CompassWaypoint(OTHER_WORLD, OVERWORLD, 10, 64, 10, "Village", SearchTarget.STRUCTURE, VILLAGE, 4L, 6), 0);
		assertEquals(3, store.size());
		assertEquals(2, store.inWorld(WORLD).size());
		assertEquals(1, store.in(WORLD, OVERWORLD).size());
	}

	@Test
	void aWorldKeepsNoMoreThanItWasToldToAndLosesTheOldestFirst() {
		final CompassWaypointStore store = new CompassWaypointStore();
		for (int i = 0; i < 5; i++) {
			store.add(new CompassWaypoint(WORLD, OVERWORLD, i * 100, 64, 0, "Village " + i, SearchTarget.STRUCTURE, VILLAGE, 1000L + i, 6), 3);
		}
		// Another world's waypoints do not count against this one's
		store.add(new CompassWaypoint(OTHER_WORLD, OVERWORLD, 0, 64, 0, "Elsewhere", SearchTarget.STRUCTURE, VILLAGE, 1L, 6), 3);

		final List<CompassWaypoint> kept = store.inWorld(WORLD);
		assertEquals(3, kept.size());
		// Newest first, and the two oldest are the ones that went
		assertEquals("Village 4", kept.get(0).getName());
		assertEquals("Village 2", kept.get(2).getName());
		assertNull(store.find(WORLD, OVERWORLD, 0, 0));
		assertNotNull(store.find(OTHER_WORLD, OVERWORLD, 0, 0));
	}

	@Test
	void removingAWorldAnswersWithWhatItHeldAndLeavesTheRestAlone() {
		final CompassWaypointStore store = new CompassWaypointStore();
		store.add(new CompassWaypoint(WORLD, OVERWORLD, 1, 64, 1, "A", SearchTarget.STRUCTURE, VILLAGE, 1L, 6), 0);
		store.add(new CompassWaypoint(WORLD, NETHER, 2, 64, 2, "B", SearchTarget.STRUCTURE, VILLAGE, 2L, 6), 0);
		store.add(new CompassWaypoint(OTHER_WORLD, OVERWORLD, 3, 64, 3, "C", SearchTarget.BIOME, VILLAGE, 3L, 6), 0);

		final int before = store.getRevision();
		final List<CompassWaypoint> removed = store.removeWorld(WORLD);
		assertEquals(2, removed.size());
		assertEquals(1, store.size());
		assertEquals("C", store.inWorld(OTHER_WORLD).get(0).getName());
		assertTrue(store.getRevision() > before, "a change that nothing drawn from the store could notice");
	}

	@Test
	void anEntryThatIsNotAWaypointIsLeftOutRatherThanRefusingTheFile() {
		final CompassWaypointStore store = new CompassWaypointStore();
		store.readFrom(JsonParser.parseString("{\"waypoints\":[{\"world\":\"save:x\",\"dimension\":\"minecraft:overworld\",\"x\":1,\"z\":2,\"name\":\"Kept\"},{\"world\":\"save:x\"},\"junk\",{\"world\":\"save:x\",\"dimension\":\"not a location!\",\"x\":1,\"z\":3}]}"));
		assertEquals(1, store.size());
		final CompassWaypoint kept = store.find("save:x", OVERWORLD, 1, 2);
		assertNotNull(kept);
		// What the entry left unsaid falls back to something sensible
		assertEquals(ExplorersCompassItem.UNKNOWN_Y, kept.getY());
		assertEquals(SearchTarget.STRUCTURE, kept.getSearchTarget());
		assertNull(kept.getTargetKey());
		assertTrue(kept.isShownOnHud());
		assertEquals(6, kept.getColorIndex());
	}

	@Test
	void theColourIsOneOfTheSixteenHoweverItWasWritten() {
		final CompassWaypoint waypoint = new CompassWaypoint(WORLD, OVERWORLD, 0, 0, 0, "X", SearchTarget.STRUCTURE, VILLAGE, 0L, 22);
		assertEquals(6, waypoint.getColorIndex());
		assertEquals(0xFFAA00, waypoint.getColor(), "index 6 is gold in the game's list of text colours");
	}

}
