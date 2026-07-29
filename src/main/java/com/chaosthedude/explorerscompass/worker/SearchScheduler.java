package com.chaosthedude.explorerscompass.worker;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

import com.chaosthedude.explorerscompass.config.ConfigHandler;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.WorldWorkerManager;
import net.minecraftforge.server.ServerLifecycleHooks;

/**
 * Hands out the time the searches on this server are allowed to spend on the server thread.
 *
 * <p>Forge hands its workers whatever is left of the tick and only checks the clock between calls,
 * and it walks them from the front of its list on every tick. Registering every search there
 * directly therefore gives none of them a budget of its own: the first one takes as much of the
 * tick as it is allowed and the ones behind it can go a long time without being asked at all. This
 * registers as a single worker instead and shares one budget between the searches, carrying on from
 * the search that had the last turn rather than starting over from the first, so that a search that
 * has just been started is not held up by the ones that were already running.
 *
 * <p>Only ever touched on the server thread.
 */
public class SearchScheduler implements WorldWorkerManager.IWorker {

	/** One search, advanced a sample at a time. */
	public interface SearchSlice {

		/** Whether this search still has anything to do. One that has not is dropped. */
		boolean hasWork();

		/** Advances the search. Returns whether it could carry straight on. */
		boolean doWork();

	}

	// How long a search may hold the budget before the turn passes to the next one. Short enough
	// that several searches share a tick, long enough that a turn is worth more than the switch.
	private static final long QUANTUM_NANOS = TimeUnit.MILLISECONDS.toNanos(2L);

	private static SearchScheduler instance;

	// Everything here is measured in nanoseconds, and against a clock that only ever moves forwards.
	// Wall clock time is not that: it is stepped by the system whenever it is corrected, and a step
	// backwards would leave the tick's budget reading as unspent for as long as the step was, so a
	// search would hold the server thread for the whole tick. Its resolution is also coarse next to
	// a budget of a few milliseconds and a turn of two.
	private final LongSupplier clock;
	private final IntSupplier tickCount;
	private final LongSupplier budgetNanos;

	private final List<SearchSlice> slices = new ArrayList<SearchSlice>();

	// Whose turn it is. Kept across ticks, which is what keeps the searches at the back of the list
	// from being starved by the ones in front of them.
	private int cursor;

	private int lastTick = Integer.MIN_VALUE;
	private long budgetStart;

	// How long the search whose turn it is has had. Carried across ticks rather than reset with the
	// budget, so that a search only ever given a moment of each tick still hands the turn on.
	private long turnNanos;

	// How many turns in a row have been handed back without the search carrying on. A search waiting
	// for something of its own, such as the positions a placement is still computing, cannot use the
	// tick, and once every search has said so there is nothing to spend the rest of the budget on.
	private int idleTurns;

	// Whether Forge is currently calling this. It drops a worker with nothing left to do, so the
	// next search to be started has to register again.
	private boolean registered;

	SearchScheduler(LongSupplier clock, IntSupplier tickCount, LongSupplier budgetNanos) {
		this.clock = clock;
		this.tickCount = tickCount;
		this.budgetNanos = budgetNanos;
	}

	/** Puts a search to work, and registers with Forge if nothing else is running. */
	static void add(SearchSlice slice) {
		instance().addSlice(slice);
	}

	/**
	 * Drops everything still registered. Called when the server stops: Forge empties its own list
	 * there without asking, so a scheduler that went on believing it was registered would never
	 * register again and no search would ever run afterwards.
	 */
	public static void shutdown() {
		if (instance != null) {
			instance.clear();
		}
	}

	private static synchronized SearchScheduler instance() {
		if (instance == null) {
			instance = new SearchScheduler(System::nanoTime, SearchScheduler::currentServerTick, SearchScheduler::configuredBudgetNanos);
		}
		return instance;
	}

	private static int currentServerTick() {
		final MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
		return server != null ? server.getTickCount() : 0;
	}

	private static long configuredBudgetNanos() {
		return TimeUnit.MILLISECONDS.toNanos(ConfigHandler.GENERAL.maxSearchTimePerTick.get().longValue());
	}

	void addSlice(SearchSlice slice) {
		if (!slices.contains(slice)) {
			slices.add(slice);
		}
		if (!registered) {
			registered = true;
			WorldWorkerManager.addWorker(this);
		}
	}

	void clear() {
		slices.clear();
		cursor = 0;
		registered = false;
	}

	@Override
	public boolean hasWork() {
		dropFinished();
		if (slices.isEmpty()) {
			registered = false;
			return false;
		}
		return true;
	}

	@Override
	public boolean doWork() {
		final long now = clock.getAsLong();
		final int tick = tickCount.getAsInt();
		if (tick != lastTick) {
			lastTick = tick;
			budgetStart = now;
			idleTurns = 0;
		}

		if (now - budgetStart >= budgetNanos.getAsLong()) {
			// The searches have had their share of this tick; whatever else Forge has to run can have
			// the rest of it
			return false;
		}

		dropFinished();
		if (slices.isEmpty()) {
			return false;
		}

		if (cursor >= slices.size()) {
			cursor = 0;
		}
		final SearchSlice slice = slices.get(cursor);
		final boolean again = slice.doWork();
		turnNanos += clock.getAsLong() - now;
		if (!again || turnNanos >= QUANTUM_NANOS) {
			cursor++;
			turnNanos = 0L;
		}

		if (again) {
			idleTurns = 0;
			return true;
		}
		idleTurns++;
		return idleTurns < slices.size();
	}

	/** Drops the searches that are over, keeping the turn on whichever search it was already on. */
	private void dropFinished() {
		for (int i = slices.size() - 1; i >= 0; i--) {
			if (!slices.get(i).hasWork()) {
				slices.remove(i);
				if (i < cursor) {
					cursor--;
				} else if (i == cursor) {
					// The turn was on the search that just ended, so whatever took its place in the list
					// starts a turn of its own rather than inheriting what that one had already spent
					turnNanos = 0L;
				}
			}
		}
	}

	int getSliceCount() {
		return slices.size();
	}

}
