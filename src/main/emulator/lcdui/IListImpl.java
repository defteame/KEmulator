/*
Copyright (c) 2025 Arman Jussupgaliyev
*/
package emulator.lcdui;

import javax.microedition.lcdui.Choice;
import javax.microedition.lcdui.Graphics;

public interface IListImpl extends Choice, IScreenImpl {

	void drawScrollBar(Graphics graphics);

	/** Navigation keys for a list the emulator draws itself. Returns true if the key was used. */
	boolean keyPressed(int key, boolean repeat);
}
