package emulator;

public final class InvokeDestroyAppRunnable implements Runnable {
	InvokeDestroyAppRunnable(final EventQueue j) {
		super();
	}

	public final void run() {
		VirtualClock.startGate();
		Emulator.getMIDlet().invokeDestroyApp(true);
	}
}
