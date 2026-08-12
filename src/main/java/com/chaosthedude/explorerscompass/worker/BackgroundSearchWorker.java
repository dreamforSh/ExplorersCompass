package com.chaosthedude.explorerscompass.worker;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A search that can run off the server thread, and what the server thread does with it once it is
 * done.
 *
 * <p>What such a search is allowed to touch is decided by the searching itself and lives in the
 * workers below this one; what is the same for all of them is when it starts, how the server thread
 * is told it has stopped, and how a worker that is not doing anything on the server thread behaves
 * towards the search it belongs to. A worker searching on threads of its own turns its turn down for
 * as long as it runs, which passes the turn round the rest of the search rather than holding it up,
 * and it holds on to its place in the search until the server thread has acted on what it turned up
 * — however far it has actually got, since what is left to search is those threads' business and
 * reading it from the server thread would be a race.
 *
 * <p>A search may be split into several pieces run side by side. Each of them counts itself out as
 * it finishes, and the last one to do so publishes that the search is over. Because counting out
 * goes through the same counter every piece reads, everything each of them wrote is visible to the
 * server thread that sees the search published as done.
 */
public abstract class BackgroundSearchWorker extends SearchWorker {

	/** Whether the search is running on threads of its own. Set before either side starts. */
	private volatile boolean background;

	/** How many of the pieces it was split into are still running. */
	private AtomicInteger stillRunning;

	// That they are all done. Publishing this last is what makes everything they wrote before it
	// visible to the server thread that sees it set.
	private volatile boolean backgroundDone;
	private volatile Throwable backgroundError;

	/** Whether the server thread has finished acting on that. Only ever touched on the server thread. */
	private boolean applied;

	public BackgroundSearchWorker(SearchContext context, int maxSamples) {
		super(context, maxSamples);
	}

	/**
	 * Whether this search may run off the server thread at all, which the settings decide. A search
	 * that never does simply leaves this, and everything below is then never reached.
	 */
	protected boolean isBackgroundAllowed() {
		return false;
	}

	/** The pieces to run side by side. Only asked for when the search is allowed its own threads. */
	protected List<Runnable> createBackgroundTasks() {
		return List.of();
	}

	@Override
	protected void onBegin() {
		if (!isBackgroundAllowed()) {
			return;
		}

		final List<Runnable> tasks = createBackgroundTasks();
		if (tasks.isEmpty()) {
			return;
		}

		background = true;
		// Set before anything can finish, so that the piece that finishes first cannot count out of a
		// total that has not been decided yet
		stillRunning = new AtomicInteger(tasks.size());
		for (Runnable task : tasks) {
			try {
				SearchExecutor.execute(() -> runBackgroundTask(task));
			} catch (Throwable t) {
				// Nothing is going to run it, so count it out as though it had run and failed rather than
				// leaving the server thread watching for a result that will never arrive
				backgroundError = t;
				finishBackgroundTask();
			}
		}
	}

	private void runBackgroundTask(Runnable task) {
		try {
			task.run();
		} catch (Throwable t) {
			backgroundError = t;
		} finally {
			finishBackgroundTask();
		}
	}

	private void finishBackgroundTask() {
		if (stillRunning.decrementAndGet() == 0) {
			backgroundDone = true;
		}
	}

	/** Whether this search is running on threads of its own rather than in slices of the tick. */
	protected boolean isBackground() {
		return background;
	}

	protected boolean isBackgroundDone() {
		return backgroundDone;
	}

	/** Whatever went wrong on those threads, or null when nothing did. */
	protected Throwable getBackgroundError() {
		return backgroundError;
	}

	/** Whether the server thread has finished acting on what the search turned up. */
	protected boolean isApplied() {
		return applied;
	}

	protected void markApplied() {
		applied = true;
	}

	@Override
	boolean isReady() {
		// While the search runs on threads of its own there is nothing for the server thread to do
		// until they have something for it
		return !background || backgroundDone;
	}

	@Override
	boolean hasWork() {
		return background ? !applied : super.hasWork();
	}

	@Override
	protected boolean isExhausted() {
		return background ? applied : super.isExhausted();
	}

}
