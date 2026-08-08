package com.tsinbei.pixelxpert.ui.fragments;

import com.tsinbei.pixelxpert.R;
import com.tsinbei.pixelxpert.utils.ControlledPreferenceFragmentCompat;

public class ThreeButtonNavFragment extends ControlledPreferenceFragmentCompat {
	@Override
	public String getTitle() {
		return getString(R.string.threebutton_header_title);
	}

	@Override
	public int getLayoutResource() {
		return R.xml.three_button_prefs;
	}
}
