// -*- coding: utf-8-unix -*-
package nico.ui;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager2;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import javax.swing.JPanel;
import javax.swing.Scrollable;

public class WaterfallPanel extends JPanel implements Scrollable {
    private Dimension _size = new Dimension(0, 0);
    private WaterfallLayout _layout = null;

    public WaterfallPanel() {
	_layout = new WaterfallLayout();
	setLayout(_layout);
	validate();
    }

    public void setComponentWidth(int width) {
	_layout.setComponentWidth(width);
	doLayout();
        revalidate();
    }

    public void moveComponent(int fromIndex, int toIndex) {
	Component c = getComponent(fromIndex);
	remove(fromIndex);
	add(c, toIndex);
	doLayout();
	revalidate();
    }

    @Override
    public Component add(Component c) {
	super.add(c);
	revalidate();
	return c;
    }

    @Override
    public Component add(Component c, int index) {
	super.add(c, index);
	revalidate();
	return c;
    }

    @Override
    public void remove(Component c) {
	super.remove(c);
	revalidate();
    }

    @Override
    public void remove(int idx) {
	super.remove(idx);
	revalidate();
    }

    @Override
    public void removeAll() {
	super.removeAll();
	revalidate();
    }

    @Override
    protected void processComponentKeyEvent(KeyEvent e) {
	Component parent = getParent();
	switch (e.getKeyCode()) {
	case KeyEvent.VK_LEFT:
	case KeyEvent.VK_RIGHT:
	case KeyEvent.VK_KP_LEFT:
	case KeyEvent.VK_KP_RIGHT:
	    parent.dispatchEvent(e);
	    break;
	default:
	    System.err.println("Other");
	    // NOP
	}
    }

    // Scrollable ここから
    public Dimension getPreferredScrollableViewportSize() {
	return getPreferredSize();
    }

    public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
	return _layout.getComponentWidth();
    }

    public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
	return getScrollableUnitIncrement(visibleRect, orientation, direction);
    }

    public boolean getScrollableTracksViewportWidth() {
	return false;
    }

    public boolean getScrollableTracksViewportHeight() {
	return true;
    }
    // Scrollable ここまで

    protected static class WaterfallLayout implements LayoutManager2 {
	private int _componentWidth = 250;

	public WaterfallLayout() {
	}

	public void setComponentWidth(int width) {
	    _componentWidth = width;
	}

	public int getComponentWidth() {
	    return _componentWidth;
	}

	// LayoutManager
	@Deprecated
	public void addLayoutComponent(String name, Component comp) {}

	public void removeLayoutComponent(Component comp) {}

	public Dimension preferredLayoutSize(Container parent) {
	    int w = 0;
	    int h = 0;
	    for (Component c : parent.getComponents()) {
		Dimension d = c.getPreferredSize();
		w += _componentWidth;
		h = Math.max(h, d.height);
	    }
	    return new Dimension(w, h);
	}

	public Dimension minimumLayoutSize(Container parent) {
	    int w = 0;
	    int h = 0;
	    for (Component c : parent.getComponents()) {
		Dimension d = c.getMinimumSize();
		w += _componentWidth;
		h = Math.max(h, d.height);
	    }
	    return new Dimension(w, h);
	}

	public void layoutContainer(Container parent) {
	    Dimension containerSize = parent.getSize();
	    Insets insets = parent.getInsets();
	    int width = containerSize.width - insets.left - insets.right;
	    int height = containerSize.height - insets.top - insets.bottom;

	    int x = 0;
	    for (Component c : parent.getComponents()) {
		c.setBounds(x, 0, _componentWidth, height);
		x += _componentWidth;
	    }
	}

	// LayoutManager2
	public void addLayoutComponent(Component comp, Object constraints) {
	}

	public Dimension maximumLayoutSize(Container target) {
	    return preferredLayoutSize(target);
	}

	public float getLayoutAlignmentX(Container target) {
	    return 0.5F;
	}

	public float getLayoutAlignmentY(Container target) {
	    return 0.5F;
	}

	public void invalidateLayout(Container target) {}
    }
}
