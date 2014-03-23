// -*- coding: utf-8-unix -*-
package nico.ui;
import java.util.Comparator;

public class PgmPanelComparator implements Comparator<PgmPanel> {
    private long _origin = -1;

    public void setOriginDate(long d) {
	_origin = d;
    }

    public int compare(PgmPanel o1, PgmPanel o2) {
	if (_origin >= 0) {
	    return (int)(Math.abs(_origin - o1.getOpenTime().getTime()) -
			 Math.abs(_origin - o2.getOpenTime().getTime()));
	} else {
	    return (int)(o2.getOpenTime().getTime() - o1.getOpenTime().getTime());
	}
    }
}
