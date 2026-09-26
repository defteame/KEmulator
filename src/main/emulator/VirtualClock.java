package emulator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Virtual time for deterministic headless runs (-headless -vtime).
 * <p>
 * Every thread that can run MIDlet code (the event thread, timer threads,
 * startApp, threads the MIDlet starts) belongs to one thread group. Such a
 * thread only counts as idle while it waits in one of the places that go
 * through this class: Thread.sleep, Object.wait, Thread.yield in MIDlet code,
 * the event queue waiting for events and the timer threads waiting for their
 * next task. Anywhere else it is busy.
 * <p>
 * Time does not pass by itself. The headless driver moves it: it waits until
 * every managed thread is idle, then wakes the waiters one at a time, first
 * those that were notified (in the order of the notifications), then those
 * whose deadline has come (in deadline order), and only when nothing is left
 * to do at the current instant moves the clock to the next deadline. A
 * notification does not wake the waiting thread at once: it runs when the
 * notifying thread has become idle. So MIDlet code runs on one thread at a
 * time, in an order that depends only on the program and the script, and the
 * same run gives the same frames every time.
 * <p>
 * With virtual time off, every method here behaves like the plain Java
 * method it replaces.
 */
public final class VirtualClock {
	/** Virtual time is on. */
	public static volatile boolean enabled;

	/**
	 * A thread that reads the clock this many times without becoming idle is
	 * waiting for time to pass in a busy loop: the clock moves on by 1 ms.
	 */
	public static final int SPIN_READS = 100000;

	private static final Object lock = new Object();
	private static final ArrayList<Waiter> waiters = new ArrayList<Waiter>();
	private static final Set<Thread> assumedIdle = new HashSet<Thread>();
	private static final ThreadLocal<Boolean> started = new ThreadLocal<Boolean>();

	private static volatile long now;
	private static long sequence;
	private static long notifications;
	private static int changes;
	private static int spinReads;
	private static ThreadGroup group;

	private VirtualClock() {
	}

	private static final class Waiter {
		final Thread thread;
		final Object monitor;
		final long deadline;
		final long seq;
		/** Notified: to be woken when the notifying thread is idle (order: notified). */
		long notified = -1;
		boolean woken;

		Waiter(Thread thread, Object monitor, long deadline, long seq) {
			this.thread = thread;
			this.monitor = monitor;
			this.deadline = deadline;
			this.seq = seq;
		}
	}

	/** Turns virtual time on, starting at <code>start</code> (ms since 1970). */
	public static void enable(long start) {
		now = start;
		group = new ThreadGroup("KEmulator-MIDlet");
		enabled = true;
	}

	/**
	 * The thread group for threads that run MIDlet code; null without virtual
	 * time (a new thread then joins its creator's group, as usual).
	 */
	public static ThreadGroup threadGroup() {
		return enabled ? group : null;
	}

	/** The time as the emulator sees it. */
	public static long currentTimeMillis() {
		return enabled ? now : System.currentTimeMillis();
	}

	/** The time as the MIDlet sees it (System.currentTimeMillis in MIDlet code). */
	public static long programTime() {
		if (!managed(Thread.currentThread())) {
			return now;
		}
		synchronized (lock) {
			if (++spinReads >= SPIN_READS) {
				spinReads = 0;
				now++;
			}
			return now;
		}
	}

	public static boolean managed(Thread t) {
		if (!enabled) {
			return false;
		}
		ThreadGroup g = t.getThreadGroup();
		return g != null && (g == group || group.parentOf(g));
	}

	/**
	 * Object.wait(timeout) for a place where a managed thread waits for
	 * something to happen. The caller holds the monitor. A timeout of 0 waits
	 * until notified.
	 */
	public static void await(Object monitor, long timeout) throws InterruptedException {
		if (!managed(Thread.currentThread())) {
			if (timeout > 0) {
				monitor.wait(timeout);
			} else {
				monitor.wait();
			}
			return;
		}
		awaitUntil(monitor, timeout > 0 ? now + timeout : Long.MAX_VALUE);
	}

	private static void awaitUntil(Object monitor, long deadline) throws InterruptedException {
		Waiter w;
		synchronized (lock) {
			w = new Waiter(Thread.currentThread(), monitor, deadline, sequence++);
			waiters.add(w);
			changes++;
			lock.notifyAll();
		}
		try {
			for (;;) {
				synchronized (lock) {
					if (w.woken) {
						break;
					}
				}
				monitor.wait();
			}
		} finally {
			synchronized (lock) {
				waiters.remove(w);
				changes++;
				lock.notifyAll();
			}
		}
	}

	/** Thread.sleep. */
	public static void sleep(long ms) throws InterruptedException {
		if (!managed(Thread.currentThread())) {
			Thread.sleep(ms);
			return;
		}
		if (ms < 0) {
			throw new IllegalArgumentException("timeout value is negative");
		}
		Object m = new Object();
		synchronized (m) {
			// a sleep takes at least a millisecond, so that a loop of sleep(0)
			// lets time pass
			awaitUntil(m, now + Math.max(1, ms));
		}
	}

	/** Thread.yield: the other threads run, then this one, at the same time. */
	public static void yield() throws InterruptedException {
		if (!managed(Thread.currentThread())) {
			Thread.yield();
			return;
		}
		Object m = new Object();
		synchronized (m) {
			awaitUntil(m, now);
		}
	}

	/**
	 * Called first thing by a new thread that runs MIDlet code: it waits until
	 * the thread that started it is idle, as on a phone with one processor.
	 */
	public static void startGate() {
		Thread t = Thread.currentThread();
		if (!managed(t) || started.get() != null) {
			return;
		}
		started.set(Boolean.TRUE);
		try {
			yield();
		} catch (InterruptedException e) {
			t.interrupt();
		}
	}

	/** Thread.join. */
	public static void join(Thread t, long ms) throws InterruptedException {
		if (!managed(Thread.currentThread())) {
			if (ms > 0) {
				t.join(ms);
			} else {
				t.join();
			}
			return;
		}
		long end = ms > 0 ? now + ms : Long.MAX_VALUE;
		while (t.isAlive() && now < end) {
			sleep(1);
		}
	}

	/** Object.notify (all = false) or notifyAll. The caller holds the monitor. */
	public static void notify(Object monitor, boolean all) {
		if (!enabled) {
			if (all) {
				monitor.notifyAll();
			} else {
				monitor.notify();
			}
			return;
		}
		boolean any = false;
		synchronized (lock) {
			Waiter first = null;
			for (int i = 0; i < waiters.size(); i++) {
				Waiter w = waiters.get(i);
				if (w.monitor != monitor || w.woken || w.notified >= 0) {
					continue;
				}
				if (all) {
					w.notified = notifications++;
					any = true;
				} else if (first == null || w.seq < first.seq) {
					first = w;
				}
			}
			if (first != null) {
				first.notified = notifications++;
				any = true;
			}
			if (any) {
				changes++;
				lock.notifyAll();
			}
		}
		if (!any) {
			// no managed waiter: wake whoever else waits, as Java would
			if (all) {
				monitor.notifyAll();
			} else {
				monitor.notify();
			}
		}
	}

	// ---- the driver's side ----

	/** The current virtual time. */
	public static long now() {
		return now;
	}

	/** Moves the clock forward to t (never back). */
	public static void setNow(long t) {
		synchronized (lock) {
			if (t > now) {
				now = t;
			}
			spinReads = 0;
		}
	}

	/** The earliest deadline of a waiter not yet woken or notified, or Long.MAX_VALUE. */
	public static long nextDeadline() {
		synchronized (lock) {
			long min = Long.MAX_VALUE;
			for (Waiter w : waiters) {
				if (!w.woken && w.notified < 0 && w.deadline < min) {
					min = w.deadline;
				}
			}
			return min;
		}
	}

	/**
	 * Wakes one waiter: the first one notified, or else the one whose deadline
	 * came first. Returns false if there is none.
	 */
	public static boolean wakeNext() {
		for (;;) {
			Waiter next = null;
			synchronized (lock) {
				for (Waiter w : waiters) {
					if (w.woken) {
						continue;
					}
					if (w.notified >= 0) {
						if (next == null || next.notified < 0 || w.notified < next.notified) {
							next = w;
						}
					} else if (w.deadline <= now && (next == null || (next.notified < 0
							&& (w.deadline < next.deadline || (w.deadline == next.deadline && w.seq < next.seq))))) {
						next = w;
					}
				}
			}
			if (next == null) {
				return false;
			}
			synchronized (next.monitor) {
				synchronized (lock) {
					if (next.woken || !waiters.contains(next)) {
						continue;
					}
					next.woken = true;
					changes++;
					lock.notifyAll();
				}
				next.monitor.notifyAll();
			}
			return true;
		}
	}

	/**
	 * Waits (up to timeoutMs of real time) until every managed thread is idle.
	 * Returns false on timeout.
	 */
	public static boolean awaitIdle(long timeoutMs) throws InterruptedException {
		long end = System.currentTimeMillis() + timeoutMs;
		for (;;) {
			int before;
			synchronized (lock) {
				before = changes;
			}
			Thread[] threads = threads();
			synchronized (lock) {
				if (before == changes && busy(threads).isEmpty()) {
					spinReads = 0;
					return true;
				}
				long left = end - System.currentTimeMillis();
				if (left <= 0) {
					return false;
				}
				lock.wait(Math.min(left, 2));
			}
		}
	}

	/** The managed threads that are busy now. */
	public static java.util.List<Thread> busyThreads() {
		Thread[] threads = threads();
		synchronized (lock) {
			return busy(threads);
		}
	}

	/**
	 * Treats a thread that waits somewhere this class does not know about
	 * (a media player, say) as idle from now on.
	 */
	public static void assumeIdle(Thread t) {
		synchronized (lock) {
			assumedIdle.add(t);
			changes++;
			lock.notifyAll();
		}
	}

	/** The managed threads, alive. */
	public static Thread[] threads() {
		ThreadGroup g = group;
		if (g == null) {
			return new Thread[0];
		}
		Thread[] a = new Thread[g.activeCount() + 16];
		int n;
		while ((n = g.enumerate(a, true)) >= a.length) {
			a = new Thread[a.length * 2];
		}
		return Arrays.copyOf(a, n);
	}

	private static java.util.List<Thread> busy(Thread[] threads) {
		java.util.List<Thread> busy = new ArrayList<Thread>();
		outer:
		for (Thread t : threads) {
			if (!t.isAlive() || assumedIdle.contains(t)) {
				continue;
			}
			for (Waiter w : waiters) {
				if (w.thread == t && !w.woken) {
					continue outer;
				}
			}
			busy.add(t);
		}
		return busy;
	}

	/** Describes what each managed thread is doing, for diagnostics. */
	public static String describeThreads() {
		StringBuilder sb = new StringBuilder();
		Thread[] threads = threads();
		synchronized (lock) {
			java.util.List<Thread> busy = busy(threads);
			for (Thread t : threads) {
				sb.append(busy.contains(t) ? "BUSY " : "idle ").append(t.getName())
						.append(" (").append(t.getState()).append(")\n");
				if (busy.contains(t)) {
					for (StackTraceElement e : t.getStackTrace()) {
						sb.append("\tat ").append(e).append('\n');
					}
				}
			}
			for (Waiter w : waiters) {
				sb.append("waiter ").append(w.thread.getName())
						.append(w.deadline == Long.MAX_VALUE ? " untimed" : " deadline " + (w.deadline - now) + " ms")
						.append(w.notified >= 0 ? " notified" : "").append(w.woken ? " woken" : "").append('\n');
			}
		}
		return sb.toString();
	}
}
