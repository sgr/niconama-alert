// -*- coding: utf-8-unix -*-
package nico.ui;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;

public class SearchResultPanel extends JPanel implements Scrollable {
    private int _lastWidth = -1;
    private SearchResultPanelLayout _layout;

    public SearchResultPanel(int hgap, int vgap) {
	_layout = new SearchResultPanelLayout(hgap, vgap);
	addComponentListener(new ComponentAdapter() {
		public void componentResized(ComponentEvent e) {
		    int newWidth = getWidth();
		    if (newWidth != _lastWidth) {
			_lastWidth = newWidth;
			_layout.setWidth(newWidth, SearchResultPanel.this);
		    }
		}
	    });
	setLayout(_layout);
	_lastWidth = getWidth();
    }
    // @Override
    // public void validate() {
    // 	JComponent parentComponent = (JComponent)SwingUtilities.getAncestorOfClass(JComponent.class, this);
    
    // 	if (parentComponent != null) {
    // 	    parentComponent.validate();
    // 	    parentComponent.repaint();
    // 	} else {
    // 	    super.validate();
    // 	}
    // }

    @Override
    public Component add(Component c) {
	super.add(c);
	validate();
	return c;
    }

    @Override
    public Component add(Component c, int index) {
	super.add(c, index);
	validate();
	return c;
    }

    @Override
    public void remove(Component c) {
	super.remove(c);
	validate();
    }

    @Override
    public void remove(int idx) {
	super.remove(idx);
	validate();
    }

    @Override
    public void removeAll() {
	super.removeAll();
	validate();
    }

    // Scrollable ここから
    public Dimension getPreferredScrollableViewportSize() {
	return getPreferredSize();
    }

    public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
	if (direction > 0) { // Scroll down
	    return 50;
	} else { // Scroll up
	    return 50;
	}
    }

    public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
	return getScrollableUnitIncrement(visibleRect, orientation, direction);
    }

    public boolean getScrollableTracksViewportWidth() {
	return true;
    }

    public boolean getScrollableTracksViewportHeight() {
	return false;
    }
    // Scrollable ここまで
}
