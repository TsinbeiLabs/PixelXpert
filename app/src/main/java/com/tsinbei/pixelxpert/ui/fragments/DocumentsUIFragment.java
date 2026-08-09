package com.tsinbei.pixelxpert.ui.fragments;

import com.tsinbei.pixelxpert.R;
import com.tsinbei.pixelxpert.utils.ControlledPreferenceFragmentCompat;

public class DocumentsUIFragment extends ControlledPreferenceFragmentCompat {
	@Override
	public String getTitle() {
		return getString(R.string.documentsui_header);
	}

	@Override
	public int getLayoutResource() {
		return R.xml.documentsui_prefs;
	}
}
