package com.tsinbei.pixelxpert.ui.fragments;

import com.tsinbei.pixelxpert.R;
import com.tsinbei.pixelxpert.utils.ControlledPreferenceFragmentCompat;

public class HotSpotFragment extends ControlledPreferenceFragmentCompat {
	@Override
	public String getTitle() {
		return getString(R.string.hotspot_header);
	}

	@Override
	public int getLayoutResource() {
		return R.xml.hotspot_prefs;
	}
}
