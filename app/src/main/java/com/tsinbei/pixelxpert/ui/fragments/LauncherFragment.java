package com.tsinbei.pixelxpert.ui.fragments;

import android.os.Bundle;

import androidx.annotation.Nullable;

import com.tsinbei.pixelxpert.R;
import com.tsinbei.pixelxpert.utils.ControlledPreferenceFragmentCompat;

public class LauncherFragment extends ControlledPreferenceFragmentCompat {
	@Override
	public String getTitle() {
		return getString(R.string.launcher_header);
	}

	@Override
	public int getLayoutResource() {
		return R.xml.launcher_prefs;
	}

	@Override
	public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
		super.onCreatePreferences(savedInstanceState, rootKey);
	}
}
