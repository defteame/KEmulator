package emulator.ui.headless;

import emulator.Emulator;
import emulator.graphics2D.IFont;
import emulator.graphics2D.IImage;
import emulator.graphics2D.awt.FontAWT;
import emulator.graphics2D.awt.ImageAWT;
import emulator.graphics3D.IGraphics3D;
import emulator.ui.IEmulatorFrontend;
import emulator.ui.ILogStream;
import emulator.ui.IMessage;
import emulator.ui.IPlugin;
import emulator.ui.IProperty;
import emulator.ui.IScreen;

import javax.microedition.lcdui.Font;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * The frontend of a headless run (-headless): no window, no SWT. The MIDlet
 * draws into AWT images, as in the SWT window (whose 2D engine is AWT too);
 * HeadlessRunner plays the script and records what reaches the screen. See
 * HeadlessMode.md.
 */
public final class HeadlessFrontend implements IEmulatorFrontend {
	private final HeadlessProperty property = new HeadlessProperty();
	private final HeadlessLog log;
	private final HeadlessScreen screen = new HeadlessScreen();
	private Properties midletProps;

	public HeadlessFrontend() throws IOException {
		log = new HeadlessLog(new File(HeadlessOptions.out, "log.txt"), HeadlessOptions.verbose);
		HeadlessRunner.init(this);
	}

	HeadlessScreen screen() {
		return screen;
	}

	HeadlessLog log() {
		return log;
	}

	public IMessage getMessage() {
		return null;
	}

	public ILogStream getLogStream() {
		return log;
	}

	public IProperty getProperty() {
		return property;
	}

	public IScreen getScreen() {
		return screen;
	}

	public int getScreenDepth() {
		return 24;
	}

	public void pushPlugin(IPlugin p) {
	}

	public void disposeSubWindows() {
	}

	private String fontName(int face) {
		return face == Font.FACE_MONOSPACE ? property.getMonospaceFontName() : property.getDefaultFontName();
	}

	public IFont newFont(int face, int size, int style) {
		return new FontAWT(fontName(face), size, style, false);
	}

	public IFont newCustomFont(int face, int size, int style, boolean height) {
		return new FontAWT(fontName(face), size, style, height);
	}

	public IFont newFont(String name, int style, int pixelSize) {
		return new FontAWT(name, pixelSize, style, false);
	}

	public IFont loadFont(InputStream in, int size) throws IOException {
		return new FontAWT(in, size);
	}

	public IImage newImage(int w, int h, boolean transparent) {
		return new ImageAWT(w, h, transparent, -1);
	}

	public IImage newImage(int w, int h, boolean transparent, int color) {
		return new ImageAWT(w, h, transparent, color);
	}

	public IImage newImage(byte[] data) throws IOException {
		return new ImageAWT(data);
	}

	public IGraphics3D getGraphics3D() {
		return Emulator.getPlatform().getGraphics3D();
	}

	public void syncValues() {
	}

	public String getAppProperty(String key) {
		String value;
		if (midletProps != null && (value = midletProps.getProperty(key)) != null) {
			return value.trim();
		}
		return null;
	}

	public Properties getAppProperties() {
		return midletProps;
	}

	public void setAppProperties(Properties p) {
		midletProps = p;
	}

	public void updateLanguage() {
	}

	public void dispose() {
		log.close();
	}

	public void openAppSettings(boolean start) {
	}
}
