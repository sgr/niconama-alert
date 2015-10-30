// -*- coding: utf-8-unix -*-
package nico.ui;
import java.awt.AWTEvent;
import java.awt.Color;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Image;
import java.awt.Window;
import java.io.IOException;
import java.net.URI;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.border.Border;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.text.html.HTMLEditorKit;
import org.apache.commons.lang3.time.FastDateFormat;

import com.github.sgr.slide.Link;
import com.github.sgr.slide.LinkHandler;
import com.github.sgr.slide.LinkHandlers;
import com.github.sgr.slide.LinkLabel;
import com.github.sgr.slide.MultiLineLabel;

import nico.ui.PgmPanelLayout.Slot;

public class PgmPanel extends JPanel {
    public static Font FONT_TITLE = new Font(Font.SANS_SERIF, Font.BOLD, 13);
    public static Font FONT_DESC = new Font(Font.SANS_SERIF, Font.PLAIN, 10);
    public static Font FONT_COMM = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
    public static Font FONT_TIME = new Font(Font.SANS_SERIF, Font.PLAIN, 9);

    public static Color FOREGROUND_DEFAULT = Color.BLACK;
    public static Color BACKGROUND_DEFAULT = Color.WHITE;

    public static Cursor LINK_CURSOR = (new HTMLEditorKit()).getLinkCursor();
    public static Cursor DEFAULT_CURSOR = Cursor.getDefaultCursor();
    public static String LINE_SEPARATOR = System.getProperty("line.separator");

    public static ImageIcon MEMBER_ONLY_ICON = new ImageIcon(Thread.currentThread().getContextClassLoader().getResource("member_only.png"));
    public static ImageIcon COMMUNITY_ICON = new ImageIcon(Thread.currentThread().getContextClassLoader().getResource("community.png"));
    public static ImageIcon CHANNEL_ICON = new ImageIcon(Thread.currentThread().getContextClassLoader().getResource("channel.png"));
    public static ImageIcon OFFICIAL_ICON = new ImageIcon(Thread.currentThread().getContextClassLoader().getResource("official.png"));
    public static ImageIcon FALLBACK_ICON = new ImageIcon(Thread.currentThread().getContextClassLoader().getResource("noimage.png"));

    private static final Logger log = Logger.getLogger(PgmPanel.class.getCanonicalName());
    private static final FastDateFormat sdf = FastDateFormat.getInstance("d MMM", Locale.ENGLISH);
    private static final FastDateFormat odf = FastDateFormat.getInstance("MM/dd HH:mm:ss");

    public static PgmPanel create(String id, String title, String link, String description,
				  String owner_name, String comm_name, String comm_id, int type,
				  int member_only, long start_time, Image thumbnail) {
	PgmPanel p = new PgmPanel();
	p.setPgmInfo(id, title, link, description, owner_name, comm_name, comm_id, type, member_only, start_time, thumbnail);
	return p;
    }

    public void release() {
	_id = null;
	_fgColor = null;
	_bgColor = null;
	removeAll();
	setLayout(null);
	_layout = null;
	_titleLabel.dispose();
	_timeLabel = null;
	_descLabel.dispose();
	_descLabel = null;
	_commLabel.dispose();
	_commLabel = null;
	_iconLabel.setIcon(null);
	if (_thumbnail != null) {
	    _thumbnail.flush();
	    _thumbnail = null;
	}
	_iconLabel = null;
	_timeLabel = null;
	if (_onlyLabel.getIcon() != null) {
	    _onlyLabel.setIcon(null);
	}
	_onlyLabel = null;
	if (_typeLabel.getIcon() != null) {
	    _typeLabel.setIcon(null);
	}
	_typeLabel = null;
    }

    private PgmPanelLayout _layout = null;

    private String _id = null;
    private long _start_time = 0;
    private Image _thumbnail = null;
    private ImageIcon _thumbnailIcon = null;
    private String _title = null;
    private String _description = null;
    private String _commName = null;
    private URI _pgmURI = null;
    private URI _commURI = null;

    private MultiLineLabel _titleLabel = null;
    private LinkLabel _iconLabel = null;
    private MultiLineLabel _descLabel = null;
    private MultiLineLabel _commLabel = null;
    private TimeLabel _timeLabel = null;
    private IconLabel _onlyLabel = null;
    private IconLabel _typeLabel = null;

    private Color _fgColor = FOREGROUND_DEFAULT;
    private Color _bgColor = BACKGROUND_DEFAULT;

    private int _currWidth = 0;
    private int _currHeight = 0;
    private boolean _repaintImmediately = true;

    private PgmPanel() {
	_titleLabel = new MultiLineLabel();
	_iconLabel = new LinkLabel();
	_descLabel = new MultiLineLabel(" ");
	_commLabel = new MultiLineLabel();
	_timeLabel = new TimeLabel();
	_onlyLabel = new IconLabel();
	_typeLabel = new IconLabel();

	_titleLabel.setFont(FONT_TITLE);
	_descLabel.setFont(FONT_DESC);
	_commLabel.setFont(FONT_COMM);
	_timeLabel.setFont(FONT_TIME);
	_timeLabel.setHorizontalAlignment(SwingConstants.RIGHT);

	_layout = new PgmPanelLayout();
	setLayout(_layout);
	add(_titleLabel, Slot.TITLE);
	add(_iconLabel, Slot.ICON);
	add(_descLabel, Slot.DESC);
	add(_commLabel, Slot.COMM);
	add(_timeLabel, Slot.TIME);
	add(_typeLabel, Slot.TYPE);

	setForegroundColor(FOREGROUND_DEFAULT);
	setBackgroundColor(BACKGROUND_DEFAULT);
    }

    public void setPgmInfo(String id, String title, String link, String description,
			   String owner_name, String comm_name, String comm_id, int type,
			   int member_only, long start_time, Image thumbnail) {
	_repaintImmediately = false;
	setId(id);
	if (description.length() > 0) {
	    setDescription(description);
	} else {
	    setDescription(" ");
	}
	setStartTime(start_time);
	setThumbnail(thumbnail);
	setType(type);
	setOnly(member_only == 0 ? false : true);

	try {
	    URI pgmURI = new URI(link);
	    setTitle(title, pgmURI);
	    _iconLabel.setURI(pgmURI);

	    URI commURI = null;
	    switch (type) {
	    case 0: // コミュニティ
		commURI = new URI("http://com.nicovideo.jp/community/" + comm_id);
		setComm(comm_name, commURI, owner_name);
		break;
	    case 1: // チャンネル
		commURI = new URI("http://ch.nicovideo.jp/channel/" + comm_id);
		setComm(comm_name, commURI);
		break;
	    case 2: // 公式
	    default:
		commURI = new URI("http://live.nicovideo.jp/");
		setComm("Official", commURI);
	    }
	} catch (Exception e) {
	    log.log(Level.WARNING, MessageFormat.format("failed creating URI from {0} ({1})", comm_id, type), e);
	} finally {
	    invalidate();
	    _repaintImmediately = true;
	}
    }

    public boolean getRepaintImmediately() {
	return _repaintImmediately;
    }

    public void setRepaintImmediately(boolean value) {
	_repaintImmediately = value;
    }

    public void setId(String id) {
	_id = id;
    }

    public String getId() {
	return _id;
    }

    public void setTitle(String title, URI pgmURI) {
	if (_title != null) {
	    _title = null;
	}
	_title = (title == null || title.equals("")) ? "<タイトルなし>" : title;
	if (pgmURI != null) {
	    _pgmURI = pgmURI;
	}
	Link[] ls = {new Link(0, _title.length(), _pgmURI)};
	_titleLabel.setText(_title, ls);
	_layout.needLayout();
	if (_repaintImmediately) {
	    invalidate();
	}
    }

    public void setTitle(String title) {
	setTitle(title, null);
    }

    public String getTitle() {
	return _title;
    }

    public void setDescription(String description) {
	if (_description != null) {
	    _description = null;
	}
	_description = (description == null || description.equals("")) ? "<説明なし>" : description;
	_descLabel.setText(_description);
	_layout.needLayout();
	if (_repaintImmediately) {
	    invalidate();
	}
    }

    public String getDescription() {
	return _description;
    }

    public void setComm(String commName, URI commURI, String ownerName) {
	if (_commName != null) {
	    _commName = null;
	}
	_commName = (commName == null || commName.equals("")) ? "<コミュニティ名なし>" : commName;
	_commURI = commURI;
	Link[] ls = {new Link(0, _commName.length(), _commURI)};
	if (ownerName != null) {
	    String s = String.format("%s%s（放送者：%s）", _commName, LINE_SEPARATOR, ownerName);
	    _commLabel.setText(s, ls);
	} else {
	    _commLabel.setText(commName, ls);
	}
	_layout.needLayout();
	if (_repaintImmediately) {
	    invalidate();
	}
    }

    public void setComm(String commName, URI commURI) {
	// チャンネルの場合放送者はnull
	setComm(commName, commURI, null);
    }

    public String getCommName() {
	return _commName;
    }

    public void setStartTime(long start_time) {
	_start_time = start_time;
	_timeLabel.setText(relativeTimeString(_start_time));
	_timeLabel.setToolTipText(odf.format(_start_time));
	_layout.needLayout();
	if (_repaintImmediately) {
	    invalidate();
	}
    }

    public long getStartTime() {
	return _start_time;
    }

    public void setThumbnail(Image thumbnail) {
	// Release old image's resource
	_iconLabel.setIcon(null);
	if (_thumbnail != null) {
	    _thumbnail.flush();
	    _thumbnail = null;
	}
	// Set new image
	if (thumbnail != null) {
	    _thumbnail = thumbnail;
	    ImageIcon icon = new ImageIcon(_thumbnail);
	    _iconLabel.setIcon(icon);
	} else {
	    _iconLabel.setIcon(FALLBACK_ICON);
	}
	_layout.needLayout();
    }

    public Image getThumbnail() {
	return _thumbnail;
    }

    public void setOnly(boolean only) {
	remove(_onlyLabel);
	if (only) {
	    _onlyLabel.setIcon(MEMBER_ONLY_ICON);
	    add(_onlyLabel, Slot.ONLY);
	}
	_layout.needLayout();
    }

    public void setType(int type) {
	ImageIcon icon = null;
	switch (type) {
	case 0:
	    icon = COMMUNITY_ICON;
	    break;
	case 1:
	    icon = CHANNEL_ICON;
	    break;
	case 2:
	default:
	    icon = OFFICIAL_ICON;
	}
	_typeLabel.setIcon(icon);
	_layout.needLayout();
    }

    public void setLinkHandlers(LinkHandlers lhdrs) {
	_titleLabel.setLinkHandlers(lhdrs);
	_iconLabel.setLinkHandlers(lhdrs);
	_commLabel.setLinkHandlers(lhdrs);
    }

    /**
     * パネルの幅を固定する。
     * パネルレイアウトはこの条件のもとpreferredSizeを計算するようになる。
     * 最小幅(PgmPanelLayout.MIN_WIDTH)未満の指定は無視される。
     */
    public void setWidth(int width) {
	if (_currWidth != width) {
	    _currWidth = width;
	    if (_layout != null) {
		_layout.setWidth(width);
	    }
	    invalidate();
	}
    }

    /**
     * パネルの高さを固定する。
     * パネルレイアウトはこの条件のもとpreferredSizeを計算するようになる。
     * 最小高(PgmPanelLayout.MIN_HEIGHT)未満の指定は無視される。
     */
    public void setHeight(int height) {
	if (_currHeight != height) {
	    _currHeight = height;
	    if (_layout != null) {
		_layout.setHeight(height);
	    }
	    invalidate();
	}
    }

    @Override
    public void setSize(Dimension d) {
	setSize(d.width, d.height);
    }

    @Override
    public void setSize(int width, int height) {
	Dimension min = getMinimumSize();
	int w = width > min.width ? width : min.width;
	int h = height > min.height ? height : min.height;
	super.setSize(_currWidth > 0 ? _currWidth : w, _currHeight > 0 ? _currHeight : h);
    }

    protected void ignoreMouse(boolean b) {
	_titleLabel.ignoreMouse(b);
	_iconLabel.ignoreMouse(b);
	_descLabel.ignoreMouse(b);
	_timeLabel.ignoreMouse(b);
	_commLabel.ignoreMouse(b);
    }

    public void paint(Graphics g) {
	_timeLabel.setText(relativeTimeString(_start_time));
	super.paint(g);
    }

    private String relativeTimeString(long d) {
	long now = System.currentTimeMillis();
	long diff = now - d;
	String s = diff >= 0 ? "" : "later";
	diff = Math.abs(diff);
	if (diff < 60000) {
	    return String.format("%ds %s", diff / 1000, s);
	} else if (diff < 3600000) {
	    return String.format("%dm %s", diff / 60000, s);
	} else if (diff < 86400000) {
	    return String.format("%dh %s", diff / 3600000, s);
	} else {
	    return sdf.format(d);
	}
    }

    public void dispose() {
	removeAll();
	setLayout(null);
	_layout = null;
	if (_titleLabel != null) {
	    _titleLabel.dispose();
	}
	if (_descLabel != null) {
	    _descLabel.dispose();
	}
	if (_commLabel != null) {
	    _commLabel.dispose();
	}
	if (_thumbnail != null) {
	    _thumbnail.flush();
	    _thumbnail = null;
	}
    }


    public void setForegroundColor(Color color) {
	Color fg = color != null ? color : FOREGROUND_DEFAULT;
	_fgColor = fg;
	_titleLabel.setForeground(fg);
	_descLabel.setForeground(fg);
	_commLabel.setForeground(fg);
	_timeLabel.setForeground(fg);
    }

    public void setBackgroundColor(Color color) {
	Color bg = color != null ? color : BACKGROUND_DEFAULT;
	_bgColor = bg;
	setBackground(bg);
    }

    public Color getForegroundColor() {
	return _fgColor;
    }

    public Color getBackgroundColor() {
	return _bgColor;
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

    private static class TimeLabel extends PartLabel {
	public TimeLabel() {
	    setOpaque(false);
	}
    }
}
