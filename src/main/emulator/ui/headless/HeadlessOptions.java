package emulator.ui.headless;

import emulator.AppSettings;
import emulator.Emulator;
import emulator.Settings;
import emulator.VirtualClock;
import emulator.custom.CustomMethod;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

/**
 * The command line of a headless run (see HeadlessMode.md), parsed before
 * anything else happens, and the settings it makes.
 */
public final class HeadlessOptions {
	/** Virtual time starts at 2004-11-04 08:00:00 UTC unless -epoch says otherwise. */
	public static final long DEFAULT_EPOCH = 1099555200000L;

	/** Options without a value (KEmulator's own parser must not take the next argument as one). */
	private static final Set<String> FLAGS = new HashSet<String>(Arrays.asList(
			"headless", "vtime", "snap-changes", "verbose", "keep-going"));

	static String scriptPath;
	static File out;
	static boolean vtime;
	static long epoch = DEFAULT_EPOCH;
	static Long seed;
	static String timezone;
	static File userdir;
	static File rmsdir;
	static int snapEvery;
	static boolean snapChanges;
	static long idleTimeout = 10000;
	static long timeout;
	static long duration = -1;
	static boolean verbose;
	static boolean keepGoing;
	static String device;
	static String locale;
	static int midletIndex;
	static final List<String[]> sets = new ArrayList<String[]>();
	static Script script;
	static String commandLine;

	private HeadlessOptions() {
	}

	public static boolean isFlag(String key) {
		return FLAGS.contains(key.toLowerCase());
	}

	private static String value(String[] args, int i, String key) {
		if (i >= args.length) {
			throw new IllegalArgumentException("-" + key + " needs a value");
		}
		return args[i];
	}

	private static long number(String s, String key) {
		try {
			return Long.parseLong(s.trim());
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("-" + key + ": not a number: " + s);
		}
	}

	public static void parse(String[] args) {
		StringBuilder cl = new StringBuilder();
		String outPath = "headless-out";
		String userPath = null;
		String rmsPath = null;
		for (int i = 0; i < args.length; i++) {
			cl.append(i == 0 ? "" : " ").append(args[i]);
			String a = args[i];
			if (!a.startsWith("-")) {
				continue;
			}
			String key = a.substring(1).toLowerCase();
			if (key.equals("vtime")) {
				vtime = true;
			} else if (key.equals("snap-changes")) {
				snapChanges = true;
			} else if (key.equals("verbose")) {
				verbose = true;
			} else if (key.equals("keep-going")) {
				keepGoing = true;
			} else if (key.equals("script")) {
				scriptPath = value(args, ++i, key);
			} else if (key.equals("out")) {
				outPath = value(args, ++i, key);
			} else if (key.equals("epoch")) {
				epoch = number(value(args, ++i, key), key);
			} else if (key.equals("seed")) {
				seed = number(value(args, ++i, key), key);
			} else if (key.equals("timezone")) {
				timezone = value(args, ++i, key);
			} else if (key.equals("userdir")) {
				userPath = value(args, ++i, key);
			} else if (key.equals("rms")) {
				rmsPath = value(args, ++i, key);
			} else if (key.equals("snap-every")) {
				snapEvery = (int) number(value(args, ++i, key), key);
			} else if (key.equals("idle-timeout")) {
				idleTimeout = number(value(args, ++i, key), key);
			} else if (key.equals("timeout")) {
				timeout = number(value(args, ++i, key), key);
			} else if (key.equals("duration")) {
				duration = number(value(args, ++i, key), key);
			} else if (key.equals("device")) {
				device = value(args, ++i, key);
			} else if (key.equals("locale")) {
				locale = value(args, ++i, key);
			} else if (key.equals("midlet-index")) {
				midletIndex = (int) number(value(args, ++i, key), key);
			} else if (key.equals("set")) {
				addSet(value(args, ++i, key));
			}
		}
		commandLine = cl.toString();
		if (scriptPath != null) {
			try {
				script = Script.load(new File(scriptPath));
			} catch (IOException e) {
				throw new IllegalArgumentException("cannot read the script: " + e.getMessage());
			}
			for (Script.Command c : script.preamble) {
				applyPreamble(c);
			}
		}
		for (String[] s : sets) {
			field(s[0]);
		}

		out = new File(outPath).getAbsoluteFile();
		out.mkdirs();
		userdir = userPath != null ? new File(userPath).getAbsoluteFile() : new File(out, "user");
		userdir.mkdirs();
		rmsdir = rmsPath != null ? new File(rmsPath).getAbsoluteFile() : new File(userdir, "rms");
		rmsdir.mkdirs();
		Emulator.userPathOverride = userdir.getPath();

		// the defaults of KEmulator's own settings file, without the ones that
		// ask questions or keep every image for the memory view
		Settings.autoUpdate = 1;
		Settings.showAppSettingsOnStart = false;
		Settings.storeCreatedImages = false;
		Settings.countImagesInObjectsSize = false;
		Settings.canvasScale = 1f;
		Settings.canvasKeyboard = true;
		Settings.searchVms = true;
		Arrays.fill(Settings.recentJars, "");

		if (timezone != null || vtime) {
			TimeZone.setDefault(TimeZone.getTimeZone(timezone != null ? timezone : "UTC"));
		}
		if (vtime) {
			VirtualClock.enable(epoch);
			CustomMethod.randomSeed = seed;
		}
		applySettings();
	}

	private static void addSet(String s) {
		int eq = s.indexOf('=');
		if (eq <= 0) {
			throw new IllegalArgumentException("-set needs FIELD=VALUE: " + s);
		}
		sets.add(new String[] {s.substring(0, eq).trim(), s.substring(eq + 1)});
	}

	private static void applyPreamble(Script.Command c) {
		String w = c.word(0);
		if (w.equals("epoch")) {
			epoch = number(c.word(1), "epoch");
		} else if (w.equals("locale")) {
			locale = c.word(1);
		} else if (w.equals("seed")) {
			seed = number(c.word(1), "seed");
		} else if (w.equals("timezone")) {
			timezone = c.word(1);
		} else if (w.equals("device")) {
			device = c.rest(1);
		} else if (w.equals("set")) {
			addSet(c.rest(1));
		}
		// rms: loaded by HeadlessRunner.beforeMidlet
	}

	/** The -device preset (before the screen size and keys are worked out). */
	public static void applyDevice() {
		if (device != null) {
			if (!AppSettings.applyPreset(device, true)) {
				throw new IllegalArgumentException("no device preset " + device);
			}
			AppSettings.devicePreset = device;
		}
	}

	/** -locale and the -set fields of AppSettings and Settings. */
	public static void applySettings() {
		if (locale != null) {
			AppSettings.locale = locale;
		}
		for (String[] s : sets) {
			Field f = field(s[0]);
			try {
				Class<?> t = f.getType();
				String v = s[1].trim();
				if (t == int.class) {
					f.setInt(null, Integer.parseInt(v));
				} else if (t == long.class) {
					f.setLong(null, Long.parseLong(v));
				} else if (t == boolean.class) {
					f.setBoolean(null, Boolean.parseBoolean(v));
				} else if (t == float.class) {
					f.setFloat(null, Float.parseFloat(v));
				} else if (t == double.class) {
					f.setDouble(null, Double.parseDouble(v));
				} else if (t == String.class) {
					f.set(null, s[1]);
				} else {
					throw new IllegalArgumentException("-set " + s[0] + ": cannot set a " + t.getSimpleName());
				}
			} catch (IllegalAccessException e) {
				throw new IllegalArgumentException("-set " + s[0] + ": " + e);
			} catch (NumberFormatException e) {
				throw new IllegalArgumentException("-set " + s[0] + ": not a number: " + s[1]);
			}
		}
	}

	/** A public static field of AppSettings or Settings. */
	private static Field field(String name) {
		for (Class<?> c : new Class<?>[] {AppSettings.class, Settings.class}) {
			try {
				Field f = c.getField(name);
				int m = f.getModifiers();
				if (Modifier.isStatic(m) && !Modifier.isFinal(m)) {
					return f;
				}
			} catch (NoSuchFieldException ignored) {
			}
		}
		throw new IllegalArgumentException("-set: no setting " + name + " (a field of emulator.AppSettings or emulator.Settings)");
	}
}
