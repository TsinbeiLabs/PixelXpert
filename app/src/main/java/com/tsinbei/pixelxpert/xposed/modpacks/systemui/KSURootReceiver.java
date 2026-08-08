package com.tsinbei.pixelxpert.xposed.modpacks.systemui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import io.github.libxposed.api.XposedModuleInterface;
import com.tsinbei.pixelxpert.utils.AppUtils;
import com.tsinbei.pixelxpert.Constants;
import com.tsinbei.pixelxpert.xposed.XposedModPack;
import com.tsinbei.pixelxpert.xposed.annotations.SystemUIModPack;

@SystemUIModPack
public class KSURootReceiver extends XposedModPack {
	public KSURootReceiver(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {}

	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) throws Throwable {
		BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				AppUtils.runKSURootActivity(mContext, false);
			}
		};

		IntentFilter filter = new IntentFilter();
		filter.addAction(Constants.ACTION_KSU_ACQUIRE_ROOT);

		mContext.registerReceiver(broadcastReceiver, filter, Context.RECEIVER_EXPORTED);
	}
}