// -*- coding: utf-8-unix -*-
package com.github.sgr.swingx;
import java.awt.Component;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.event.ChangeEvent;
import javax.swing.event.TableModelEvent;
import javax.swing.table.TableModel;
import javax.swing.table.TableColumnModel;

public class MultiLineTable extends JTable {
    private class RowHeightUpdator implements Runnable {
	private JTable _tbl = null;

	public RowHeightUpdator(JTable table) {
	    _tbl = table;
	}

	private int maxRowHeight(int row) {
	    // ここでのrowはビューの行
	    int maxHeight = -1;
	    for (int i = 0; i < _tbl.getColumnCount(); i++) {
		int col = _tbl.convertColumnIndexToView(i);
		Component c = _tbl.prepareRenderer(getCellRenderer(row, col), row, col);
		if (maxHeight < c.getPreferredSize().height) {
		    maxHeight = c.getPreferredSize().height;
		}
	    }
	    return maxHeight;
	}

	private void updateRowHeight(int row) {
	    int r = _tbl.convertRowIndexToView(row);
	    int mrh = maxRowHeight(r);
	    if (_tbl.getRowHeight(r) != mrh) {
		_tbl.setRowHeight(r, mrh);
	    }
	}

	public void run() {
	    for (int r = 0; r < _tbl.getRowCount(); r++) {
		updateRowHeight(r);
	    }
	}
    }

    public MultiLineTable() {
	super();
    }

    public MultiLineTable(TableModel dm, TableColumnModel cm) {
	super(dm, cm);
    }

    @Override
    public void columnMarginChanged(ChangeEvent evt) {
    	super.columnMarginChanged(evt);
	updateRowHeights();
    }

    private void updateRowHeights() {
	SwingUtilities.invokeLater(new RowHeightUpdator(this));
    }
}
