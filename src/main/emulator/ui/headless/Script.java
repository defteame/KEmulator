package emulator.ui.headless;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A headless script: one command per line. Comments run from "//" to the
 * end of the line; a line starting with "# " is a comment too ("#" alone is
 * the key). The settings at the top (epoch, locale, seed, timezone, device,
 * set, rms), up to an optional "start", are made before the MIDlet starts.
 */
final class Script {
	static final Set<String> PREAMBLE = new HashSet<String>(Arrays.asList(
			"epoch", "locale", "seed", "timezone", "device", "set", "rms"));

	static final class Command {
		final int line;
		final String[] words;
		final String text;

		Command(int line, String text) {
			this.line = line;
			this.text = text;
			this.words = text.split("\\s+");
		}

		String word(int i) {
			if (i >= words.length) {
				throw new ScriptException(this, "missing argument " + i);
			}
			return words[i];
		}

		int count() {
			return words.length;
		}

		long number(int i) {
			try {
				return Long.parseLong(word(i));
			} catch (NumberFormatException e) {
				throw new ScriptException(this, "not a number: " + word(i));
			}
		}

		/** The words from i on, as written. */
		String rest(int i) {
			int pos = 0;
			for (int n = 0; n < i; n++) {
				while (pos < text.length() && !Character.isWhitespace(text.charAt(pos))) {
					pos++;
				}
				while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
					pos++;
				}
			}
			if (pos >= text.length()) {
				throw new ScriptException(this, "missing argument " + i);
			}
			return text.substring(pos);
		}

		public String toString() {
			return text;
		}
	}

	static final class ScriptException extends RuntimeException {
		ScriptException(Command c, String message) {
			super("line " + c.line + " (" + c.text + "): " + message);
		}
	}

	final String path;
	final List<Command> preamble = new ArrayList<Command>();
	final List<Command> body = new ArrayList<Command>();

	private Script(String path) {
		this.path = path;
	}

	static Script load(File f) throws IOException {
		Script s = new Script(f.getPath());
		List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
		boolean inPreamble = true;
		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i);
			int slash = line.indexOf("//");
			if (slash >= 0) {
				line = line.substring(0, slash);
			}
			line = line.trim();
			if (line.isEmpty() || line.startsWith("# ")) {
				continue;
			}
			Command c = new Command(i + 1, line);
			if (inPreamble && PREAMBLE.contains(c.word(0))) {
				s.preamble.add(c);
				continue;
			}
			if (inPreamble && c.word(0).equals("start")) {
				inPreamble = false;
				continue;
			}
			inPreamble = false;
			s.body.add(c);
		}
		return s;
	}
}
