package emulator.ui.headless;

import emulator.VirtualClock;
import emulator.debug.Profiler;
import emulator.graphics2D.IImage;
import emulator.graphics2D.awt.ImageAWT;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a headless run writes to its output directory:
 * <ul>
 * <li>trace.txt: one line per frame and per event, only what the MIDlet and
 * the script decide (with -vtime, two runs of the same script give the same
 * file):<br>
 * <code>F frame time hash displayable</code> and <code>E frame time text</code>
 * (time in ms since the start, hash: 64-bit FNV-1a of the screen's RGB)</li>
 * <li>frames.csv: measurements per frame (real time, paint time, drawing
 * calls), which differ from run to run</li>
 * <li>frames/: screenshots (snap, -snap-every, -snap-changes)</li>
 * <li>report.json: the summary (see HeadlessRunner.finish)</li>
 * </ul>
 */
final class Recorder {
	private final File dir;
	private final PrintWriter trace;
	private final PrintWriter csv;
	private final long realStart = System.nanoTime();
	private final long virtualStart;

	int frames;
	int canvasFrames;
	int screenFrames;
	long lastHash;
	private boolean haveLast;
	private final Set<Long> distinct = new HashSet<Long>();
	private long sequence = 0xcbf29ce484222325L;
	private int snaps;

	final Stats intervalVirtual = new Stats();
	final Stats intervalReal = new Stats();
	final Stats paintMicros = new Stats();
	final Stats drawCallsPerFrame = new Stats();
	private long lastVirtual = -1;
	private long lastReal = -1;

	/** Profiler counters at the last frame, and their sums over the run. */
	private final long[] profilerLast = new long[PROFILER.length];
	final long[] profilerTotal = new long[PROFILER.length];

	static final String[] PROFILER = {
			"drawCalls", "drawImageCalls", "drawImagePixels", "drawRegionCalls", "drawRegionPixels",
			"drawRGBCalls", "drawRGBPixels", "nokiaDrawImageCalls", "nokiaDrawImagePixels",
			"nokiaDrawPixelCalls", "nokiaDrawPixelPixels", "gcCalls", "currentTimeMillisCalls"
	};

	Recorder(File dir) throws IOException {
		this.dir = dir;
		dir.mkdirs();
		trace = new PrintWriter(new OutputStreamWriter(new FileOutputStream(new File(dir, "trace.txt")), StandardCharsets.UTF_8));
		csv = new PrintWriter(new OutputStreamWriter(new FileOutputStream(new File(dir, "frames.csv")), StandardCharsets.UTF_8));
		csv.println("frame,time_ms,real_ms,paint_us,draw_calls,draw_image_calls,draw_region_calls,draw_rgb_calls,"
				+ "nokia_draw_image_calls,nokia_draw_pixel_calls,hash,displayable");
		virtualStart = HeadlessOptions.epoch;
		snapshotProfiler(profilerLast);
	}

	/** ms since the start of the run: virtual with -vtime, else real. */
	long time() {
		if (stopped) {
			return end;
		}
		return VirtualClock.enabled ? VirtualClock.now() - virtualStart : realMillis();
	}

	long realMillis() {
		return (System.nanoTime() - realStart) / 1000000L;
	}

	synchronized void event(String text) {
		if (stopped) {
			return;
		}
		trace.println("E " + frames + " " + time() + " " + text.replace('\n', ' '));
		trace.flush();
	}

	/**
	 * Ends the recording: what the MIDlet does from now on (in real time it
	 * goes on running while the report is written) is not recorded.
	 */
	synchronized void stop() {
		if (!stopped) {
			end = time();
			stopped = true;
		}
	}

	private volatile boolean stopped;
	/** The time the recording stopped. */
	private long end;

	/** A frame reached the screen. */
	synchronized void frame(IImage screen, String displayable, boolean canvas, long paintNanos) {
		if (stopped) {
			return;
		}
		BufferedImage img = ((ImageAWT) screen).getBufferedImage();
		int w = img.getWidth(), h = img.getHeight();
		int[] px = img.getRGB(0, 0, w, h, null, 0, w);
		long hash = hash(px);
		long t = time();
		long real = realMillis();
		frames++;
		if (canvas) {
			canvasFrames++;
		} else {
			screenFrames++;
		}
		distinct.add(hash);
		sequence = mix(mix(sequence, t), hash);
		if (lastVirtual >= 0) {
			intervalVirtual.add(t - lastVirtual);
			intervalReal.add(real - lastReal);
		}
		lastVirtual = t;
		lastReal = real;
		if (paintNanos >= 0) {
			paintMicros.add(paintNanos / 1000);
		}
		long[] now = new long[PROFILER.length];
		snapshotProfiler(now);
		long[] delta = new long[PROFILER.length];
		for (int i = 0; i < now.length; i++) {
			delta[i] = now[i] - profilerLast[i];
			profilerTotal[i] += delta[i];
		}
		System.arraycopy(now, 0, profilerLast, 0, now.length);
		drawCallsPerFrame.add(delta[0]);

		String hex = hex(hash);
		trace.println("F " + frames + " " + t + " " + hex + " " + displayable);
		csv.println(frames + "," + t + "," + real + "," + (paintNanos >= 0 ? paintNanos / 1000 : "") + ","
				+ delta[0] + "," + delta[1] + "," + delta[3] + "," + delta[5] + "," + delta[7] + "," + delta[9] + ","
				+ hex + "," + csvText(displayable));
		boolean changed = !haveLast || hash != lastHash;
		if ((HeadlessOptions.snapEvery > 0 && (frames - 1) % HeadlessOptions.snapEvery == 0)
				|| (HeadlessOptions.snapChanges && changed)) {
			write(img, new File(dir, "frames" + File.separator + String.format("f%06d.png", frames)));
		}
		lastHash = hash;
		haveLast = true;
	}

	/** Saves the screen as NAME.png; returns its hash. */
	synchronized String snap(IImage screen, String name) {
		BufferedImage img = ((ImageAWT) screen).getBufferedImage();
		int w = img.getWidth(), h = img.getHeight();
		int[] px = img.getRGB(0, 0, w, h, null, 0, w);
		BufferedImage copy = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		copy.setRGB(0, 0, w, h, px, 0, w);
		write(copy, new File(dir, name + ".png"));
		snaps++;
		return hex(hash(px));
	}

	int distinctFrames() {
		return distinct.size();
	}

	String sequenceHash() {
		return hex(sequence);
	}

	int snaps() {
		return snaps;
	}

	synchronized void close() {
		trace.flush();
		trace.close();
		csv.flush();
		csv.close();
	}

	File dir() {
		return dir;
	}

	private static void write(BufferedImage img, File f) {
		try {
			f.getParentFile().mkdirs();
			ImageIO.write(img, "png", f);
		} catch (IOException e) {
			HeadlessRunner.warning("cannot write " + f + ": " + e);
		}
	}

	private static void snapshotProfiler(long[] a) {
		a[0] = Profiler.drawCallCount;
		a[1] = Profiler.drawImageCallCount;
		a[2] = Profiler.drawImagePixelCount;
		a[3] = Profiler.drawRegionCallCount;
		a[4] = Profiler.drawRegionPixelCount;
		a[5] = Profiler.drawRGBCallCount;
		a[6] = Profiler.drawRGBPixelCount;
		a[7] = Profiler.nokiaDrawImageCallCount;
		a[8] = Profiler.nokiaDrawImagePixelCount;
		a[9] = Profiler.nokiaDrawPixelCallCount;
		a[10] = Profiler.nokiaDrawPixelPixelCount;
		a[11] = Profiler.gcCallCount;
		a[12] = Profiler.currentTimeMillisCallCount;
	}

	/** 64-bit FNV-1a over the RGB bytes of the pixels. */
	static long hash(int[] pixels) {
		long h = 0xcbf29ce484222325L;
		for (int p : pixels) {
			h ^= (p >> 16) & 0xFF;
			h *= 0x100000001b3L;
			h ^= (p >> 8) & 0xFF;
			h *= 0x100000001b3L;
			h ^= p & 0xFF;
			h *= 0x100000001b3L;
		}
		return h;
	}

	private static long mix(long h, long v) {
		for (int i = 0; i < 8; i++) {
			h ^= (v >>> (8 * i)) & 0xFF;
			h *= 0x100000001b3L;
		}
		return h;
	}

	static String hex(long h) {
		String s = Long.toHexString(h);
		return "0000000000000000".substring(s.length()) + s;
	}

	private static String csvText(String s) {
		if (s.indexOf(',') < 0 && s.indexOf('"') < 0) {
			return s;
		}
		return '"' + s.replace("\"", "\"\"") + '"';
	}

	/** Count, sum, extremes and percentiles of a series of values. */
	static final class Stats {
		private long[] values = new long[256];
		private int n;
		private long sum;

		void add(long v) {
			synchronized (this) {
				if (n == values.length) {
					values = Arrays.copyOf(values, n * 2);
				}
				values[n++] = v;
				sum += v;
			}
		}

		int count() {
			return n;
		}

		Map<String, Object> summary() {
			Map<String, Object> m = Json.object();
			long[] sorted;
			long total;
			synchronized (this) {
				sorted = Arrays.copyOf(values, n);
				total = sum;
			}
			int n = sorted.length;
			m.put("count", n);
			if (n == 0) {
				return m;
			}
			Arrays.sort(sorted);
			m.put("min", sorted[0]);
			m.put("mean", (double) total / n);
			m.put("p50", sorted[(n - 1) / 2]);
			m.put("p95", sorted[(int) Math.min(n - 1, Math.ceil(n * 0.95) - 1)]);
			m.put("p99", sorted[(int) Math.min(n - 1, Math.ceil(n * 0.99) - 1)]);
			m.put("max", sorted[n - 1]);
			return m;
		}
	}

	static List<String> profilerNames() {
		return Arrays.asList(PROFILER);
	}
}
