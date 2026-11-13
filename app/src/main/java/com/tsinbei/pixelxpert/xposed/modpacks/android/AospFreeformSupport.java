package com.tsinbei.pixelxpert.xposed.modpacks.android;

import android.content.Context;
import com.tsinbei.pixelxpert.xposed.XposedModPack;
import com.tsinbei.pixelxpert.xposed.annotations.FrameworkModPack;
import com.tsinbei.pixelxpert.xposed.utils.reflection.ReflectedClass;
import io.github.libxposed.api.XposedModuleInterface;
import static com.tsinbei.pixelxpert.xposed.XPrefs.Xprefs;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.getBooleanField;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.getObjectField;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.setObjectField;

/** Enable the existing AOSP freeform capability for the module's own window
 * entry points. Restore the platform setting when both entry points are off. */
@FrameworkModPack
public class AospFreeformSupport extends XposedModPack {
	private volatile boolean requested;
	private Object service;
	private boolean nativeSupport;
	public AospFreeformSupport(Context context) { super(context); }
	@Override public void onPreferenceUpdated(String... keys) {
		requested = Xprefs.getBoolean("NotificationFreeform", false)
				|| (Xprefs.getBoolean("LauncherFreeformGesture", false) && "0".equals(Xprefs.getString("LauncherFreeformMode", "0")));
		if (service != null) apply();
	}
	@Override public void onPackageLoaded(XposedModuleInterface.PackageReadyParam param) {
		ReflectedClass type = ReflectedClass.of("com.android.server.wm.ActivityTaskManagerService");
		type.after("retrieveSettings").run(hook -> {
			service = hook.thisObject;
			nativeSupport = getBooleanField(service, "mSupportsFreeformWindowManagement");
			apply();
		});
		type.before("systemReady").run(hook -> {
			if (service == null) {
				service = hook.thisObject;
				nativeSupport = getBooleanField(service, "mSupportsFreeformWindowManagement");
				apply();
			}
		});
	}
	private void apply() {
		try {
			synchronized (getObjectField(service, "mGlobalLock")) {
				setObjectField(service, "mSupportsFreeformWindowManagement", nativeSupport || requested);
			}
		} catch (Throwable failure) { log("AospFreeformSupport: capability unavailable", failure); }
	}
}
