package com.tsinbei.pixelxpert.ui.fragments;

import com.tsinbei.pixelxpert.R;
import com.tsinbei.pixelxpert.utils.ControlledPreferenceFragmentCompat;

public class NavFragment extends ControlledPreferenceFragmentCompat {
	@Override
	public String getTitle() {
		return getString(R.string.nav_header);
	}

	@Override
	public int getLayoutResource() {
		return R.xml.nav_prefs;
	}
}
