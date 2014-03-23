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

public class SearchResultPanelLayout implements LayoutManager2 {
    private static final Logger log = Logger.getLogger(SearchResultPanelLayout.class.getCanonicalName());

    private int _hgap = 0;
    private int _vgap = 0;
    private int _width = 0;
    private Dimension _preferredLayoutSize = null;

    private PgmPanelComparator _descComparator = new PgmPanelComparator();

    public SearchResultPanelLayout(int hgap, int vgap) {
	_hgap = hgap;
	_vgap = vgap;
    }

    public int getHgap() {
	return _hgap;
    }

    public void setHgap(int hgap) {
	_hgap = hgap;
    }

    public int getVgap() {
	return _vgap;
    }

    public void setVgap(int vgap) {
	_vgap = vgap;
    }

    public void setWidth(int width, Container target) {
	if (width > 0 && width != _width) {
	    _width = width;
	    updateSize(target);
	}
    }

    // LayoutManager ここから
    @Deprecated
    public void addLayoutComponent(String name, Component comp) {}

    public void removeLayoutComponent(Component comp) {
	_preferredLayoutSize = null;
    }

    public Dimension minimumLayoutSize(Container target) {
	int maxWidth = 0;
	int accHeight = 0;
	for (Component c : target.getComponents()) {
	    Dimension d = c.getPreferredSize();
	    maxWidth = d.width > maxWidth ? d.width : maxWidth;
	    accHeight += _vgap + d.height;
	}
	return new Dimension(_hgap + maxWidth + _hgap, accHeight + _vgap);
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
	int x = insets.left;
	int y = insets.top;

	int accWidth = 0;
	int maxHeight = 0;
	PgmPanel[] ps = Arrays.copyOf(target.getComponents(), target.getComponentCount(), PgmPanel[].class);
	_descComparator.setOriginDate(System.currentTimeMillis());
	Arrays.sort(ps, _descComparator);
	for (PgmPanel p : ps) {
	    Dimension d = p.getPreferredSize();
	    if (width < (accWidth + _hgap + d.width)) {
		y += _vgap + maxHeight;
		accWidth = 0;
		maxHeight = 0;
	    }
	    p.setBounds(x + accWidth + _hgap, y + _vgap, d.width, d.height);
	    accWidth += _hgap + d.width;
	    maxHeight = d.height > maxHeight ? d.height : maxHeight;
	}
    }
    // LayoutManager ここまで

    // LayoutManager2 ここから
    public void addLayoutComponent(Component comp, Object constraints) {
	_preferredLayoutSize = null;
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

    public void invalidateLayout(Container target) {
	_width = -1;
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
	_preferredLayoutSize = sz != null ? sz : minimumLayoutSize(target);
    }

    private Dimension calcSizeWithRestriction(int width, Container target) {
	int maxWidth = 0;
	int accWidth = 0;
	int maxHeight = 0;
	int accHeight = 0;
	for (Component c : target.getComponents()) {
	    Dimension d = c.getPreferredSize();
	    if (width < (accWidth + _hgap + d.width)) {
		maxWidth = accWidth > maxWidth ? accWidth : maxWidth;
		accHeight += _vgap + maxHeight;
		accWidth = 0;
		maxHeight = 0;
	    }
	    accWidth += _hgap + d.width;
	    maxHeight = d.height > maxHeight ? d.height : maxHeight;
	}
	accHeight += _vgap + maxHeight; // 最後の行の分
	return new Dimension(maxWidth + _hgap, accHeight + _vgap);
    }
}
