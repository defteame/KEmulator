package emulator.ui.headless;

import emulator.ui.IProperty;

import java.io.File;
import java.io.IOException;

/**
 * Global settings of a headless run. Nothing is read from or written to
 * KEmulator's property.txt: the defaults below and the command line decide.
 * The default font is the Nokia font KEmulator ships, so text looks the same
 * on every machine (-fontname chooses another).
 */
final class HeadlessProperty implements IProperty {
	private String defaultFont = "Nokia";
	private final String monospaceFont = "Monospaced";

	public String getRmsFolderPath() {
		File dir = HeadlessOptions.rmsdir;
		dir.mkdirs();
		try {
			return dir.getCanonicalPath() + File.separator;
		} catch (IOException e) {
			return dir.getAbsolutePath() + File.separator;
		}
	}

	public String getOldRmsPath() {
		return getRmsFolderPath() + "legacy" + File.separator;
	}

	public String getDefaultFontName() {
		return defaultFont;
	}

	String getMonospaceFontName() {
		return monospaceFont;
	}

	public void setDefaultFontName(String name) {
		defaultFont = name;
	}

	public void saveProperties() {
	}

	public void loadProperties() {
	}

	public boolean updateController() {
		return false;
	}
}
