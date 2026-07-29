package com.chaosthedude.explorerscompass.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class SearchSchedulerTest {

	/** A search that counts its turns, and can be told how long each of them takes. */
	private static class FakeSlice implements SearchScheduler.SearchSlice {

		private final Clock clock;
		private final long millisPerTurn;
		private int turns;
		private int remainingTurns;
		private boolean canCarryOn = true;

		private FakeSlice(Clock clock, long millisPerTurn, int remainingTurns) {
			this.clock = clock;
			this.millisPerTurn = millisPerTurn;
			this.remainingTurns = remainingTurns;
		}

		@Override
		public boolean hasWork() {
			return remainingTurns > 0;
		}

		@Override
		public boolean doWork() {
			turns++;
			remainingTurns--;
			clock.nanos += nanos(millisPerTurn);
			return canCarryOn && remainingTurns > 0;
		}

	}

	/** A search that says it could carry on, but turns out to be over by the time it is asked again. */
	private static class VanishingSlice implements SearchScheduler.SearchSlice {

		private final Clock clock;
		private final long millisPerTurn;
		private int turns;

		private VanishingSlice(Clock clock, long millisPerTurn) {
			this.clock = clock;
			this.millisPerTurn = millisPerTurn;
		}

		@Override
		public boolean hasWork() {
			return turns == 0;
		}

		@Override
		public boolean doWork() {
			turns++;
			clock.nanos += nanos(millisPerTurn);
			return true;
		}

	}

	/** The scheduler measures in nanoseconds; the tests are written in the milliseconds they stand for. */
	private static class Clock {

		private long nanos;
		private int tick;

	}

	private static long nanos(long millis) {
		return TimeUnit.MILLISECONDS.toNanos(millis);
	}

	private static SearchScheduler scheduler(Clock clock, long budgetMillis) {
		return new SearchScheduler(() -> clock.nanos, () -> clock.tick, () -> nanos(budgetMillis));
	}

	@Test
	void aTickIsGivenUpOnceTheBudgetIsSpent() {
		final Clock clock = new Clock();
		final SearchScheduler scheduler = scheduler(clock, 10L);
		final FakeSlice slice = new FakeSlice(clock, 3L, 100);
		scheduler.addSlice(slice);

		int calls = 0;
		while (scheduler.doWork()) {
			calls++;
		}

		// Four turns take it to 12ms, which is where the budget of 10ms runs out
		assertEquals(4, slice.turns);
		assertEquals(4, calls);
	}

	@Test
	void everyTickBringsAFreshBudget() {
		final Clock clock = new Clock();
		final SearchScheduler scheduler = scheduler(clock, 10L);
		final FakeSlice slice = new FakeSlice(clock, 12L, 100);
		scheduler.addSlice(slice);

		for (int tick = 1; tick <= 5; tick++) {
			clock.tick = tick;
			while (scheduler.doWork()) {
				// The one turn this tick has room for
			}
		}

		assertEquals(5, slice.turns);
	}

	@Test
	void theTurnPassesOnAndIsPickedUpWhereItWasLeft() {
		final Clock clock = new Clock();
		// A budget of one turn per tick, so which search gets it is all that is being tested
		final SearchScheduler scheduler = scheduler(clock, 1L);
		final FakeSlice first = new FakeSlice(clock, 1L, 100);
		final FakeSlice second = new FakeSlice(clock, 1L, 100);
		final FakeSlice third = new FakeSlice(clock, 1L, 100);
		scheduler.addSlice(first);
		scheduler.addSlice(second);
		scheduler.addSlice(third);

		for (int tick = 1; tick <= 6; tick++) {
			clock.tick = tick;
			while (scheduler.doWork()) {
				// One turn per tick
			}
		}

		// Starting over from the front of the list on every tick would leave the last two searches
		// with nothing at all
		assertEquals(2, first.turns);
		assertEquals(2, second.turns);
		assertEquals(2, third.turns);
	}

	@Test
	void aSearchThatIsOverIsDroppedWithoutMovingTheTurnOn() {
		final Clock clock = new Clock();
		final SearchScheduler scheduler = scheduler(clock, 100L);
		final FakeSlice done = new FakeSlice(clock, 1L, 0);
		final FakeSlice running = new FakeSlice(clock, 1L, 3);
		scheduler.addSlice(done);
		scheduler.addSlice(running);

		assertTrue(scheduler.hasWork());
		assertEquals(1, scheduler.getSliceCount());

		while (scheduler.doWork()) {
			// Until the remaining search is done as well
		}

		assertEquals(0, done.turns);
		assertEquals(3, running.turns);
		assertFalse(scheduler.hasWork());
		assertEquals(0, scheduler.getSliceCount());
	}

	@Test
	void aSearchThatEndsPartwayThroughItsTurnDoesNotShortenTheNextOne() {
		final Clock clock = new Clock();
		final SearchScheduler scheduler = scheduler(clock, 100L);
		// Ends having used half of the two milliseconds a turn is worth
		final VanishingSlice vanishing = new VanishingSlice(clock, 1L);
		final FakeSlice next = new FakeSlice(clock, 1L, 100);
		final FakeSlice after = new FakeSlice(clock, 1L, 100);
		scheduler.addSlice(vanishing);
		scheduler.addSlice(next);
		scheduler.addSlice(after);

		scheduler.doWork();
		scheduler.doWork();
		scheduler.doWork();

		// The search that took the vanished one's place gets a turn of its own, rather than inheriting
		// what was already spent on the one that is no longer there
		assertEquals(1, vanishing.turns);
		assertEquals(2, next.turns);
		assertEquals(0, after.turns);
	}

	@Test
	void aTickIsGivenUpWhenEverySearchIsWaiting() {
		final Clock clock = new Clock();
		// A budget that a search taking no time at all could spin in forever
		final SearchScheduler scheduler = scheduler(clock, 10L);
		final FakeSlice waiting = new FakeSlice(clock, 0L, 100);
		final FakeSlice alsoWaiting = new FakeSlice(clock, 0L, 100);
		waiting.canCarryOn = false;
		alsoWaiting.canCarryOn = false;
		scheduler.addSlice(waiting);
		scheduler.addSlice(alsoWaiting);

		// One turn each, and then nothing left to spend the tick on
		assertTrue(scheduler.doWork());
		assertFalse(scheduler.doWork());
		assertEquals(1, waiting.turns);
		assertEquals(1, alsoWaiting.turns);
	}

}
