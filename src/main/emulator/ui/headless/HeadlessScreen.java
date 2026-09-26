package emulator.ui.headless;

import emulator.AppSettings;
import emulator.Emulator;
import emulator.EventQueue;
import emulator.graphics2D.IImage;
import emulator.graphics2D.awt.ImageAWT;
import emulator.ui.CommandsMenuPosition;
import emulator.ui.ICaret;
import emulator.ui.IScreen;
import emulator.ui.TargetedCommand;
import emulator.ui.bridge.DummyCaret;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Displayable;
import java.io.InputStream;
import java.util.Vector;

/**
 * The screen of a headless run: images in memory. Every repaint is a frame
 * for the recorder; what the SWT window would show around the screen (soft
 * key labels, the commands menu, messages) becomes an event in the trace.
 */
final class HeadlessScreen implements IScreen {
	private ImageAWT screen;
	private ImageAWT backBuffer;
	private ImageAWT xray;
	private String leftLabel = "";
	private String rightLabel = "";
	private String title;
	private Vector<TargetedCommand> commandsMenu;
	private final ICaret caret = new DummyCaret();
	volatile boolean started;

	HeadlessScreen() {
		createImages(240, 320);
	}

	private synchronized void createImages(int w, int h) {
		screen = new ImageAWT(w, h, false, -1);
		backBuffer = new ImageAWT(w, h, false, -1);
		xray = new ImageAWT(w, h, true, -1);
	}

	public void initScreen(int w, int h) {
		createImages(w, h);
	}

	public void setSize(int w, int h) {
		if (w != getWidth() || h != getHeight()) {
			createImages(w, h);
			HeadlessRunner.event("screen size " + w + "x" + h);
		}
	}

	public synchronized IImage getScreenImg() {
		return screen;
	}

	public synchronized IImage getBackBufferImage() {
		// as in the SWT window: without asyncFlush the MIDlet draws on the screen itself
		return AppSettings.asyncFlush ? backBuffer : screen;
	}

	public synchronized IImage getXRayScreenImage() {
		return xray;
	}

	public void repaint() {
		Displayable d = Emulator.getCurrentDisplay().getCurrent();
		if (d == null) {
			return;
		}
		EventQueue q = Emulator.getEventQueue();
		long paint = -1;
		if (q != null) {
			paint = q.lastPaintNanos;
			q.lastPaintNanos = -1;
		}
		HeadlessRunner.frame(getScreenImg(), d, paint);
	}

	public int getWidth() {
		return getScreenImg().getWidth();
	}

	public int getHeight() {
		return getScreenImg().getHeight();
	}

	public void setLeftSoftLabel(String label) {
		label = label == null ? "" : label;
		if (!label.equals(leftLabel)) {
			leftLabel = label;
			HeadlessRunner.event("soft1 \"" + label + "\"");
		}
	}

	public void setRightSoftLabel(String label) {
		label = label == null ? "" : label;
		if (!label.equals(rightLabel)) {
			rightLabel = label;
			HeadlessRunner.event("soft2 \"" + label + "\"");
		}
	}

	String leftLabel() {
		return leftLabel;
	}

	String rightLabel() {
		return rightLabel;
	}

	public void showCommandsList(Vector<TargetedCommand> cmds, CommandsMenuPosition target, int tx, int ty) {
		commandsMenu = cmds;
		StringBuilder sb = new StringBuilder("menu");
		int n = 0;
		for (TargetedCommand c : cmds) {
			if (c != null) {
				sb.append(' ').append(n++).append(":\"").append(c.text).append('"');
			}
		}
		HeadlessRunner.event(sb.toString());
	}

	public void forceCloseCommandsList() {
		commandsMenu = null;
	}

	/** The commands of the open commands menu (without separators), or null. */
	Vector<TargetedCommand> commandsMenu() {
		if (commandsMenu == null) {
			return null;
		}
		Vector<TargetedCommand> v = new Vector<TargetedCommand>();
		for (TargetedCommand c : commandsMenu) {
			if (c != null) {
				v.add(c);
			}
		}
		return v;
	}

	public void startVibra(long ms) {
		HeadlessRunner.event("vibra " + ms);
	}

	public void stopVibra() {
		HeadlessRunner.event("vibra 0");
	}

	public ICaret getCaret() {
		return caret;
	}

	public void setWindowIcon(InputStream in) {
	}

	public void showMessage(String message) {
		HeadlessRunner.message(message, null);
	}

	public void showMessage(String title, String detail) {
		HeadlessRunner.message(title, detail);
	}

	public void showMessageThreadSafe(String title, String detail) {
		showMessage(title, detail);
	}

	public int showMidletChoice(Vector<String> midletKeys) {
		return Math.max(0, Math.min(HeadlessOptions.midletIndex, midletKeys.size() - 1));
	}

	public int showUpdateDialog(int type) {
		return 1;
	}

	public boolean showSecurityDialog(String message) {
		HeadlessRunner.event("permission granted: " + message);
		return true;
	}

	public String showIMEIDialog() {
		return "";
	}

	public void setCurrent(Displayable d) {
		HeadlessRunner.event("display " + HeadlessRunner.describe(d));
		title = d == null ? null : d.getTitle();
	}

	public void updateTitle() {
		Displayable d = Emulator.getCurrentDisplay().getCurrent();
		String t = d == null ? null : d.getTitle();
		if (t != null && !t.equals(title) && !(d instanceof Canvas && t.isEmpty())) {
			HeadlessRunner.event("title \"" + t + "\"");
		}
		title = t;
	}

	public void runEmpty() {
		HeadlessRunner.noMidlet();
	}

	public void runWithMidlet() {
		HeadlessRunner.run();
	}

	public void appStarted(boolean first) {
		started = true;
		HeadlessRunner.event(first ? "started" : "resumed");
	}

	public boolean isShown() {
		return true;
	}
}
