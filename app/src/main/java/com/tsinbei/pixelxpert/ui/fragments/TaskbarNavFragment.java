package com.tsinbei.pixelxpert.ui.fragments;

import com.tsinbei.pixelxpert.R;
import com.tsinbei.pixelxpert.utils.ControlledPreferenceFragmentCompat;

public class TaskbarNavFragment extends ControlledPreferenceFragmentCompat {
	@Override
	public String getTitle() {
		return getString(R.string.taskbar_header_title);
	}

	@Override
	public int getLayoutResource() {
		return R.xml.taskbar_prefs;
	}
}
