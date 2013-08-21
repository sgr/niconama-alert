// -*- coding: utf-8-unix -*-
package nico.ui;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Image;
import java.awt.image.ImageObserver;
import java.text.SimpleDateFormat;
import java.util.Date;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.table.TableCellRenderer;
import com.github.sgr.swingx.MultiLineRenderer;

public class PgmCellRenderer extends MultiLineRenderer {
    private static Color TABLE_SELECTION_FOREGROUND = (Color)UIManager.getDefaults().get("Table.selectionForeground");
    private static Color TABLE_SELECTION_INACTIVE_FOREGROUND = (Color)UIManager.getDefaults().get("Table.selectionInactiveForeground");
    private static Font FONT_PLAIN = new Font("Default", Font.PLAIN, 12);
    private static Font FONT_BOLD = new Font("Default", Font.BOLD, 12);

    private int _columnFetchedAt = -1;
    private int _columnMemberOnly = -1;

    public PgmCellRenderer(String datePattern, int columnFetchedAt, int columnMemberOnly) {
	super(datePattern);
	_columnFetchedAt = columnFetchedAt;
	_columnMemberOnly = columnMemberOnly;
    }

    @Override
    public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
	Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
	int mrow = table.convertRowIndexToModel(row);
	Date fetchedAt = (Date)table.getModel().getValueAt(mrow, _columnFetchedAt);
	if (fetchedAt != null && (60000 > (new Date().getTime() - fetchedAt.getTime()))) {
	    c.setFont(FONT_BOLD);
	} else {
	    c.setFont(FONT_PLAIN);
	}

	Boolean memberOnly = (Boolean)table.getModel().getValueAt(mrow, _columnMemberOnly);
	if (memberOnly != null && memberOnly) {
	    c.setForeground(Color.BLUE);
	}
	return c;
    }
}
