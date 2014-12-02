// -*- coding: utf-8-unix -*-
package nico.ui;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import javax.swing.JPanel;
import javax.swing.Scrollable;

public class PgmList extends JPanel implements Scrollable {
    private int _lastWidth = -1;
    private PgmListLayout _layout = new PgmListLayout();;

    public PgmList() {
	addComponentListener(new ComponentAdapter() {
		public void componentResized(ComponentEvent e) {
		    int newWidth = getWidth();
		    if (newWidth != _lastWidth) {
			_lastWidth = newWidth;
			_layout.setWidth(newWidth, PgmList.this);
		    }
		}
	    });
	setLayout(_layout);
	_lastWidth = getWidth();
	setBackground(Color.lightGray);
    }

    // Scrollable ここから
    public Dimension getPreferredScrollableViewportSize() {
	return getPreferredSize();
    }

    public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
	if (direction > 0) { // Scroll down
	    return 8;
	} else { // Scroll up
	    return 10;
	}
    }

    public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
	//return getScrollableUnitIncrement(visibleRect, orientation, direction);
	if (direction > 0) { // Scroll down
	    return 45;
	} else { // Scroll up
	    return 80;
	}
    }

    public boolean getScrollableTracksViewportWidth() {
	return true;
    }

    public boolean getScrollableTracksViewportHeight() {
	return false;
    }
    // Scrollable ここまで

    // キーボード操作
    @Override
    protected void processComponentKeyEvent(KeyEvent e) {
	Component parent = getParent();
	switch (e.getKeyCode()) {
	case KeyEvent.VK_LEFT:
	case KeyEvent.VK_RIGHT:
	case KeyEvent.VK_KP_LEFT:
	case KeyEvent.VK_KP_RIGHT:
	    //System.err.println("<- or ->");
	    parent.dispatchEvent(e);
	    break;
	default:
	    System.err.println("Other");
	    // NOP
	}
    }

}
