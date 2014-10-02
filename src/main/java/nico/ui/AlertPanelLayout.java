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
import java.util.logging.Level;
import java.util.logging.Logger;
import java.text.MessageFormat;

public class AlertPanelLayout implements LayoutManager2 {
    public static enum Slot { MSG, ICON }
    private static final Logger log = Logger.getLogger(AlertPanelLayout.class.getCanonicalName());

    // レイアウト用定数
    public static int PAD = 5; // コンポーネント間隔
    public static int INDENT = 50; // アイコン行のインデント
    public static Dimension MIN_MSG_SIZE = new Dimension(69, 20);
    public static Dimension ICON_SIZE = new Dimension(40, 40);
    private static Dimension MIN_SIZE = new Dimension(PAD + MIN_MSG_SIZE.width + PAD,
						      PAD + MIN_MSG_SIZE.height + PAD + ICON_SIZE.height + PAD);

    private int _targetWidth = 0;
    private int _targetHeight = 0;
    private Dimension _preferredLayoutSize = null;
    private Component _msg = null;
    private ArrayList<Component> _icons = new ArrayList<Component>();

    // LayoutManager ここから
    @Deprecated
    public void addLayoutComponent(String name, Component comp) {}

    public void removeLayoutComponent(Component comp) {
	if (comp.equals(_msg)) {
	    _msg = null;
	} else {
	    if (!_icons.remove(comp)) {
		log.log(Level.WARNING, MessageFormat.format("couldn't remove {0}", comp.toString()));
	    }
	}
    }

    public Dimension minimumLayoutSize(Container target) {
	return MIN_SIZE;
    }

    public Dimension preferredLayoutSize(Container target) {
	Insets insets = target.getInsets();
	int w = target.getWidth() - insets.left - insets.right;
	int h = target.getHeight() - insets.top - insets.bottom;
	if (_targetWidth != w || _targetHeight != h || _preferredLayoutSize == null) {
	    _targetWidth = w;
	    _targetHeight = h;
	    _msg.setSize(w - PAD - PAD, h - PAD - PAD - ICON_SIZE.height);
	    Dimension d = _msg.getPreferredSize();
	    _preferredLayoutSize = new Dimension(PAD + d.width + PAD,
						 PAD + d.height + PAD + ICON_SIZE.height + PAD);
	}
	return _preferredLayoutSize;
    }

    public void layoutContainer(Container target) {
	// レイアウト先コンテナのサイズを確認する。
	Insets insets = target.getInsets();
	int width = target.getWidth() - insets.left - insets.right - PAD - PAD;
	int height = target.getHeight() - insets.top - insets.bottom - PAD - PAD;
	int origin_x = insets.left + PAD;
	int origin_y = insets.top + PAD;

	_msg.setBounds(origin_x, origin_y, width, height - ICON_SIZE.height);

	int x = origin_x + width - ICON_SIZE.width;
	int y = origin_y + height - ICON_SIZE.height;
	int min_x = origin_x + INDENT;
	for (Component c : _icons) {
	    if (min_x <= x) {
		c.setBounds(x, y, ICON_SIZE.width, ICON_SIZE.height);
		c.setVisible(true);
		x -= ICON_SIZE.width + PAD;
	    } else {
		c.setVisible(false);
	    }
	}
    }
    // LayoutManager ここまで

    // LayoutManager2 ここから
    public void addLayoutComponent(Component comp, Object constraints) {
	if (constraints instanceof Slot) {
	    switch ((Slot)constraints) {
	    case MSG:
		_msg = comp;
		break;
	    case ICON:
		_icons.add(comp);
		break;
	    default:
		throw new IllegalArgumentException(String.format("Unknown constraints: %s",
								 constraints.toString()));
	    }
	} else {
	    throw new IllegalArgumentException(String.format("Unsupported constraints: %s",
							     constraints.getClass().getCanonicalName()));
	}
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
    // LayoutManager2 ここまで
}
