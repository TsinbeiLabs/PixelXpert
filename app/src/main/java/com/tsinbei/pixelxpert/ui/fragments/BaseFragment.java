package com.tsinbei.pixelxpert.ui.fragments;

import static com.tsinbei.pixelxpert.utils.MiscUtils.setOnBackPressedDispatcherCallback;
import static com.tsinbei.pixelxpert.utils.MiscUtils.setupToolbar;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.tsinbei.pixelxpert.utils.DisplayUtils;

public abstract class BaseFragment extends Fragment {

	private final boolean isTabletDevice = DisplayUtils.isTablet();

	protected boolean isBackButtonEnabled() {
		return true;
	}

	public boolean getBackButtonEnabled() {
		return isBackButtonEnabled();
	}

	public abstract String getTitle();

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		setupToolbar(this, view, getTitle(), getBackButtonEnabled());

		setOnBackPressedDispatcherCallback(requireActivity(), this);
	}
}
