package com.chaosthedude.explorerscompass.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.chaosthedude.explorerscompass.client.WaypointReconciliation.MapWaypoint;
import com.chaosthedude.explorerscompass.client.WaypointReconciliation.Result;
import com.chaosthedude.explorerscompass.util.SearchTarget;

import net.minecraft.resources.ResourceLocation;

/**
 * Holds the reconciliation to its table of cases.
 *
 * <p>Every way the player can edit a waypoint in the minimap has to come out as the one thing the
 * compass then does to its record, and a case read wrong is a record forgotten that should have
 * followed its copy, or a waypoint of the player's taken in twice. None of it fails loudly.
 */
class WaypointReconciliationTest {

	private static final String WORLD = "save:New World";
	private static final String XAERO_WORLD = "Singleplayer_New World/dim%0";
	private static final String ELSEWHERE = "Singleplayer_New World/dim%-1";
	private static final ResourceLocation OVERWORLD = ResourceLocation.withDefaultNamespace("overworld");
	private static final ResourceLocation VILLAGE = ResourceLocation.withDefaultNamespace("village_plains");

	@Test
	void aRecordWhoseCopyStandsWhereItWasIsLeftAlone() {
		final CompassWaypoint record = mirrored("Village", 100, 64, 200, 6);

		final Result result = WaypointReconciliation.reconcile(List.of(record), XAERO_WORLD, List.of(copy("Village", 100, 64, 200, 6)));

		assertTrue(result.isEmpty(), "nothing differed, yet something was found to do");
	}

	@Test
	void aCopyDeletedInTheMinimapOrphansItsRecord() {
		final CompassWaypoint record = mirrored("Village", 100, 64, 200, 6);

		final Result result = WaypointReconciliation.reconcile(List.of(record), XAERO_WORLD, List.of());

		assertEquals(List.of(record), result.orphaned());
		assertTrue(result.moved().isEmpty());
	}

	@Test
	void aRecordNeverMirroredHereIsNotOrphanedByHavingNoCopy() {
		// Mirrored into another of the minimap's worlds, or into none: this world has nothing to say about it
		final CompassWaypoint elsewhere = mirrored("Fortress", 1, 64, 1, 6);
		elsewhere.setXaeroWorld(ELSEWHERE);
		final CompassWaypoint nowhere = new CompassWaypoint(WORLD, OVERWORLD, 2, 64, 2, "Igloo", SearchTarget.STRUCTURE, VILLAGE, 1L, 6);

		final Result result = WaypointReconciliation.reconcile(List.of(elsewhere, nowhere), XAERO_WORLD, List.of());

		assertTrue(result.isEmpty());
	}

	@Test
	void aCopyMovedInTheMinimapTakesItsRecordAlongByName() {
		final CompassWaypoint record = mirrored("Village", 100, 64, 200, 6);

		final Result result = WaypointReconciliation.reconcile(List.of(record), XAERO_WORLD, List.of(copy("Village", 130, 70, 210, 6)));

		assertTrue(result.orphaned().isEmpty(), "a moved copy was taken for a deleted one");
		assertEquals(130, result.moved().get(record).x());
		assertEquals(210, result.moved().get(record).z());
		// And the copy the record followed is not also taken in as a new waypoint
		assertTrue(result.unrecorded().isEmpty());
	}

	@Test
	void aCopyOfAnotherNameIsNotWhereARecordWentButSomethingNew() {
		final CompassWaypoint record = mirrored("Village", 100, 64, 200, 6);

		final Result result = WaypointReconciliation.reconcile(List.of(record), XAERO_WORLD, List.of(copy("Home", 130, 70, 210, 3)));

		assertEquals(List.of(record), result.orphaned());
		assertEquals(1, result.unrecorded().size());
		assertEquals("Home", result.unrecorded().get(0).name());
	}

	@Test
	void aCopyRenamedOrRecolouredInTheMinimapChangesItsRecord() {
		final CompassWaypoint renamed = mirrored("Village", 100, 64, 200, 6);
		final CompassWaypoint recoloured = mirrored("Temple", 300, 64, 400, 6);
		final CompassWaypoint untouched = mirrored("Igloo", 500, 64, 600, 6);

		final Result result = WaypointReconciliation.reconcile(List.of(renamed, recoloured, untouched), XAERO_WORLD,
				List.of(copy("My Village", 100, 64, 200, 6), copy("Temple", 300, 64, 400, 11), copy("Igloo", 500, 64, 600, 6)));

		assertEquals(2, result.changed().size());
		assertEquals("My Village", result.changed().get(renamed).name());
		assertEquals(11, result.changed().get(recoloured).colorIndex());
		assertTrue(result.orphaned().isEmpty());
		assertTrue(result.unrecorded().isEmpty());
	}

	@Test
	void aCopyStandingOnARecordNeverMirroredHereIsAdoptedAsItsMirror() {
		final CompassWaypoint record = new CompassWaypoint(WORLD, OVERWORLD, 100, 64, 200, "Village", SearchTarget.STRUCTURE, VILLAGE, 1L, 6);

		final Result result = WaypointReconciliation.reconcile(List.of(record), XAERO_WORLD, List.of(copy("Village", 100, 64, 200, 6)));

		assertSame(record, result.adopted().keySet().iterator().next());
		// Adopted rather than taken in again as a waypoint of the player's
		assertTrue(result.unrecorded().isEmpty());
		assertTrue(result.changed().isEmpty());
	}

	@Test
	void anAdoptedCopyThatSaysSomethingElseChangesTheRecordToo() {
		// The minimap is where the player edits, so what its waypoint is called wins
		final CompassWaypoint record = new CompassWaypoint(WORLD, OVERWORLD, 100, 64, 200, "Village", SearchTarget.STRUCTURE, VILLAGE, 1L, 6);

		final Result result = WaypointReconciliation.reconcile(List.of(record), XAERO_WORLD, List.of(copy("Home", 100, 64, 200, 2)));

		assertEquals(1, result.adopted().size());
		assertEquals("Home", result.changed().get(record).name());
	}

	@Test
	void aCopyOnNoRecordsColumnIsAWaypointThePlayerMade() {
		final CompassWaypoint record = mirrored("Village", 100, 64, 200, 6);

		final Result result = WaypointReconciliation.reconcile(List.of(record), XAERO_WORLD,
				List.of(copy("Village", 100, 64, 200, 6), copy("Mine", 5, 12, 5, 4), copy("Farm", 8, 64, 9, 10)));

		assertEquals(2, result.unrecorded().size());
		assertEquals("Mine", result.unrecorded().get(0).name());
		assertEquals("Farm", result.unrecorded().get(1).name());
	}

	@Test
	void twoCopiesOnOneColumnCountOnce() {
		// The minimap's own confusion, which must not be read in twice
		final Result result = WaypointReconciliation.reconcile(List.of(), XAERO_WORLD, List.of(copy("A", 1, 64, 1, 6), copy("B", 1, 70, 1, 6)));

		assertEquals(1, result.unrecorded().size());
		assertEquals("A", result.unrecorded().get(0).name());
	}

	@Test
	void aMovedCopyIsClaimedByOneRecordOnly() {
		// Two records of one name have both lost their copies, and only one copy of that name remains:
		// one follows it, and the other is gone
		final CompassWaypoint first = mirrored("Village", 100, 64, 200, 6);
		final CompassWaypoint second = mirrored("Village", 300, 64, 400, 6);

		final Result result = WaypointReconciliation.reconcile(List.of(first, second), XAERO_WORLD, List.of(copy("Village", 130, 64, 210, 6)));

		assertEquals(1, result.moved().size());
		assertEquals(1, result.orphaned().size());
		assertTrue(result.unrecorded().isEmpty());
	}

	@Test
	void theColourIsComparedAsOneOfTheSixteen() {
		// The minimap's colour is an index into the same sixteen; a record holds it wrapped already
		final CompassWaypoint record = mirrored("Village", 100, 64, 200, 6);

		final Result result = WaypointReconciliation.reconcile(List.of(record), XAERO_WORLD, List.of(copy("Village", 100, 64, 200, 22)));

		assertTrue(result.changed().isEmpty(), "22 wraps to 6, which is what the record already has");
	}

	private static CompassWaypoint mirrored(String name, int x, int y, int z, int color) {
		final CompassWaypoint waypoint = new CompassWaypoint(WORLD, OVERWORLD, x, y, z, name, SearchTarget.STRUCTURE, VILLAGE, 1L, color);
		waypoint.setXaeroWorld(XAERO_WORLD);
		return waypoint;
	}

	private static MapWaypoint copy(String name, int x, int y, int z, int color) {
		return new MapWaypoint(x, y, z, name, color, true);
	}

}
