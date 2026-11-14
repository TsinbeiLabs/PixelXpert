package com.tsinbei.pixelxpert.xposed.modpacks.allApps;

import static com.tsinbei.pixelxpert.Constants.ACTION_CHECK_XPOSED_ENABLED;
import static com.tsinbei.pixelxpert.Constants.ACTION_XPOSED_CONFIRMED;
import static com.tsinbei.pixelxpert.xposed.utils.toolkit.Logger.log;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.UserHandle;

import io.github.libxposed.api.XposedModuleInterface;
import com.tsinbei.pixelxpert.BuildConfig;
import com.tsinbei.pixelxpert.Constants;
import com.tsinbei.pixelxpert.xposed.XPLauncher;
import com.tsinbei.pixelxpert.xposed.XposedModPack;
import com.tsinbei.pixelxpert.xposed.annotations.CommonModPack;

@CommonModPack
public class HookTester extends XposedModPack {
	public HookTester(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {}

	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) throws Throwable {
		BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				new Thread(() -> {
					Intent broadcast = new Intent(ACTION_XPOSED_CONFIRMED);

					broadcast.putExtra("packageName", XPLauncher.isSystemServer ? Constants.SYSTEM_FRAMEWORK_PACKAGE : PRParam.getPackageName());

					broadcast.setPackage(BuildConfig.APPLICATION_ID);

					try {
						if (XPLauncher.isSystemServer) {
							mContext.sendBroadcastAsUser(broadcast, UserHandle.getUserHandleForUid(1000));
						} else {
							mContext.sendBroadcast(broadcast);
						}

						log("PixelXpert hook test successful for " + broadcast.getStringExtra("packageName"));
					} catch (Throwable throwable) {
						log(throwable);
					}
				}).start();
			}
		};

		mContext.registerReceiver(broadcastReceiver,
				new IntentFilter(ACTION_CHECK_XPOSED_ENABLED),
				Context.RECEIVER_EXPORTED);
	}
}
