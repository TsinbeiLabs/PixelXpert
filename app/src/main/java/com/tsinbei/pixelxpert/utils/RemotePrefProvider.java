package com.tsinbei.pixelxpert.utils;

import android.content.Context;
import android.content.pm.ProviderInfo;

import androidx.annotation.NonNull;

import com.crossbowffs.remotepreferences.RemotePreferenceFile;
import com.crossbowffs.remotepreferences.RemotePreferenceProvider;

import com.tsinbei.pixelxpert.BuildConfig;

public class RemotePrefProvider extends RemotePreferenceProvider {
	public RemotePrefProvider() {
		super(BuildConfig.APPLICATION_ID, new RemotePreferenceFile[]{new RemotePreferenceFile(BuildConfig.APPLICATION_ID + "_preferences", true)});
	}

	@Override
	public void attachInfo(@NonNull Context context, @NonNull ProviderInfo info) {
		super.attachInfo(context.createDeviceProtectedStorageContext(), info);
	}
}
