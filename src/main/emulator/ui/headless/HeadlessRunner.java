package emulator.ui.headless;

import emulator.AppSettings;
import emulator.Emulator;
import emulator.EventQueue;
import emulator.Settings;
import emulator.VirtualClock;
import emulator.custom.CustomMethod;
import emulator.graphics2D.IImage;
import emulator.ui.TargetedCommand;

import javax.microedition.lcdui.Alert;
import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.List;
import javax.microedition.lcdui.Screen;
import javax.microedition.lcdui.TextBox;
import javax.microedition.rms.InvalidRecordIDException;
import javax.microedition.rms.RecordStore;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Writer;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TimeZone;
import java.util.Vector;

/**
 * Plays the script of a headless run on the main thread and writes the
 * results (see Recorder and HeadlessMode.md).
 * <p>
 * With virtual time (-vtime) the MIDlet only runs while the driver lets time
 * pass or delivers an event: after each step the driver waits until every
 * thread of the MIDlet is idle (see VirtualClock), so the script's keys
 * arrive at exact times and the run can be repeated frame for frame. In real
 * time the script's waits are real waits and the MIDlet runs freely, as in
 * the window.
 */
public final class HeadlessRunner {
	public static final int EXIT_OK = 0;
	/** The MIDlet threw an exception, or the script is wrong. */
	public static final int EXIT_ERROR = 1;
	/** An expect or until of the script failed. */
	public static final int EXIT_FAILED = 2;
	/** The MIDlet hung (its threads never became idle) or -timeout passed. */
	public static final int EXIT_HANG = 3;
	/** Bad command line, or the MIDlet could not be started. */
	public static final int EXIT_USAGE = 4;
	/** The MIDlet exited before the script ended (without "exit" or "expect destroyed"). */
	public static final int EXIT_EXITED = 5;

	private static HeadlessFrontend frontend;
	private static Recorder recorder;
	private static volatile boolean destroyed;
	private static final java.util.List<String> errors = Collections.synchronizedList(new ArrayList<String>());
	private static final java.util.List<String> failures = Collections.synchronizedList(new ArrayList<String>());
	private static final java.util.List<String> warnings = Collections.synchronizedList(new ArrayList<String>());
	private static final Set<Integer> held = new LinkedHashSet<Integer>();
	private static final Object sleeper = new Object();
	private static final Object finishLock = new Object();
	private static boolean finished;
	private static boolean expectExit;
	private static long busyNanos;
	private static int commandsRun;
	private static int commandsTotal;
	private static long runStart;

	private HeadlessRunner() {
	}

	static final class Hang extends RuntimeException {
		Hang(String message) {
			super(message);
		}
	}

	static void init(HeadlessFrontend f) throws IOException {
		frontend = f;
		recorder = new Recorder(HeadlessOptions.out);
		runStart = System.nanoTime();
		VirtualClock.slowListener = new VirtualClock.SlowListener() {
			public void slow(String threads) {
				slowSteps++;
				System.err.println("slow step (" + VirtualClock.SLOW_MS + " ms and more) at " + recorder.time() + " ms:\n" + threads);
			}
		};
	}

	/** Steps where the MIDlet's threads took SLOW_MS or more of real time to become idle. */
	private static int slowSteps;

	// ---- callbacks from the emulator ----

	public static void event(String text) {
		if (recorder != null) {
			recorder.event(text);
		}
	}

	static void warning(String text) {
		warnings.add(text);
		System.err.println("KEmulator headless warning: " + text);
	}

	static void frame(IImage screen, Displayable d, long paintNanos) {
		recorder.frame(screen, describe(d), d instanceof Canvas, paintNanos);
	}

	public static void exception(String where, Throwable e) {
		errors.add(where + ": " + e);
		event("exception in " + where + ": " + e);
		System.err.println("MIDlet exception in " + where + ":");
		e.printStackTrace();
	}

	public static void midletDestroyed() {
		destroyed = true;
		event("destroyed");
		synchronized (sleeper) {
			sleeper.notifyAll();
		}
	}

	static void noMidlet() {
		errors.add("no MIDlet: give the JAR with -jar");
		finish(EXIT_USAGE);
	}

	/** Called by Emulator.main before the MIDlet is created. */
	public static void beforeMidlet() {
		Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
			public void uncaughtException(Thread t, Throwable e) {
				exception("thread " + t.getName(), e);
			}
		});
		Script script = HeadlessOptions.script;
		if (script != null) {
			for (Script.Command c : script.preamble) {
				if (c.word(0).equals("rms")) {
					preloadRecordStore(c);
				}
			}
		}
		if (HeadlessOptions.timeout > 0) {
			Thread watchdog = new Thread(new Runnable() {
				public void run() {
					try {
						Thread.sleep(HeadlessOptions.timeout);
					} catch (InterruptedException e) {
						return;
					}
					errors.add("timeout: the run took longer than " + HeadlessOptions.timeout + " ms");
					System.err.println(threadDump());
					finish(EXIT_HANG);
				}
			}, "KEmulator-Watchdog");
			watchdog.setDaemon(true);
			watchdog.start();
		}
	}

	/** rms NAME HEX...: a record store as the MIDlet will find it ("-" for a deleted record). */
	private static void preloadRecordStore(Script.Command c) {
		String name = c.word(1);
		try {
			RecordStore rs = RecordStore.openRecordStore(name, true);
			for (int i = 2; i < c.count(); i++) {
				String hex = c.word(i);
				if (hex.equals("-")) {
					rs.deleteRecord(rs.addRecord(new byte[0], 0, 0));
				} else {
					byte[] b = unhex(c, hex);
					rs.addRecord(b, 0, b.length);
				}
			}
			rs.closeRecordStore();
			event("rms " + name + " " + (c.count() - 2) + " records");
		} catch (Exception e) {
			throw new Script.ScriptException(c, "cannot create the record store: " + e);
		}
	}

	// ---- the script ----

	static void run() {
		int code;
		try {
			code = play();
		} catch (Hang e) {
			errors.add("hang: " + e.getMessage());
			code = EXIT_HANG;
		} catch (Script.ScriptException e) {
			errors.add("script " + e.getMessage());
			code = EXIT_ERROR;
		} catch (Throwable e) {
			errors.add("driver: " + e);
			e.printStackTrace();
			code = EXIT_ERROR;
		}
		finish(code);
	}

	private static int play() throws Exception {
		if (VirtualClock.enabled) {
			settle();
		} else {
			long end = System.currentTimeMillis() + 30000;
			while (!frontend.screen().started && !destroyed && System.currentTimeMillis() < end) {
				sleepReal(10);
			}
		}
		Script script = HeadlessOptions.script;
		if (script == null) {
			advance(HeadlessOptions.duration >= 0 ? HeadlessOptions.duration : 5000);
			return EXIT_OK;
		}
		commandsTotal = script.body.size();
		for (Script.Command c : script.body) {
			if (destroyed) {
				break;
			}
			event("> " + c.text);
			exec(c);
			commandsRun++;
			if (!failures.isEmpty() && !HeadlessOptions.keepGoing) {
				break;
			}
		}
		return EXIT_OK;
	}

	private static void exec(Script.Command c) throws Exception {
		String w = c.word(0);
		if (w.equals("wait")) {
			advance(c.number(1));
		} else if (w.equals("press") || w.equals("release") || w.equals("repeat")) {
			key(w, keyCode(c, 1));
		} else if (w.equals("tap") || w.equals("hold")) {
			int k = keyCode(c, 1);
			key("press", k);
			advance(c.count() > 2 ? c.number(2) : 100);
			key("release", k);
		} else if (w.equals("select")) {
			select(c, (int) c.number(1));
		} else if (w.equals("command")) {
			command(c.rest(1));
		} else if (w.equals("menu")) {
			menu(c, (int) c.number(1));
		} else if (w.equals("text")) {
			text(c, c.count() > 1 ? c.rest(1) : "");
		} else if (w.equals("hide")) {
			Displayable d = current();
			if (d != null) {
				Emulator.getEventQueue().notifyHidden(d);
			}
			settle();
		} else if (w.equals("show")) {
			Emulator.getEventQueue().queue(EventQueue.EVENT_SHOW);
			settle();
		} else if (w.equals("pause")) {
			Emulator.getEventQueue().queue(EventQueue.EVENT_PAUSE);
			settle();
		} else if (w.equals("resume")) {
			Emulator.getEventQueue().queue(EventQueue.EVENT_RESUME);
			settle();
		} else if (w.equals("snap") || w.equals("shot")) {
			String name = c.word(1);
			event("snap " + name + " " + recorder.snap(frontend.screen().getScreenImg(), name));
		} else if (w.equals("until")) {
			until(c, c.number(1), c.rest(2));
		} else if (w.equals("expect")) {
			String cond = c.rest(1);
			if (cond.trim().equals("destroyed")) {
				expectExit = true;
			}
			if (test(c, cond)) {
				event("ok " + cond);
			} else {
				fail(c, "expected " + cond + ", but the display is " + describe(current()));
			}
		} else if (w.equals("fuzz")) {
			fuzz(c);
		} else if (w.equals("exit")) {
			expectExit = true;
			Emulator.getEventQueue().queue(EventQueue.EVENT_EXIT);
			if (VirtualClock.enabled) {
				settle();
			} else {
				long end = System.currentTimeMillis() + 5000;
				while (!destroyed && System.currentTimeMillis() < end) {
					sleepReal(10);
				}
			}
		} else if (w.equals("log")) {
			event("log " + (c.count() > 1 ? c.rest(1) : ""));
		} else if (w.equals("start")) {
			// the MIDlet has started already
		} else if (Script.PREAMBLE.contains(w)) {
			throw new Script.ScriptException(c, "a setting: it belongs at the top of the script");
		} else {
			throw new Script.ScriptException(c, "unknown command");
		}
	}

	private static void fail(Script.Command c, String message) {
		failures.add("line " + c.line + ": " + message);
		event("FAILED " + message);
	}

	static Displayable current() {
		return Emulator.getCurrentDisplay().getCurrent();
	}

	static String describe(Displayable d) {
		if (d == null) {
			return "none";
		}
		String kind = d instanceof Canvas ? "Canvas" : d instanceof List ? "List" : d instanceof Form ? "Form"
				: d instanceof Alert ? "Alert" : d instanceof TextBox ? "TextBox" : d.getClass().getName();
		String title = d.getTitle();
		return title == null || title.isEmpty() ? kind : kind + " \"" + title + "\"";
	}

	/** Key names: 0-9 * # up down left right select (fire) soft1 soft2, or a key code. */
	static int keyCode(Script.Command c, int i) {
		String s = c.word(i);
		String l = s.toLowerCase();
		if (l.equals("soft1") || l.equals("leftsoft")) {
			return AppSettings.leftSoftKey;
		}
		if (l.equals("soft2") || l.equals("rightsoft")) {
			return AppSettings.rightSoftKey;
		}
		if (l.equals("up")) {
			return AppSettings.upKey;
		}
		if (l.equals("down")) {
			return AppSettings.downKey;
		}
		if (l.equals("left")) {
			return AppSettings.leftKey;
		}
		if (l.equals("right")) {
			return AppSettings.rightKey;
		}
		if (l.equals("select") || l.equals("fire")) {
			return AppSettings.fireKey;
		}
		if (l.equals("*") || l.equals("star")) {
			return '*';
		}
		if (l.equals("#") || l.equals("pound")) {
			return '#';
		}
		if (s.length() == 1 && Character.isDigit(s.charAt(0))) {
			return s.charAt(0);
		}
		try {
			return Integer.parseInt(s);
		} catch (NumberFormatException e) {
			throw new Script.ScriptException(c, "unknown key " + s);
		}
	}

	private static void key(String kind, int code) throws InterruptedException {
		EventQueue q = Emulator.getEventQueue();
		event(kind + " " + code);
		if (kind.equals("press")) {
			held.add(code);
			q.keyPress(code);
		} else if (kind.equals("release")) {
			held.remove(code);
			q.keyRelease(code);
		} else {
			q.keyRepeat(code);
		}
		settle();
	}

	private static void select(Script.Command c, int n) throws InterruptedException {
		Displayable d = current();
		if (!(d instanceof List)) {
			event("select ignored: the display is " + describe(d));
			return;
		}
		List list = (List) d;
		if (n < 0 || n >= list.size()) {
			throw new Script.ScriptException(c, "the list has " + list.size() + " items");
		}
		list.setSelectedIndex(n, true);
		event("select " + n + " \"" + list.getString(n) + "\"");
		Command cmd = list._getSelectCommand();
		if (cmd != null) {
			Emulator.getEventQueue().commandAction(cmd, list);
		}
		settle();
	}

	private static int commandType(String s) {
		String u = s.toUpperCase();
		if (u.equals("SCREEN")) return Command.SCREEN;
		if (u.equals("BACK")) return Command.BACK;
		if (u.equals("CANCEL")) return Command.CANCEL;
		if (u.equals("OK")) return Command.OK;
		if (u.equals("HELP")) return Command.HELP;
		if (u.equals("STOP")) return Command.STOP;
		if (u.equals("EXIT")) return Command.EXIT;
		if (u.equals("ITEM")) return Command.ITEM;
		return -1;
	}

	/** command TYPE (BACK, EXIT, OK, ...) or command LABEL: the first such command of the current displayable. */
	private static void command(String what) throws InterruptedException {
		String label = what.trim();
		if (label.length() > 1 && label.startsWith("\"") && label.endsWith("\"")) {
			label = label.substring(1, label.length() - 1);
		}
		Displayable d = current();
		int type = commandType(label);
		Command found = null;
		if (d != null) {
			for (Command cmd : d._getCommands()) {
				if ((type != -1 && cmd.getCommandType() == type) || label.equals(cmd.getLabel())) {
					found = cmd;
					break;
				}
			}
		}
		if (found == null) {
			event("command ignored: no " + label + " command on " + describe(d));
			return;
		}
		event("command \"" + found.getLabel() + "\"");
		Emulator.getEventQueue().commandAction(found, d);
		settle();
	}

	/** menu N: item N of the commands menu the soft key opened. */
	private static void menu(Script.Command c, int n) throws InterruptedException {
		Vector<TargetedCommand> cmds = frontend.screen().commandsMenu();
		if (cmds == null || n < 0 || n >= cmds.size()) {
			throw new Script.ScriptException(c, cmds == null ? "no commands menu is open" : "the menu has " + cmds.size() + " items");
		}
		TargetedCommand tc = cmds.get(n);
		frontend.screen().forceCloseCommandsList();
		event("menu " + n + " \"" + tc.text + "\"");
		if (tc.item != null) {
			Emulator.getEventQueue().commandAction(tc.command, tc.item);
		} else {
			Emulator.getEventQueue().commandAction(tc.command, tc.screen);
		}
		settle();
	}

	private static void text(Script.Command c, String s) throws InterruptedException {
		Displayable d = current();
		if (!(d instanceof TextBox)) {
			throw new Script.ScriptException(c, "the display is " + describe(d) + ", not a TextBox");
		}
		((TextBox) d).setString(s);
		event("text \"" + s + "\"");
		settle();
	}

	private static void until(Script.Command c, long ms, String cond) throws InterruptedException {
		long end = recorder.time() + ms;
		while (!test(c, cond)) {
			if (destroyed || recorder.time() >= end) {
				fail(c, "not reached within " + ms + " ms: " + cond + " (the display is " + describe(current()) + ")");
				return;
			}
			step(end);
		}
		event("reached " + cond);
	}

	/** Conditions of until and expect. */
	private static boolean test(Script.Command c, String cond) {
		String[] w = cond.trim().split("\\s+", 2);
		String k = w[0].toLowerCase();
		String arg = w.length > 1 ? w[1] : "";
		Displayable d = current();
		if (k.equals("not")) {
			return !test(c, arg);
		}
		if (k.equals("canvas")) return d instanceof Canvas;
		if (k.equals("list")) return d instanceof List;
		if (k.equals("form")) return d instanceof Form;
		if (k.equals("alert")) return d instanceof Alert;
		if (k.equals("textbox")) return d instanceof TextBox;
		if (k.equals("screen")) return d instanceof Screen;
		if (k.equals("title")) return d != null && arg.equals(d.getTitle());
		if (k.equals("destroyed")) return destroyed;
		if (k.equals("alive")) return !destroyed;
		if (k.equals("frame")) return recorder.frames > 0 && Recorder.hex(recorder.lastHash).equalsIgnoreCase(arg);
		if (k.equals("frames")) return recorder.frames >= Long.parseLong(arg);
		if (k.equals("soft1")) return frontend.screen().leftLabel().equals(unquote(arg));
		if (k.equals("soft2")) return frontend.screen().rightLabel().equals(unquote(arg));
		if (k.equals("selected")) return d instanceof List && ((List) d).getSelectedIndex() == Integer.parseInt(arg);
		if (k.equals("item")) return d instanceof List && ((List) d).getSelectedIndex() >= 0
				&& ((List) d).getString(((List) d).getSelectedIndex()).equals(unquote(arg));
		throw new Script.ScriptException(c, "unknown condition " + k);
	}

	private static String unquote(String s) {
		s = s.trim();
		if (s.length() > 1 && s.startsWith("\"") && s.endsWith("\"")) {
			return s.substring(1, s.length() - 1);
		}
		return s;
	}

	/**
	 * fuzz SEED MS KEY...: random presses and releases of the keys for MS
	 * milliseconds. Back in a list it selects the first item, on another
	 * screen it takes the BACK command.
	 */
	private static void fuzz(Script.Command c) throws InterruptedException {
		Random r = new Random(c.number(1));
		long end = recorder.time() + c.number(2);
		int[] codes = new int[c.count() - 3];
		for (int i = 0; i < codes.length; i++) {
			codes[i] = keyCode(c, i + 3);
		}
		if (codes.length == 0) {
			throw new Script.ScriptException(c, "no keys");
		}
		while (recorder.time() < end && !destroyed) {
			advance(Math.min(end - recorder.time(), 40 + r.nextInt(400)));
			Displayable d = current();
			if (d instanceof List) {
				select(c, 0);
				continue;
			}
			if (!(d instanceof Canvas)) {
				command("BACK");
				continue;
			}
			int k = codes[r.nextInt(codes.length)];
			if (held.contains(k)) {
				key("release", k);
			} else if (held.size() < 2) {
				key("press", k);
			}
		}
		for (Integer k : new ArrayList<Integer>(held)) {
			key("release", k);
		}
	}

	// ---- time ----

	/** Lets ms of time pass. */
	static void advance(long ms) throws InterruptedException {
		if (!VirtualClock.enabled) {
			sleepReal(ms);
			return;
		}
		long target = VirtualClock.now() + ms;
		for (;;) {
			settle();
			if (destroyed) {
				return;
			}
			long next = VirtualClock.nextDeadline();
			if (next > target) {
				break;
			}
			VirtualClock.setNow(next);
		}
		VirtualClock.setNow(target);
		settle();
	}

	/** Lets time pass to the next thing that happens, but not beyond end (ms since the start). */
	private static void step(long end) throws InterruptedException {
		if (VirtualClock.enabled) {
			settle();
			long absoluteEnd = HeadlessOptions.epoch + end;
			VirtualClock.setNow(Math.min(VirtualClock.nextDeadline(), absoluteEnd));
			settle();
		} else {
			sleepReal(Math.max(1, Math.min(10, end - recorder.time())));
		}
	}

	/** Runs everything that is due now: wakes the waiters one at a time, each after all threads are idle. */
	static void settle() throws InterruptedException {
		if (!VirtualClock.enabled) {
			return;
		}
		long start = System.nanoTime();
		try {
			for (;;) {
				idle();
				if (!VirtualClock.wakeNext()) {
					return;
				}
			}
		} finally {
			busyNanos += System.nanoTime() - start;
		}
	}

	private static void idle() throws InterruptedException {
		for (;;) {
			if (VirtualClock.awaitIdle(HeadlessOptions.idleTimeout)) {
				return;
			}
			String threads = VirtualClock.describeThreads();
			System.err.println("MIDlet threads not idle after " + HeadlessOptions.idleTimeout + " ms:\n" + threads);
			boolean progress = false;
			for (Thread t : VirtualClock.busyThreads()) {
				Thread.State s = t.getState();
				if (s == Thread.State.WAITING || s == Thread.State.TIMED_WAITING) {
					VirtualClock.assumeIdle(t);
					warning("thread " + t.getName() + " waits where the virtual clock cannot see it; "
							+ "it counts as idle from now on and the run may not repeat exactly");
					event("warning: thread " + t.getName() + " treated as idle");
					progress = true;
				}
			}
			if (!progress) {
				throw new Hang("MIDlet threads still busy after " + HeadlessOptions.idleTimeout + " ms:\n" + threads);
			}
		}
	}

	private static void sleepReal(long ms) throws InterruptedException {
		long end = System.currentTimeMillis() + ms;
		synchronized (sleeper) {
			long left;
			while (!destroyed && (left = end - System.currentTimeMillis()) > 0) {
				sleeper.wait(left);
			}
		}
	}

	// ---- the end ----

	static void finish(int code) {
		synchronized (finishLock) {
			if (finished) {
				return;
			}
			finished = true;
		}
		if (code == EXIT_OK) {
			if (!errors.isEmpty()) {
				code = EXIT_ERROR;
			} else if (!failures.isEmpty()) {
				code = EXIT_FAILED;
			} else if (destroyed && !expectExit && commandsRun < commandsTotal) {
				code = EXIT_EXITED;
			}
		}
		Map<String, Object> rms = Json.object();
		try {
			rms = dumpRecordStores(new File(HeadlessOptions.out, "rms.txt"));
		} catch (Throwable e) {
			warning("cannot read the record stores: " + e);
		}
		try {
			Writer w = new OutputStreamWriter(new FileOutputStream(new File(HeadlessOptions.out, "report.json")), StandardCharsets.UTF_8);
			w.write(Json.write(report(code, rms)));
			w.close();
		} catch (Throwable e) {
			System.err.println("cannot write report.json: " + e);
		}
		recorder.close();
		frontend.dispose();
		long runMs = recorder.time();
		HeadlessLog.console.println("KEmulator headless: " + status(code) + ", " + recorder.frames + " frames ("
				+ recorder.distinctFrames() + " distinct) in " + runMs + " ms" + (VirtualClock.enabled ? " of virtual time" : "")
				+ ", sequence " + recorder.sequenceHash() + ", " + errors.size() + " errors, " + failures.size() + " failures");
		for (String e : errors) {
			HeadlessLog.console.println("  error: " + e);
		}
		for (String f : failures) {
			HeadlessLog.console.println("  failed: " + f);
		}
		HeadlessLog.console.println("  output: " + HeadlessOptions.out);
		HeadlessLog.console.flush();
		System.exit(code);
	}

	private static String status(int code) {
		switch (code) {
			case EXIT_OK:
				return "ok";
			case EXIT_ERROR:
				return "error";
			case EXIT_FAILED:
				return "failed";
			case EXIT_HANG:
				return "hang";
			case EXIT_USAGE:
				return "usage";
			case EXIT_EXITED:
				return "exited";
			default:
				return "exit " + code;
		}
	}

	private static Map<String, Object> report(int code, Map<String, Object> rms) {
		Map<String, Object> r = Json.object();

		Map<String, Object> kem = Json.object();
		kem.put("version", Emulator.version);
		kem.put("revision", Emulator.revision);
		r.put("kemulator", kem);

		Map<String, Object> java = Json.object();
		java.put("version", System.getProperty("java.version"));
		java.put("vendor", System.getProperty("java.vendor"));
		java.put("vm", System.getProperty("java.vm.name"));
		java.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"));
		r.put("java", java);
		r.put("commandLine", HeadlessOptions.commandLine);

		Map<String, Object> midlet = Json.object();
		midlet.put("jar", Emulator.midletJarPath);
		midlet.put("sha256", AppSettings.jarHash);
		midlet.put("class", Emulator.midletClassName);
		if (frontend.getAppProperties() != null) {
			midlet.put("name", frontend.getAppProperty("MIDlet-Name"));
			midlet.put("vendor", frontend.getAppProperty("MIDlet-Vendor"));
			midlet.put("version", frontend.getAppProperty("MIDlet-Version"));
		}
		r.put("midlet", midlet);

		Map<String, Object> settings = Json.object();
		settings.put("vtime", VirtualClock.enabled);
		settings.put("epoch", VirtualClock.enabled ? HeadlessOptions.epoch : null);
		settings.put("seed", CustomMethod.randomSeed);
		settings.put("timezone", TimeZone.getDefault().getID());
		settings.put("screen", frontend.screen().getWidth() + "x" + frontend.screen().getHeight());
		settings.put("device", AppSettings.devicePreset);
		settings.put("font", frontend.getProperty().getDefaultFontName());
		settings.put("locale", AppSettings.locale);
		settings.put("platform", AppSettings.microeditionPlatform);
		settings.put("frameRate", AppSettings.frameRate);
		settings.put("asyncFlush", AppSettings.asyncFlush);
		settings.put("synchronizeKeyEvents", AppSettings.synchronizeKeyEvents);
		settings.put("textAntiAliasing", Settings.textAntiAliasing);
		r.put("settings", settings);

		Map<String, Object> script = Json.object();
		script.put("path", HeadlessOptions.scriptPath);
		script.put("commands", commandsTotal);
		script.put("run", commandsRun);
		script.put("completed", commandsRun == commandsTotal);
		r.put("script", script);

		Map<String, Object> result = Json.object();
		result.put("status", status(code));
		result.put("exitCode", code);
		result.put("destroyed", destroyed);
		result.put("display", describe(current()));
		result.put("errors", new ArrayList<String>(errors));
		result.put("failures", new ArrayList<String>(failures));
		result.put("warnings", new ArrayList<String>(warnings));
		r.put("result", result);

		long runMs = recorder.time();
		long realMs = (System.nanoTime() - runStart) / 1000000L;
		Map<String, Object> time = Json.object();
		time.put("runMs", runMs);
		time.put("realMs", realMs);
		if (VirtualClock.enabled) {
			time.put("midletRealMs", busyNanos / 1000000L);
			time.put("speed", realMs > 0 ? (double) runMs / realMs : null);
			time.put("slowSteps", slowSteps);
		}
		r.put("time", time);

		Map<String, Object> frames = Json.object();
		frames.put("count", recorder.frames);
		frames.put("canvas", recorder.canvasFrames);
		frames.put("screen", recorder.screenFrames);
		frames.put("distinct", recorder.distinctFrames());
		frames.put("sequenceHash", recorder.sequenceHash());
		frames.put("lastHash", recorder.frames > 0 ? Recorder.hex(recorder.lastHash) : null);
		frames.put("perSecond", runMs > 0 ? recorder.frames * 1000.0 / runMs : null);
		frames.put("intervalMs", recorder.intervalVirtual.summary());
		frames.put("realIntervalMs", recorder.intervalReal.summary());
		frames.put("paintUs", recorder.paintMicros.summary());
		frames.put("drawCallsPerFrame", recorder.drawCallsPerFrame.summary());
		r.put("frames", frames);

		Map<String, Object> profiler = Json.object();
		for (int i = 0; i < Recorder.PROFILER.length; i++) {
			profiler.put(Recorder.PROFILER[i], recorder.profilerTotal[i]);
		}
		r.put("profiler", profiler);

		r.put("jvm", jvm());
		r.put("rms", rms);
		r.put("snaps", recorder.snaps());
		return r;
	}

	private static Map<String, Object> jvm() {
		Map<String, Object> m = Json.object();
		try {
			java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
			if (os instanceof com.sun.management.OperatingSystemMXBean) {
				m.put("cpuMs", ((com.sun.management.OperatingSystemMXBean) os).getProcessCpuTime() / 1000000L);
			}
		} catch (Throwable ignored) {
		}
		try {
			ThreadMXBean threads = ManagementFactory.getThreadMXBean();
			if (VirtualClock.enabled && threads.isThreadCpuTimeSupported()) {
				long cpu = 0;
				for (Thread t : VirtualClock.threads()) {
					long c = threads.getThreadCpuTime(t.getId());
					if (c > 0) {
						cpu += c;
					}
				}
				m.put("midletThreadsCpuMs", cpu / 1000000L);
			}
			m.put("threadsPeak", threads.getPeakThreadCount());
		} catch (Throwable ignored) {
		}
		long gcCount = 0, gcMs = 0;
		for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
			gcCount += Math.max(0, gc.getCollectionCount());
			gcMs += Math.max(0, gc.getCollectionTime());
		}
		m.put("gcCount", gcCount);
		m.put("gcMs", gcMs);
		long heapPeak = 0;
		for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
			if (pool.getType() == MemoryType.HEAP && pool.getPeakUsage() != null) {
				heapPeak += pool.getPeakUsage().getUsed();
			}
		}
		m.put("heapPeakBytes", heapPeak);
		m.put("classesLoaded", ManagementFactory.getClassLoadingMXBean().getTotalLoadedClassCount());
		return m;
	}

	/** rms.txt: one line per record store, "rms NAME HEX..." ("-" for a deleted record), as the script's rms command takes. */
	private static Map<String, Object> dumpRecordStores(File f) throws Exception {
		Map<String, Object> m = Json.object();
		String[] names = RecordStore.listRecordStores();
		int records = 0;
		long bytes = 0;
		PrintWriter w = new PrintWriter(new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8));
		java.util.List<String> sorted = new ArrayList<String>();
		if (names != null) {
			Collections.addAll(sorted, names);
			Collections.sort(sorted);
		}
		for (String name : sorted) {
			RecordStore rs = RecordStore.openRecordStore(name, false);
			StringBuilder sb = new StringBuilder("rms ").append(name);
			int next = rs.getNextRecordID();
			for (int id = 1; id < next; id++) {
				sb.append(' ');
				try {
					byte[] b = rs.getRecord(id);
					records++;
					if (b == null || b.length == 0) {
						sb.append("\"\"");
						continue;
					}
					bytes += b.length;
					for (byte x : b) {
						sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
					}
				} catch (InvalidRecordIDException e) {
					sb.append('-');
				}
			}
			rs.closeRecordStore();
			w.println(sb);
		}
		w.close();
		m.put("stores", sorted.size());
		m.put("records", records);
		m.put("bytes", bytes);
		return m;
	}

	private static byte[] unhex(Script.Command c, String s) {
		if (s.equals("\"\"")) {
			return new byte[0];
		}
		if (s.length() % 2 != 0) {
			throw new Script.ScriptException(c, "odd number of hex digits: " + s);
		}
		byte[] b = new byte[s.length() / 2];
		for (int i = 0; i < b.length; i++) {
			int hi = Character.digit(s.charAt(2 * i), 16);
			int lo = Character.digit(s.charAt(2 * i + 1), 16);
			if (hi < 0 || lo < 0) {
				throw new Script.ScriptException(c, "not hex: " + s);
			}
			b[i] = (byte) (hi << 4 | lo);
		}
		return b;
	}

	private static String threadDump() {
		if (VirtualClock.enabled) {
			return VirtualClock.describeThreads();
		}
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
			sb.append(e.getKey().getName()).append(" (").append(e.getKey().getState()).append(")\n");
			for (StackTraceElement s : e.getValue()) {
				sb.append("\tat ").append(s).append('\n');
			}
		}
		return sb.toString();
	}
}
