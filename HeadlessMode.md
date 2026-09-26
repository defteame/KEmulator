# Headless mode

KEmulator can run a MIDlet without a window, driven by a script, and write down
everything that reached the screen. That makes it usable for automated tests:
start a game, press keys at exact times, check what is on the screen, measure
it, and get an exit code that says whether the run passed.

With virtual time (`-vtime`) a run is deterministic: the same JAR and the same
script give the same frames, at the same times, on every run, and the run is
much faster than real time. Two builds of a game can then be compared frame by
frame.

```
headless/build.sh
headless/kemulator-headless.sh -jar game.jar -vtime -screen 128x128 -script test.txt -out results
```

- [Building](#building)
- [Running](#running)
- [Options](#options)
- [Scripts](#scripts)
- [Virtual time](#virtual-time)
- [Output](#output)
- [Exit codes](#exit-codes)
- [Differences from the window](#differences-from-the-window)
- [Recipes](#recipes)
- [How it is built](#how-it-is-built)

## Building

`headless/build.sh` builds KEmulator from the command line, without an IDE,
into `out/headless/`: `KEmulator.jar` (the emulator with its libraries inside,
like the `KEmulator_x64` artifact of the IDEA project), `micro3d_gl.jar`,
`micro3d_sw.jar`, and a copy of the runtime files of `home/`. The result is a
complete KEmulator directory; `java -jar out/headless/KEmulator.jar` opens the
normal window.

It needs a JDK (`javac` and `jar`). KEmulator targets Java 8 and uses one class
of the JDK's internal sound API, so it is compiled against Java 8's class
library: set `JAVA8_HOME` to a Java 8 JDK or JRE (or `RT_JAR` to its `rt.jar`).
Without one, the build targets Java 11 instead, and the result needs Java 11 or
later.

```
JAVA8_HOME=/usr/lib/jvm/java-8-openjdk headless/build.sh
```

Environment: `OUT` (output directory, default `out/headless`), `BUILD` (work
directory, default `out/build`), `JAVAC`, `JAR`, `JAVA8_HOME`, `RT_JAR`. On
Windows run it from Git Bash or MSYS2. Tested on Windows 11 (Java 8 class
library, JDK 25) and on Ubuntu 24.04 (JDK 21, without Java 8: the Java 11
build).

## Running

```
headless/kemulator-headless.sh -jar game.jar [options]
```

The launcher runs `out/headless/KEmulator.jar` (or `$KEM_DIR/KEmulator.jar`)
with `-headless` and the JVM options KEmulator needs: `-Djava.awt.headless=true`
and, on Java 9 and later, the `--add-opens` that KEmulator otherwise adds when
it restarts itself. `JAVA` chooses the Java to run it with, `JAVA_OPTS` adds JVM
options. Without the launcher:

```
java -Djava.awt.headless=true --add-opens java.base/java.lang=ALL-UNNAMED ... -jar KEmulator.jar -headless -jar game.jar ...
```

A headless run never opens a window or a dialog, never checks for updates and
never restarts itself. It does not load SWT's native libraries (only SWT's
image decoder, which is plain Java), so it runs where there is no display: on
a server, in a container, in CI. (Tested on Linux with `DISPLAY` and
`WAYLAND_DISPLAY` unset.)

Give the MIDlet with `-jar game.jar` (or `-jad game.jad`, or KEmulator's other
ways). Better not as a bare path: after one of KEmulator's own options without
a value (`-s`, `-log`, ...) it would be taken as that option's value.

## Options

The headless options, in addition to KEmulator's own (`-jar`, `-jad`, `-midlet`,
`-screen WxH`, `-fontname`, `-key`, ...):

| Option | Meaning |
|---|---|
| `-headless` | Run without a window (the launcher adds it). |
| `-vtime` | [Virtual time](#virtual-time): deterministic, faster than real time. |
| `-script FILE` | The [script](#scripts) to play. Without one the MIDlet runs for `-duration`. |
| `-duration MS` | How long to run without a script (default 5000). |
| `-out DIR` | Where the [results](#output) go (default `headless-out`). |
| `-screen WxH` | Screen size (KEmulator's option; default: from the JAD or JAR name, else the device preset). |
| `-device PRESET` | A device preset of `res/presets.xml`, e.g. `"128x128 (COMMON - full screen)"`: screen size, key codes, fonts. |
| `-fontname NAME` | The font of the MIDlet's text (default `Nokia`, the font KEmulator ships, so text looks the same on every machine). |
| `-locale TAG` | `microedition.locale` (default `en-US`). |
| `-epoch MS` | With `-vtime`: the clock at the start, in ms since 1970 (default 1099555200000, 2004-11-04 08:00 UTC). |
| `-seed N` | With `-vtime`: the seed of every `new Random()` (default: the virtual clock, as on a phone). |
| `-timezone ID` | The time zone (default UTC with `-vtime`, else the system's). |
| `-set FIELD=VALUE` | Sets a public static field of `emulator.AppSettings` or `emulator.Settings`: `-set frameRate=60`, `-set textAntiAliasing=false`, `-set enableKeyRepeat=false`. Repeatable. |
| `-userdir DIR` | Where KEmulator keeps its files for the run (default `OUT/user`). |
| `-rms DIR` | The record stores (default `USERDIR/rms`, empty at the start unless you reuse it). |
| `-snap-every N` | Save every Nth frame as `frames/fNNNNNN.png`. |
| `-snap-changes` | Save every frame that differs from the one before. |
| `-idle-timeout MS` | With `-vtime`: how long the MIDlet's threads may stay busy before the run counts as hung (default 10000; see [limits](#limits)). |
| `-timeout MS` | Ends the run (exit code 3) if it takes longer than this, in real time. |
| `-keep-going` | Go on with the script after a failed `expect` or `until`. |
| `-verbose` | Echo KEmulator's log and the MIDlet's output to the console. |
| `-midlet-index N` | Which MIDlet of a suite with several (default 0). |

Nothing is read from or written to KEmulator's `property.txt` or `midlets.ini`:
a run depends only on its command line, so it can be repeated anywhere.

## Scripts

One command per line. Comments run from `//` to the end of the line; a line
that starts with `# ` is a comment too (`#` alone is the key).

```
# Start a new game from the menu and paddle for a while.
epoch 1099555200000      // settings first: they apply before the MIDlet starts
locale en
start

until 10000 list         // wait (up to 10 s) for the menu
select 0                 // "New game"
until 2000 canvas
wait 3500
hold 8 3000              // key 8 for 3 s
tap right                // a short press (100 ms)
snap playing             // results/playing.png
expect canvas
```

### Settings

At the top of the script, before the first command (or before an optional
`start`), these apply before the MIDlet starts:

| Setting | Meaning |
|---|---|
| `epoch MS` | as `-epoch` |
| `locale TAG` | as `-locale` |
| `seed N` | as `-seed` |
| `timezone ID` | as `-timezone` |
| `device PRESET` | as `-device` |
| `set FIELD=VALUE` | as `-set` |
| `rms NAME HEX...` | Creates record store NAME with one record per argument, in hex; `-` makes a deleted record (so that the next record gets the next ID), `""` an empty one. `rms.txt` of a run has the same form, so the record stores at the end of one run can start another. |

### Commands

| Command | Meaning |
|---|---|
| `wait MS` | Let MS milliseconds pass. |
| `press KEY`, `release KEY`, `repeat KEY` | A key event. |
| `tap KEY [MS]`, `hold KEY [MS]` | Press, wait MS (default 100), release. |
| `select N` | Select item N of the current List and send its select command (as the fire key would). |
| `command TYPE` or `command LABEL` | Send the current displayable's first command of that type (`BACK`, `EXIT`, `OK`, `CANCEL`, `STOP`, `HELP`, `SCREEN`, `ITEM`) or with that label. |
| `menu N` | Choose item N of the commands menu a soft key opened. |
| `text STRING` | Set the text of the current TextBox. |
| `hide`, `show` | `hideNotify` / `showNotify` of the current Canvas (an incoming call). |
| `pause`, `resume` | `pauseApp` / `startApp`, as the window's pause and resume. |
| `snap NAME` | Save the screen as `NAME.png`. |
| `until MS CONDITION` | Let time pass until CONDITION holds; a failure if it does not within MS. |
| `expect CONDITION` | A failure if CONDITION does not hold now. |
| `fuzz SEED MS KEY...` | Random presses and releases of the keys for MS milliseconds (at most two held at a time). Back on a List it selects item 0, on another Screen it sends BACK. The same seed gives the same presses. |
| `exit` | `destroyApp(true)`; the run ends. |
| `log TEXT` | Write TEXT to the trace. |
| `start` | Nothing (the MIDlet has started when the script begins); ends the settings. |

A failed `expect` or `until` ends the script unless `-keep-going`.

Keys: `0`-`9`, `*` (`star`), `#` (`pound`), `up`, `down`, `left`, `right`,
`select` (`fire`), `soft1` (`leftsoft`), `soft2` (`rightsoft`), or a key code
(`-6`, `53`). The names map to the key codes of the device preset.

Conditions: `canvas`, `list`, `form`, `alert`, `textbox`, `screen` (the current
displayable is one), `title TEXT`, `selected N` and `item TEXT` (the List's
selected item), `soft1 TEXT` and `soft2 TEXT` (soft key labels), `frame HASH`
(the last frame's hash), `frames N` (at least N frames so far), `destroyed`,
`alive`, and `not CONDITION`.

## Virtual time

With `-vtime` time does not pass by itself. The script moves it: `wait 1000`
runs everything the MIDlet would do in that second (timer tasks, sleeping
threads waking up, repaints), jumping from one moment something happens to the
next, without waiting in real time. Key presses arrive at exact moments. The
run is deterministic and usually 10 to 100 times faster than real time.

What goes through the virtual clock, in the MIDlet's code: `System.currentTimeMillis`,
`Thread.sleep`, `Thread.yield`, `Thread.join`, `Object.wait`, `notify` and
`notifyAll`, `java.util.Timer` and `TimerTask`, `new Date()`,
`Calendar.getInstance()`, `new Random()` (seeded from the clock, or from
`-seed`); in the emulator: the event queue, repaints, `Display.callSerially`,
Alert timeouts, record store timestamps. The time zone is UTC unless
`-timezone` says otherwise.

How it works: every thread that runs MIDlet code (the event thread, timer
threads, the threads of `startApp`, the threads the MIDlet starts) belongs to
one thread group, and counts as idle only while it waits in one of the places
listed above. After every step the driver waits until all of them are idle.
Then it wakes one waiter: first those that were notified, in the order of the
notifications, then those whose time has come, earliest first. When nothing is
left to do at this moment, it moves the clock to the next deadline. A
notification does not wake the other thread at once: it runs when the notifying
thread is idle. So MIDlet code runs on one thread at a time, in an order that
depends only on the MIDlet and the script, as on a phone with one processor.

A thread that reads the clock 100,000 times without becoming idle is waiting
for time to pass in a busy loop; the clock then moves on by 1 ms.

### Limits

- A thread that waits for something outside the virtual clock (a media player,
  a network connection, native code) keeps the driver waiting. After
  `-idle-timeout` such a thread counts as idle from then on (a warning in the
  report and the trace), and the run may not repeat exactly. A thread that
  stays *running* that long (an endless loop) is a hang: exit code 3, with the
  stacks of the busy threads in `log.txt`.
- Frames are compared by their pixels, and text is drawn by Java's font
  rasterizer. The same run gave the same frames on Windows 11 with Java 25 and
  on Linux (Ubuntu 24.04 under WSL2) with Java 21, but another Java version may
  draw antialiased text differently; `-set textAntiAliasing=false` makes that
  less likely.
- Sound plays as in the window (if there is a sound device); players are not
  on the virtual clock.
- 3D (M3G, Mascot Capsule) needs the same OpenGL setup as in the window.

Without `-vtime` the run is in real time: `wait 1000` is a real second, and the
MIDlet runs freely as in the window. Use it to measure how fast a MIDlet runs.

## Output

The output directory (`-out`) gets:

| File | What |
|---|---|
| `trace.txt` | Every frame and every event, in order. Only what the MIDlet and the script decide: with `-vtime`, two runs of the same script give the same file, byte for byte. |
| `frames.csv` | Measurements for every frame (they differ from run to run). |
| `report.json` | The summary: result, time, frames, profiler counters, JVM statistics. |
| `rms.txt` | The record stores at the end, one line each, in the form of the script's `rms` setting. |
| `NAME.png` | Screenshots (`snap`). |
| `frames/` | Frames saved by `-snap-every` and `-snap-changes`. |
| `log.txt` | KEmulator's log and everything the MIDlet printed. |
| `user/` | KEmulator's files for the run, with the record stores (`user/rms`). |

### trace.txt

```
E 0 0 display Canvas
F 1 0 d18ea89c5f1d0090 Canvas
E 17 3200 display List "Water Rapids"
E 17 3200 soft2 "Exit"
F 18 3200 b28196161331f955 List "Water Rapids"
E 18 4000 > select 0
E 18 4000 select 0 "New game"
F 23 4083 43c505a1f107b08c Canvas
```

`F FRAME TIME HASH DISPLAYABLE`: a frame reached the screen (a paint of a
Canvas, `flushGraphics`, or a Screen drawn by the emulator). TIME is ms since
the start of the run (virtual with `-vtime`), HASH the 64-bit FNV-1a hash of
the screen's RGB pixels, DISPLAYABLE the current displayable and its title.

`E FRAMES TIME TEXT`: an event, after FRAMES frames. The script's commands
(`> select 0`) and what they did (`select 0 "New game"`, `press 53`,
`command "Back"`, `snap NAME HASH`, `ok CONDITION`, `reached CONDITION`,
`FAILED ...`); what the window would show outside the screen (`display ...`,
`title ...`, `soft1 "..."`, `soft2 "..."`, `menu 0:"..." 1:"..."`,
`message "..."`, `vibra MS`); the MIDlet's life (`started`, `resumed`,
`destroyed`, `exception in WHERE: ...`); `rms NAME N records` for the record
stores the settings created; warnings.

### frames.csv

`frame, time_ms, real_ms, paint_us, draw_calls, draw_image_calls,
draw_region_calls, draw_rgb_calls, nokia_draw_image_calls,
nokia_draw_pixel_calls, hash, displayable`: for every frame the real time since
the start, how long the paint took (µs; empty for `flushGraphics`), and the
drawing calls the MIDlet made since the frame before (KEmulator's profiler
counters).

### report.json

| Field | What |
|---|---|
| `kemulator`, `java`, `commandLine` | What ran. |
| `midlet` | JAR path and SHA-256, MIDlet class, name, vendor, version. |
| `settings` | Virtual time, epoch, seed, time zone, screen, device, font, locale, platform, frame rate and the other settings that change what the MIDlet sees. |
| `script` | Path, number of commands, how many ran, `completed`. |
| `result` | `status` (see [exit codes](#exit-codes)), `exitCode`, `destroyed`, the current `display` at the end, `errors`, `failures`, `warnings`. |
| `time` | `runMs` (the run's length: virtual with `-vtime`), `realMs`; with `-vtime` also `midletRealMs` (real time the MIDlet's threads ran), `speed` (virtual/real) and `slowSteps` (steps that took the threads 250 ms or more of real time to finish). |
| `frames` | `count` (`canvas`, `screen`), `distinct` hashes, `sequenceHash` (one hash over every frame's time and hash: two runs with the same one showed the same frames at the same times), `lastHash`, `perSecond`; `intervalMs` and `realIntervalMs` between frames, `paintUs`, `drawCallsPerFrame`, each as count, min, mean, p50, p95, p99, max. |
| `profiler` | Totals of KEmulator's profiler counters: drawing calls and pixels by kind, `System.gc` and `currentTimeMillis` calls. |
| `jvm` | Process CPU time, CPU time of the MIDlet's threads (`-vtime`), peak threads, GC count and time, peak heap, classes loaded. |
| `rms` | Record stores, records and bytes at the end. |
| `snaps` | Screenshots taken. |

## Exit codes

| Code | Status | Meaning |
|---|---|---|
| 0 | `ok` | The script ran to its end. |
| 1 | `error` | The MIDlet threw an exception (in a callback, a timer task, a thread), could not be started, or the script has an error. |
| 2 | `failed` | An `expect` or `until` failed. |
| 3 | `hang` | The MIDlet's threads never became idle (`-idle-timeout`), or `-timeout` passed. |
| 4 | `usage` | Bad options, or no MIDlet to run. |
| 5 | `exited` | The MIDlet exited before the script ended (without the script's `exit` or `expect destroyed`). |

Every run writes `report.json`, also when the MIDlet cannot be started or the
run is interrupted.

## Differences from the window

- Lists and text boxes are native SWT widgets in the window. Headless (and in
  the bridge frontend) the emulator draws them itself, as it draws forms
  (`ListLCDUI`, `TextBoxLCDUI`): up and down move the selection, the fire key
  selects. They look different from the window's, and they are in the frames.
- Soft key labels and the commands menu are drawn by the window around the
  screen; headless they are events in the trace (`soft1`, `soft2`, `menu`).
- The default font is the `Nokia` font KEmulator ships, not a system font.
- Settings come from the command line only (see [options](#options)).
- Keys do not repeat by themselves: the script sends `repeat`.
- Security questions are answered "allow".

## Recipes

Does a run repeat exactly?

```
kemulator-headless.sh -jar game.jar -vtime -script test.txt -out run1
kemulator-headless.sh -jar game.jar -vtime -script test.txt -out run2
cmp run1/trace.txt run2/trace.txt && cmp run1/rms.txt run2/rms.txt
```

Does a new build of a game behave as the old one? Run both under the same
script with `-vtime` and compare `trace.txt` and `rms.txt` (or just
`frames.sequenceHash` of the reports); the first differing line of the traces
says where they part.

How fast is it? Run in real time (without `-vtime`) and read `frames.perSecond`
and `frames.realIntervalMs`; `frames.paintUs` and `frames.drawCallsPerFrame`
(also measured with `-vtime`) say what one frame costs.

Look at a run: `-snap-changes` saves every new frame as a PNG.

In CI, for example GitHub Actions on Linux (a sketch: the steps are the ones
tested under Linux above, the workflow itself has not run on GitHub):

```yaml
- uses: actions/setup-java@v4
  with:
    distribution: temurin
    java-version: |
      8
      17
- run: JAVA8_HOME=$JAVA_HOME_8_X64 headless/build.sh
- run: headless/kemulator-headless.sh -jar game.jar -vtime -script test.txt -out results
- uses: actions/upload-artifact@v4
  if: always()
  with:
    name: results
    path: results
```

## How it is built

| File | Role |
|---|---|
| `src/main/emulator/ui/headless/HeadlessFrontend.java` | The frontend: images and fonts (AWT, as in the window), log, settings. |
| `src/main/emulator/ui/headless/HeadlessScreen.java` | The screen: every repaint is a frame; soft keys, menus and messages become events. |
| `src/main/emulator/ui/headless/HeadlessRunner.java` | Plays the script, moves the clock, checks conditions, writes the report. |
| `src/main/emulator/ui/headless/HeadlessOptions.java` | The command line; the settings that apply before the MIDlet starts. |
| `src/main/emulator/ui/headless/Script.java` | Reads scripts. |
| `src/main/emulator/ui/headless/Recorder.java` | `trace.txt`, `frames.csv`, screenshots, frame statistics. |
| `src/main/emulator/VirtualClock.java` | Virtual time: the managed threads, their waits, the order they run in. |
| `src/main/emulator/custom/CustomMethodAdapter.java` | With `-vtime`, sends the MIDlet's clock, wait, notify, sleep, yield, join, Date, Calendar and Random calls to `CustomMethod` and so to the virtual clock. |
| `src/main/emulator/lcdui/ListLCDUI.java`, `TextBoxLCDUI.java` | List and TextBox drawn by the emulator. |
| `headless/build.sh`, `headless/kemulator-headless.sh` | Build and launcher. |

Without `-headless` nothing of this is active and KEmulator behaves as before:
the virtual clock methods fall back to the plain Java ones, and the class
loader changes nothing.
