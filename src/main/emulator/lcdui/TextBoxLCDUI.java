package emulator.lcdui;

import emulator.Emulator;
import emulator.EventQueue;

import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.TextBox;
import javax.microedition.lcdui.TextField;

/**
 * A TextBox that the emulator draws itself, for frontends without SWT
 * widgets (headless, bridge). It keeps the text and draws it; there is no
 * key input method: a headless script sets the text with its "text" command.
 */
public class TextBoxLCDUI implements ITextBoxImpl {
	private final TextBox textBox;
	private final StringBuffer text = new StringBuffer();
	private int maxSize;
	private int constraints;
	private int caret;

	public TextBoxLCDUI(TextBox textBox, String text, int maxSize, int constraints) {
		if (maxSize <= 0) {
			throw new IllegalArgumentException("maxSize must be positive");
		}
		this.textBox = textBox;
		this.maxSize = maxSize;
		this.constraints = constraints;
		setString(text);
	}

	public int getCaretPosition() {
		return caret;
	}

	public String getString() {
		return text.toString();
	}

	public void setString(String newText) {
		if (newText != null && newText.length() > maxSize) {
			throw new IllegalArgumentException("text is longer than maxSize");
		}
		text.setLength(0);
		if (newText != null) {
			text.append(newText);
		}
		caret = text.length();
		changed();
	}

	public int getChars(char[] charData) {
		if (charData.length < text.length()) {
			throw new ArrayIndexOutOfBoundsException();
		}
		text.getChars(0, text.length(), charData, 0);
		return text.length();
	}

	public void setChars(char[] charData, int offset, int length) {
		setString(charData == null ? null : new String(charData, offset, length));
	}

	public void insert(String s, int position) {
		if (s == null) {
			throw new NullPointerException();
		}
		if (text.length() + s.length() > maxSize) {
			throw new IllegalArgumentException("text would be longer than maxSize");
		}
		position = Math.max(0, Math.min(position, text.length()));
		text.insert(position, s);
		caret = position + s.length();
		changed();
	}

	public void delete(int offset, int length) {
		if (offset < 0 || length < 0 || offset + length > text.length()) {
			throw new StringIndexOutOfBoundsException();
		}
		text.delete(offset, offset + length);
		caret = Math.min(caret, text.length());
		changed();
	}

	public int getMaxSize() {
		return maxSize;
	}

	public int setMaxSize(int newMaxSize) {
		if (newMaxSize <= 0) {
			throw new IllegalArgumentException();
		}
		maxSize = newMaxSize;
		if (text.length() > maxSize) {
			text.setLength(maxSize);
			caret = Math.min(caret, maxSize);
		}
		changed();
		return maxSize;
	}

	public int size() {
		return text.length();
	}

	public void setConstraints(int newConstraints) {
		constraints = newConstraints;
		changed();
	}

	public int getConstraints() {
		return constraints;
	}

	public void setInitialInputMode(String inputMode) {
	}

	public void defocus() {
	}

	public void focusCaret() {
	}

	private void changed() {
		if (textBox != null && textBox.isShown()) {
			Emulator.getEventQueue().queue(EventQueue.EVENT_SCREEN);
		}
	}

	public void paint(Graphics g) {
		Font font = Font.getDefaultFont();
		int top = font.getHeight() + 4;
		int w = getWidth();
		int h = getHeight();
		String shown = text.toString();
		if ((constraints & TextField.PASSWORD) != 0) {
			StringBuffer stars = new StringBuffer();
			for (int i = 0; i < shown.length(); i++) {
				stars.append('*');
			}
			shown = stars.toString();
		}
		g.setColor(LCDUIUtils.highlightedBorderColor);
		g.drawRect(1, top + 1, w - 3, h - 3);
		g.setColor(LCDUIUtils.foregroundColor);
		g.setFont(font);
		String[] lines = TextUtils.textArr(shown, font, w - 8, w - 8);
		int y = top + 3;
		for (int i = 0; i < lines.length && y < top + h; i++) {
			g.drawString(lines[i], 4, y, 0);
			y += font.getHeight();
		}
	}

	public void layout() {
	}

	public boolean isSWT() {
		return false;
	}

	public void swtShown() {
	}

	public void swtHidden() {
	}

	public void swtUpdateSizes() {
	}

	public Object getSwtContent() {
		return null;
	}

	// TextBox.getWidth/getHeight ask the implementation: the area of a Screen
	public int getWidth() {
		return Emulator.getEmulator().getScreen().getWidth() - 4;
	}

	public int getHeight() {
		return Emulator.getEmulator().getScreen().getHeight() - (Font.getDefaultFont().getHeight() + 4);
	}
}
