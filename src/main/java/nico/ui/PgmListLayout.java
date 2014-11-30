// -*- coding: utf-8-unix -*-
package nico.ui;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.LayoutManager2;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.text.MessageFormat;

public class PgmListLayout implements LayoutManager2 {
    private static final Logger log = Logger.getLogger(PgmListLayout.class.getCanonicalName());
    private static final int SEPARATOR_THICKNESS = 2;

    private int _width = 0;
    private Dimension _minimumLayoutSize = null;
    private Dimension _preferredLayoutSize = null;

    private PgmPanelComparator _descComparator = new PgmPanelComparator();

    public void setWidth(int width, Container target) {
	if (width > 0 && width != _width) {
	    _width = width;
	    updateSize(target);
	}
    }

    // LayoutManager ここから
    @Deprecated
    public void addLayoutComponent(String name, Component comp) {}

    public void removeLayoutComponent(Component comp) {}

    public Dimension minimumLayoutSize(Container target) {
	if (_minimumLayoutSize == null) {
	    int w = 0;
	    int h = 0;
	    for (Component c : target.getComponents()) {
		w += c.getMinimumSize().width;
		h += c.getMinimumSize().height + SEPARATOR_THICKNESS;
	    }
	    _minimumLayoutSize = new Dimension(w, h);
	}
	return _minimumLayoutSize;
    }

    public Dimension preferredLayoutSize(Container target) {
	if (_preferredLayoutSize == null) {
	    updateSize(target);
	}
	return _preferredLayoutSize;
    }

    public void layoutContainer(Container target) {
	Insets insets = target.getInsets();
	int width = (_width > 0 ? _width : target.getWidth()) - insets.left - insets.right;
	int height = target.getHeight() - insets.top - insets.bottom;
	int x = insets.left;
	int y = insets.top;

	PgmPanel[] ps = Arrays.copyOf(target.getComponents(), target.getComponentCount(), PgmPanel[].class);
	_descComparator.setOriginDate(System.currentTimeMillis());
	Arrays.sort(ps, _descComparator);
	for (PgmPanel p : ps) {
	    p.setWidth(width);
	    int h = p.getPreferredSize().height;
	    p.setBounds(x, y, width, h);
	    y += h + SEPARATOR_THICKNESS;
	}
    }
    // LayoutManager ここまで

    // LayoutManager2 ここから
    public void addLayoutComponent(Component comp, Object constraints) {}

    public Dimension maximumLayoutSize(Container target) {
	return preferredLayoutSize(target);
    }

    public float getLayoutAlignmentX(Container target) {
	return 0.5F;
    }

    public float getLayoutAlignmentY(Container target) {
	return 0.5F;
    }

    public void invalidateLayout(Container target) {
	//_width = -1;
	_preferredLayoutSize = null;
    }
    // LayoutManager2 ここまで

    private void updateSize(Container target) {
	Dimension sz = null;
	if (_width > 0) {
	    sz = calcSizeWithRestriction(_width, target);
	}
	if (sz == null) {
	    Insets insets = target.getInsets();
	    int width = target.getWidth() - insets.left - insets.right;
	    if (width > 0) {
		sz = calcSizeWithRestriction(width, target);
	    }
	}
	_preferredLayoutSize = sz != null ? sz : calcSizeNoRestriction(target);
    }

    private Dimension calcSizeWithRestriction(int width, Container target) {
	int h = 0;
	for (Component c : target.getComponents()) {
	    ((PgmPanel)c).setWidth(width);
	    h += c.getPreferredSize().height + SEPARATOR_THICKNESS;
	}
	return new Dimension(width, h);
    }

    private Dimension calcSizeNoRestriction(Container target) {
	int w = 0;
	int h = 0;
	for (Component c : target.getComponents()) {
	    w += c.getPreferredSize().width;
	    h += c.getPreferredSize().height + SEPARATOR_THICKNESS;
	}
	return new Dimension(w, h);
    }
}
