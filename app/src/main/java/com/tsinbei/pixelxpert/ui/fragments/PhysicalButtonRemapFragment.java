package com.tsinbei.pixelxpert.ui.fragments;

import com.tsinbei.pixelxpert.R;
import com.tsinbei.pixelxpert.utils.ControlledPreferenceFragmentCompat;

public class PhysicalButtonRemapFragment extends ControlledPreferenceFragmentCompat {
	@Override
	public String getTitle() {
		return getString(R.string.remap_physical_buttons_title);
	}

	@Override
	public int getLayoutResource() {
		return R.xml.physical_buttons_prefs;
	}
}
