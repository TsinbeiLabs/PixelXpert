package com.tsinbei.pixelxpert.ui.fragments;

import com.tsinbei.pixelxpert.R;
import com.tsinbei.pixelxpert.utils.ControlledPreferenceFragmentCompat;

public class SBCFragment extends ControlledPreferenceFragmentCompat {
	@Override
	public String getTitle() {
		return getString(R.string.sbc_header);
	}

	@Override
	public int getLayoutResource() {
		return R.xml.statusbar_clock_prefs;
	}
}
