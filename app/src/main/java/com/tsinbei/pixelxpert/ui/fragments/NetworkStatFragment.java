package com.tsinbei.pixelxpert.ui.fragments;

import com.tsinbei.pixelxpert.R;
import com.tsinbei.pixelxpert.utils.ControlledPreferenceFragmentCompat;

public class NetworkStatFragment extends ControlledPreferenceFragmentCompat {
	@Override
	public String getTitle() {
		return getString(R.string.netstat_header);
	}

	@Override
	public int getLayoutResource() {
		return R.xml.lsqs_custom_text;
	}
}
