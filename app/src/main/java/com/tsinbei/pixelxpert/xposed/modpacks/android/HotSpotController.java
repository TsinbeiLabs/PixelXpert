package com.tsinbei.pixelxpert.xposed.modpacks.android;


import static de.robv.android.xposed.XposedHelpers.setObjectField;
import static com.tsinbei.pixelxpert.xposed.XPrefs.Xprefs;

import android.content.Context;

import io.github.libxposed.api.XposedModuleInterface;
import com.tsinbei.pixelxpert.xposed.XposedModPack;
import com.tsinbei.pixelxpert.xposed.annotations.FrameworkModPack;
import com.tsinbei.pixelxpert.xposed.utils.reflection.ReflectedClass;

@SuppressWarnings("RedundantThrows")
@FrameworkModPack
public class HotSpotController extends XposedModPack {
	private static long hotSpotTimeoutMillis = 0;
	private static boolean hotSpotHideSSID = false;
	private static int hotSpotMaxClients = 0;
	private static boolean hotspotDisableApproval = false;

	public HotSpotController(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {

		int clients = Xprefs.getSliderInt("hotSpotMaxClients", 0);

		hotSpotTimeoutMillis = (long) (Xprefs.getSliderFloat("hotSpotTimeoutSecs", 0) * 1000L);
		hotSpotHideSSID = Xprefs.getBoolean("hotSpotHideSSID", false);
		hotspotDisableApproval = Xprefs.getBoolean("hotspotDisableApproval", false);
		hotSpotMaxClients = clients;
	}
	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) throws Throwable {
		try {
			ReflectedClass SoftApConfiguration = ReflectedClass.of("android.net.wifi.SoftApConfiguration");


			SoftApConfiguration
					.afterConstruction()
					.run(param -> {
						setObjectField(param.thisObject, "mHiddenSsid", hotSpotHideSSID);

						if (hotspotDisableApproval) {
							setObjectField(param.thisObject, "mClientControlByUser", false);
						}

						if (hotSpotTimeoutMillis > 0) {
							setObjectField(param.thisObject, "mShutdownTimeoutMillis", hotSpotTimeoutMillis);
						}

						if (hotSpotMaxClients > 0) {
							setObjectField(param.thisObject, "mMaxNumberOfClients", hotSpotMaxClients);
						}
					});
		} catch (Throwable ignored) {
		}
	}
}
