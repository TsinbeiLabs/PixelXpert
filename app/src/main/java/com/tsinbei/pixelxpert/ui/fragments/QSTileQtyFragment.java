package com.tsinbei.pixelxpert.ui.fragments;

import com.tsinbei.pixelxpert.R;
import com.tsinbei.pixelxpert.utils.ControlledPreferenceFragmentCompat;

public class QSTileQtyFragment extends ControlledPreferenceFragmentCompat {
	@Override
	public String getTitle() {
		return getString(R.string.qs_tile_qty_title);
	}

	@Override
	public int getLayoutResource() {
		return R.xml.qs_tile_qty_prefs;
	}
}
