package emulator.lcdui;

import emulator.Emulator;
import emulator.EventQueue;
import emulator.KeyMapping;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Choice;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;
import javax.microedition.lcdui.List;

/**
 * A List that the emulator draws itself, as it draws a Form, for frontends
 * without SWT widgets (headless, bridge). Selection works as in ListSWT: in
 * an implicit or exclusive list the highlighted item is the selected one, in
 * a multiple list the fire key ticks the highlighted item. Up and down move
 * the highlight; fire on an implicit list sends its select command.
 */
public class ListLCDUI implements IListImpl {
	private final List list;
	private final int type;
	private final ChoiceImpl choice;
	/** The highlighted item (multiple lists; the others highlight the selected item). */
	private int focus;
	/** First visible pixel row of the items. */
	private int scroll;

	public ListLCDUI(List list, String title, int type, String[] text, Image[] img) {
		if (type != Choice.IMPLICIT && type != Choice.EXCLUSIVE && type != Choice.MULTIPLE) {
			throw new IllegalArgumentException();
		}
		this.list = list;
		this.type = type;
		choice = new ChoiceImpl(type == Choice.MULTIPLE);
		choice.check(text, img);
		for (int i = 0; i < text.length; i++) {
			choice.append(text[i], img != null ? img[i] : null);
		}
	}

	public int append(String text, Image img) {
		int i = choice.append(text, img);
		changed();
		return i;
	}

	public void insert(int position, String text, Image img) {
		choice.insert(position, text, img);
		if (type == Choice.MULTIPLE && position <= focus && size() > 1) {
			focus++;
		}
		changed();
	}

	public void set(int position, String text, Image img) {
		choice.set(position, text, img);
		changed();
	}

	public void delete(int position) {
		if (position < 0 || position >= size()) {
			throw new IndexOutOfBoundsException();
		}
		choice.delete(position);
		if (type == Choice.MULTIPLE && focus >= size()) {
			focus = Math.max(0, size() - 1);
		}
		changed();
	}

	public void deleteAll() {
		choice.deleteAll();
		focus = 0;
		scroll = 0;
		changed();
	}

	public int getFitPolicy() {
		return choice.getFitPolicy();
	}

	public Font getFont(int position) {
		return choice.getFont(position);
	}

	public Image getImage(int position) {
		return choice.getImage(position);
	}

	public String getString(int position) {
		return choice.getString(position);
	}

	public int getSelectedFlags(boolean[] selectedArray) {
		return choice.getSelectedFlags(selectedArray);
	}

	public int getSelectedIndex() {
		return choice.getSelectedIndex();
	}

	public boolean isSelected(int position) {
		return choice.isSelected(position);
	}

	public void setFitPolicy(int newFitPolicy) {
		choice.setFitPolicy(newFitPolicy);
		changed();
	}

	public void setFont(int n, Font font) {
		choice.setFont(n, font);
		changed();
	}

	public void setSelectedFlags(boolean[] selectedArray) {
		choice.setSelectedFlags(selectedArray);
		changed();
	}

	public void setSelectedIndex(int position, boolean select) {
		choice.setSelected(position, select);
		if (type == Choice.MULTIPLE) {
			focus = position;
		}
		changed();
	}

	public int size() {
		return choice.size();
	}

	/** The highlighted item, or -1 if the list is empty. */
	private int highlighted() {
		if (size() == 0) {
			return -1;
		}
		if (type == Choice.MULTIPLE) {
			return Math.min(focus, size() - 1);
		}
		int sel = choice.getSelectedIndex();
		return sel == -1 ? 0 : sel;
	}

	/** Up, down and fire. Returns true if the key was used. */
	public boolean keyPressed(int key, boolean repeat) {
		int n = size();
		if (n == 0) {
			return false;
		}
		int h = highlighted();
		if (key == KeyMapping.getArrowKeyFromDevice(Canvas.UP) || key == KeyMapping.getArrowKeyFromDevice(Canvas.DOWN)) {
			int next = key == KeyMapping.getArrowKeyFromDevice(Canvas.UP) ? h - 1 : h + 1;
			if (next < 0 || next >= n) {
				return true;
			}
			if (type == Choice.MULTIPLE) {
				focus = next;
			} else {
				choice.setSelected(next, true);
			}
			changed();
			return true;
		}
		if (key == KeyMapping.getArrowKeyFromDevice(Canvas.FIRE) && !repeat) {
			if (type == Choice.IMPLICIT) {
				choice.setSelected(h, true);
				Command select = list._getSelectCommand();
				if (select != null) {
					Emulator.getEventQueue().commandAction(select, list);
				}
			} else if (type == Choice.MULTIPLE) {
				choice.setSelected(h, !choice.isSelected(h));
				changed();
			} else {
				choice.setSelected(h, true);
				changed();
			}
			return true;
		}
		return false;
	}

	private void changed() {
		if (list.isShown()) {
			Emulator.getEventQueue().queue(EventQueue.EVENT_SCREEN);
		}
	}

	private static int rowHeight(Font font, Image img) {
		int h = font.getHeight() + 4;
		if (img != null) {
			h = Math.max(h, Math.min(img.getHeight(), 16) + 4);
		}
		return h;
	}

	public void paint(Graphics g) {
		int n = size();
		Font defaultFont = Font.getDefaultFont();
		int top = defaultFont.getHeight() + 4;
		int w = list.getWidth();
		int h = list.getHeight();
		int current = highlighted();

		int y = 0, currentY = 0, currentH = 0;
		for (int i = 0; i < n; i++) {
			int rh = rowHeight(choice.getFont(i), choice.getImage(i));
			if (i == current) {
				currentY = y;
				currentH = rh;
			}
			y += rh;
		}
		int total = y;
		if (current >= 0) {
			if (currentY < scroll) {
				scroll = currentY;
			} else if (currentY + currentH > scroll + h) {
				scroll = currentY + currentH - h;
			}
		}
		scroll = Math.max(0, Math.min(scroll, Math.max(0, total - h)));

		int cx = g.getClipX(), cy = g.getClipY(), cw = g.getClipWidth(), ch = g.getClipHeight();
		g.clipRect(0, top, w, h);
		y = top - scroll;
		for (int i = 0; i < n; i++) {
			Font font = choice.getFont(i);
			Image img = choice.getImage(i);
			int rh = rowHeight(font, img);
			if (y + rh > top && y < top + h) {
				if (i == current) {
					LCDUIUtils.drawSelectedItemBackground(g, 1, y, w - 2, rh - 1);
				}
				g.setColor(LCDUIUtils.foregroundColor);
				int x = 4;
				if (type != Choice.IMPLICIT) {
					LCDUIUtils.drawChoiceItem(g, x, y + (rh - 10) / 2, choice.isSelected(i), type);
					x += 14;
				}
				if (img != null) {
					g.drawRegion(img, 0, 0, Math.min(img.getWidth(), 16), Math.min(img.getHeight(), 16), 0,
							x, y + 2, 0);
					x += 20;
				}
				g.setFont(font);
				g.drawString(choice.getString(i), x, y + (rh - font.getHeight()) / 2, 0);
			}
			y += rh;
		}
		g.setClip(cx, cy, cw, ch);
		g.setFont(defaultFont);
	}

	public void drawScrollBar(Graphics g) {
		int top = Font.getDefaultFont().getHeight() + 4;
		LCDUIUtils.drawScrollbar(g, list.getWidth() + 1, top - 1, 2, list.getHeight() - 2, size(), highlighted());
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

	public int getWidth() {
		return list.getWidth();
	}

	public int getHeight() {
		return list.getHeight();
	}
}
