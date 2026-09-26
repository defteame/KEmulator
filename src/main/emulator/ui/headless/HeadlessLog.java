package emulator.ui.headless;

import emulator.custom.CustomMethod;
import emulator.ui.ILogStream;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;

/**
 * The emulator's log, and everything printed to System.out and System.err
 * (the MIDlet's output too), go to log.txt in the output directory; with
 * -verbose also to the console. The driver's own messages keep the console.
 */
final class HeadlessLog implements ILogStream {
	/** The console as it was at start (the driver's messages). */
	static PrintStream console = System.out;
	private final PrintStream file;

	HeadlessLog(File f, boolean verbose) throws IOException {
		console = System.out;
		final PrintStream err = System.err;
		final OutputStream fileOut = new FileOutputStream(f);
		OutputStream tee = new OutputStream() {
			public void write(int b) throws IOException {
				fileOut.write(b);
				if (verbose) {
					err.write(b);
				}
			}

			public void write(byte[] b, int off, int len) throws IOException {
				fileOut.write(b, off, len);
				if (verbose) {
					err.write(b, off, len);
				}
			}

			public void flush() throws IOException {
				fileOut.flush();
				if (verbose) {
					err.flush();
				}
			}
		};
		file = new PrintStream(tee, true, "UTF-8");
		System.setOut(file);
		System.setErr(file);
	}

	public void print(String s) {
		file.print(s);
	}

	public void println(String s) {
		file.println(s);
	}

	public void println() {
		file.println();
	}

	public void printStackTrace(String s) {
		file.println("==StackTrace==" + s + "==StackTrace==");
	}

	public void stdout(String s) {
		file.println(s);
	}

	public void println(Throwable e) {
		file.println(CustomMethod.getStackTrace(e));
	}

	void close() {
		file.flush();
	}
}
