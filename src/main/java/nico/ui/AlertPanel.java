// -*- coding: utf-8-unix -*-
package nico.ui;
import java.awt.AWTEvent;
import java.awt.Color;
import java.awt.Container;
import java.awt.Font;
import java.awt.Image;
import java.awt.Window;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.WindowEvent;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;
import java.util.EmptyStackException;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

import com.github.sgr.slide.MultiLineLabel;
import nico.ui.AlertPanelLayout.Slot;

public class AlertPanel extends JPanel implements MouseListener {
    private static final Logger log = Logger.getLogger(AlertPanel.class.getCanonicalName());
    public static Font FONT_MSG = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
    public static Color FOREGROUND_DEFAULT = Color.BLACK;
    public static Color BACKGROUND_DEFAULT = Color.WHITE;

    private static Stack<AlertPanel> cache = new Stack<AlertPanel>();

    public static AlertPanel create() {
	synchronized(cache) {
	    AlertPanel p = null;
	    try {
		p = cache.pop();
		log.log(Level.FINE, MessageFormat.format("reused a AlertPanel from cache <- ({0})", cache.size()));
	    } catch (EmptyStackException e) {
		log.log(Level.FINE, MessageFormat.format("created a new AlertPanel ({0})", cache.size()));
		p = new AlertPanel();
	    } finally {
		return p;
	    }
	}
    }

    public static AlertPanel create(String msg, List<Image> icons) {
	AlertPanel p = AlertPanel.create();
	p.setAlertInfo(msg, icons);
	return p;
    }

    /*
     * AlertPanelを開放する。
     * 本クラスの利用者であるdesktop-alertがdisposeを呼ぶため、releaseでなくdisposeとして実装する。
     * 実際の廃棄は行わず、キャッシュに戻す。
     */
    public void dispose() {
	removeAll();
	add(_msgLabel, Slot.MSG);
	for (Image img : _imgs) {
	    img.flush();
	}
	_imgs.clear();
	invalidate();
	synchronized(cache) {
	    cache.push(this);
	    log.log(Level.FINE, MessageFormat.format("released a AlertPanel into cache -> ({0})", cache.size()));
	}
    }

    private MultiLineLabel _msgLabel = null;
    private ArrayList<Image> _imgs = new ArrayList<Image>();

    public void setAlertInfo(String msg, List<Image> icons) {
	removeAll();
	_msgLabel.setText(msg);
	add(_msgLabel, Slot.MSG);

	for (Image img : _imgs) {
	    img.flush();
	}
	_imgs.clear();
	for (Image img : icons) {
	    Image nimg = img.getScaledInstance(AlertPanelLayout.ICON_SIZE.width,
					       AlertPanelLayout.ICON_SIZE.height,
					       Image.SCALE_SMOOTH);
	    _imgs.add(nimg);
	    img.flush();
	    add(new IconLabel(new ImageIcon(nimg)), Slot.ICON);
	}
	invalidate();
    }

    private AlertPanel() {
	_msgLabel = new MultiLineLabel();
	_msgLabel.setFont(FONT_MSG);
	setLayout(new AlertPanelLayout());
	add(_msgLabel, Slot.MSG);
	setForegroundColor(FOREGROUND_DEFAULT);
	setBackgroundColor(BACKGROUND_DEFAULT);
	addMouseListener(this);
    }

    public void setForegroundColor(Color color) {
	Color fg = color != null ? color : FOREGROUND_DEFAULT;
	_msgLabel.setForeground(fg);
    }

    public void setBackgroundColor(Color color) {
	Color bg = color != null ? color : BACKGROUND_DEFAULT;
	setBackground(bg);
    }

    /*
     * AlertPanelを廃棄する。
     * 元々はdisposeだったがキャッシュ導入に伴い、外部から直接呼べなくした。
     */
    private void disposeActually() {
	removeMouseListener(this);
	removeAll();
	setLayout(null);
	for (Image img : _imgs) {
	    img.flush();
	}
	_imgs.clear();
    }

    private static class PartLabel extends JLabel {
	public void ignoreMouse(boolean b) {
	    if (b) {
		disableEvents(AWTEvent.MOUSE_EVENT_MASK |
			      AWTEvent.MOUSE_MOTION_EVENT_MASK |
			      AWTEvent.MOUSE_WHEEL_EVENT_MASK);
	    } else {
		enableEvents(AWTEvent.MOUSE_EVENT_MASK |
			     AWTEvent.MOUSE_MOTION_EVENT_MASK |
			     AWTEvent.MOUSE_WHEEL_EVENT_MASK);
	    }
	}
    }

    private static class IconLabel extends PartLabel {
	public IconLabel() {
	    setOpaque(false);
	    setHorizontalTextPosition(SwingConstants.CENTER);
	    setHorizontalAlignment(SwingConstants.CENTER);
	}

	public IconLabel(ImageIcon icon) {
	    this();
	    setIcon(icon);
	}
    }

    // MouseListener
    public void mouseClicked(MouseEvent e) {
	Container c = getParent();
	if ((c != null) && (c instanceof Window)) {
	    c.dispatchEvent(new WindowEvent((Window)c, WindowEvent.WINDOW_CLOSING));
	}
    }

    public void mouseEntered(MouseEvent e) {
    }

    public void mouseExited(MouseEvent e) {
    }

    public void mousePressed(MouseEvent e) {
    }

    public void mouseReleased(MouseEvent e) {
    }
}
