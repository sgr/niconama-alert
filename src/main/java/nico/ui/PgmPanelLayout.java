// -*- coding: utf-8-unix -*-
package nico.ui;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.LayoutManager2;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.text.MessageFormat;

public class PgmPanelLayout implements LayoutManager2 {
    public static enum Slot { TITLE, ICON, DESC, COMM, TIME, ONLY, TYPE }
    private static final Logger log = Logger.getLogger(PgmPanelLayout.class.getCanonicalName());

    // レイアウト用定数
    public static int PAD = 5; // コンポーネント間隔
    public static Dimension ICON_SIZE = new Dimension(64, 64);
    public static Dimension MINI_ICON_SIZE = new Dimension(10, 10);
    public static int MINI_PAD = 3; // ミニアイコンの間隔
    public static int MIN_LEFT_WIDTH = 64; // アイコン幅と同じにしている
    private static int TIME_HEIGHT = PgmPanel.FONT_TIME.getSize();
    private static int MIN_COMM_HEIGHT = PgmPanel.FONT_COMM.getSize();
    private static int STD_UPPER_HEIGHT = TIME_HEIGHT + PAD + ICON_SIZE.height;

    public static int MIN_WIDTH = PAD + MIN_LEFT_WIDTH + PAD + ICON_SIZE.width + PAD;
    public static int MIN_HEIGHT = PAD + TIME_HEIGHT + PAD + ICON_SIZE.height + PAD + MIN_COMM_HEIGHT + PAD;
    public static Dimension MIN_SIZE = new Dimension(MIN_WIDTH, MIN_HEIGHT);

    private int _width = 0;
    private int _height = 0;
    private Dimension _preferredLayoutSize = null;
    private Boolean _valid = false;

    private Component _title = null;
    private Component _icon = null;
    private Component _desc = null;
    private Component _comm = null;
    private Component _time = null;
    private Component _only = null;
    private Component _type = null;

    public void setWidth(int width) {
	if (_width != width) {
	    log.log(Level.FINEST, MessageFormat.format("setWidth {0} -> {1}", _width, width));
	    _width = width;
	    updateSize();
	    _valid = false;
	}
    }

    public void setHeight(int height) {
	if (_height != height) {
	    log.log(Level.FINEST, MessageFormat.format("setHeight {0} -> {1}", _height, height));
	    _height = height;
	    updateSize();
	    _valid = false;
	}
    }

    public void needLayout() {
	_valid = false;
    }

    // LayoutManager ここから
    @Deprecated
    public void addLayoutComponent(String name, Component comp) {}

    public void removeLayoutComponent(Component comp) {
	if (comp.equals(_title)) {
	    _title = null;
	} else if (comp.equals(_icon)) {
	    _icon = null;
	} else if (comp.equals(_desc)) {
	    _desc = null;
	} else if (comp.equals(_comm)) {
	    _comm = null;
	} else if (comp.equals(_time)) {
	    _time = null;
	} else if (comp.equals(_only)) {
	    _only = null;
	} else if (comp.equals(_type)) {
	    _type = null;
	} else {
	    log.log(Level.WARNING, MessageFormat.format("couldn't remove {0}", comp.toString()));
	}
    }

    public Dimension minimumLayoutSize(Container target) {
	return MIN_SIZE;
    }

    public Dimension preferredLayoutSize(Container target) {
	//log.log(Level.INFO, "preferredLayoutSize");
	if (_preferredLayoutSize == null || !_valid || _width == 0) {
	    updateSize(target);
	}
	log.log(Level.FINEST, MessageFormat.format("preferredLayoutSize ({0})", _preferredLayoutSize));
	return _preferredLayoutSize;
    }

    public void layoutContainer(Container target) {
	//log.log(Level.INFO, "layoutContainer");
	if (!_valid || _width == 0) {
	    // レイアウト先コンテナのサイズを確認する。
	    Insets insets = target.getInsets();
	    int width = (_width >= MIN_WIDTH ? _width : target.getWidth())
		- insets.left - insets.right - PAD - PAD;
	    int height = (_height >= MIN_HEIGHT ? _height : target.getHeight())
		- insets.top - insets.bottom - PAD - PAD;
	    int origin_x = insets.left + PAD;
	    int origin_y = insets.top + PAD;

	    if (width > 0) {
		// 時間、種別、コミュ限、アイコンラベルのレイアウト（これらはコンテナサイズだけで決められる）
		int leftWidth = width - PAD - ICON_SIZE.width;
		_type.setBounds(origin_x + leftWidth + PAD, origin_y,
				MINI_ICON_SIZE.width, MINI_ICON_SIZE.height);
		int time_w = 0;
		if (_only != null) {
		    _only.setBounds(origin_x + leftWidth + PAD + MINI_ICON_SIZE.width + MINI_PAD, origin_y,
				    MINI_ICON_SIZE.width, MINI_ICON_SIZE.height);
		    time_w = ICON_SIZE.width - MINI_ICON_SIZE.width - MINI_PAD - MINI_ICON_SIZE.width;
		} else {
		    time_w = ICON_SIZE.width - MINI_ICON_SIZE.width - MINI_PAD;
		}
		_time.setBounds(origin_x + width - time_w, origin_y, time_w, TIME_HEIGHT);
		_icon.setBounds(origin_x + leftWidth + PAD, origin_y + TIME_HEIGHT + PAD,
				ICON_SIZE.width, ICON_SIZE.height);

		// 一番下、コミュニティラベルからレイアウト
		_comm.setSize(width, 0);
		int rest_h = height - (TIME_HEIGHT + PAD + ICON_SIZE.height + PAD + _comm.getPreferredSize().height);
		int comm_h = _comm.getPreferredSize().height + (rest_h < 0 ? rest_h : 0);
		_comm.setBounds(origin_x, PAD + height - comm_h, width, comm_h);

		rest_h -= PAD;
		int leftHeight = TIME_HEIGHT + PAD + ICON_SIZE.height + PAD + (rest_h > 0 ? rest_h : 0);

		// パネル左側、タイトルと詳細ラベルのレイアウト
		_title.setSize(leftWidth, 0);
		int left_rest_h = leftHeight - _title.getPreferredSize().height;
		int title_h = left_rest_h > 0 ? _title.getPreferredSize().height : leftHeight;
		_title.setBounds(origin_x, origin_y, leftWidth, title_h);

		left_rest_h -= PAD;
		if (left_rest_h > 0) {
		    _desc.setSize(leftWidth, 0);
		    int desc_h = _desc.getPreferredSize().height;
		    _desc.setBounds(origin_x, origin_y + title_h,
				    leftWidth, desc_h > left_rest_h ? left_rest_h : desc_h);
		}
	    }
	    _valid = true;
	}
    }
    // LayoutManager ここまで

    // LayoutManager2 ここから
    public void addLayoutComponent(Component comp, Object constraints) {
	if (constraints instanceof Slot) {
	    switch ((Slot)constraints) {
	    case TITLE:
		_title = comp;
		break;
	    case ICON:
		_icon = comp;
		break;
	    case DESC:
		_desc = comp;
		break;
	    case COMM:
		_comm = comp;
		break;
	    case TIME:
		_time = comp;
		break;
	    case ONLY:
		_only = comp;
		break;
	    case TYPE:
		_type = comp;
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

    private void updateSize(Container target) {
	Dimension sz = null;
	if (_width >= MIN_WIDTH && _height >= MIN_HEIGHT) {
	    sz = new Dimension(_width, _height);
	} else {
	    Insets insets = target.getInsets();
	    int width = (_width >= MIN_WIDTH ? _width : target.getWidth())
		- insets.left - insets.right - PAD - PAD;
	    if (width > 0) {
		sz = calcSizeWithRestriction(width);
	    }
	}

	if (sz != null) {
	    _preferredLayoutSize = sz;
	} else {
	    updateSize();
	}
    }

    private void updateSize() {
	Dimension sz = null;
	if (_width >= MIN_WIDTH && _height >= MIN_HEIGHT) {
	    sz = new Dimension(_width, _height);
	} else if (_width >= MIN_WIDTH && _height < MIN_HEIGHT) {
	    sz = calcSizeWithRestriction(_width);
	} else if (_width < MIN_WIDTH && _height >= MIN_HEIGHT) {
	    // 高さがMIN_HEIGHTより大きくてもそれにあわせることはしない。
	    // 高さに合わせたレイアウトは試行を要することによりコストが高いため。
	    sz = calcSizeNoRestriction();
	} else { // _width < MIN_WIDTH && _height < MIN_HEIGHT
	    sz = calcSizeNoRestriction();
	}

	if (sz == null) {
	    sz = calcSizeNoRestriction();
	}
	_preferredLayoutSize = new Dimension(Math.max(sz.width, MIN_WIDTH), Math.max(sz.height, MIN_HEIGHT));
    }

    private Dimension calcSizeWithRestriction(int width) {
	int w = width - PAD - PAD - ICON_SIZE.width - PAD;
	if (w > 0) {
	    _title.setBounds(0, 0, w, 0);
	    _desc.setBounds(0, 0, w, 0);
	    _comm.setBounds(0, 0, width - PAD - PAD, 0);
	    int h = Math.max(_title.getPreferredSize().height + PAD + _desc.getPreferredSize().height,
			     STD_UPPER_HEIGHT);
	    return new Dimension(PAD + w + PAD + ICON_SIZE.width + PAD,
				 PAD + h + PAD + _comm.getPreferredSize().height + PAD);
	} else {
	    return null;
	}
    }

    private Dimension calcSizeNoRestriction() {
	_title.setBounds(0, 0, 0, 0);
	_desc.setBounds(0, 0, 0, 0);
	_comm.setBounds(0, 0, 0, 0);
	int stdUpperWidth = _comm.getPreferredSize().width - PAD - ICON_SIZE.width;
	int width = 0;
	int height = 0;
	if (stdUpperWidth > ICON_SIZE.width) { // アイコン並みの幅はあってもよいだろうということ
	    // stdUpperWidthを基準にレイアウトする
	    _title.setBounds(0, 0, stdUpperWidth, 0);
	    _desc.setBounds(0, 0, stdUpperWidth, 0);
	    width = stdUpperWidth;
	    height = _title.getPreferredSize().height + PAD + _desc.getPreferredSize().height;
	} else {
	    // STD_UPPER_HEIGHTを基準にレイアウトする
	    width = Math.max(_title.getPreferredSize().width, _desc.getPreferredSize().width);
	    if (width > 0) {
		for (int i = 1; ;i++) { // 粗く詰める
		    int w = (int)(width / i);
		    _title.setBounds(0, 0, w, 0);
		    _desc.setBounds(0, 0, w, 0);
		    int h = _title.getPreferredSize().height + PAD + _desc.getPreferredSize().height;
		    if (h > STD_UPPER_HEIGHT) {
			break;
		    } else {
			width = w;
		    }
		}
		for (int w = width; w > 0; w--) { // 細かく詰める
		    _title.setBounds(0, 0, w, 0);
		    _desc.setBounds(0, 0, w, 0);
		    int h = _title.getPreferredSize().height + PAD + _desc.getPreferredSize().height;
		    if (h > STD_UPPER_HEIGHT) {
			break;
		    } else {
			width = w;
		    }
		}
	    }
	    height = STD_UPPER_HEIGHT;
	}
	return new Dimension(PAD + width + PAD + ICON_SIZE.width + PAD,
			     PAD + height + PAD + _comm.getPreferredSize().height + PAD);
    }
}
