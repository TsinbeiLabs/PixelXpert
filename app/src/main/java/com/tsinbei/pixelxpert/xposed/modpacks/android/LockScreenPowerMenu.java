package com.tsinbei.pixelxpert.xposed.modpacks.android;

import static com.tsinbei.pixelxpert.xposed.XPrefs.Xprefs;

import android.app.KeyguardManager;
import android.content.Context;

import io.github.libxposed.api.XposedModuleInterface;
import com.tsinbei.pixelxpert.xposed.XposedModPack;
import com.tsinbei.pixelxpert.xposed.annotations.FrameworkModPack;
import com.tsinbei.pixelxpert.xposed.utils.reflection.ReflectedClass;
import com.tsinbei.pixelxpert.xposed.utils.toolkit.Logger;

/**
 * Lockscreen power-menu protection adapted from TouchMeNot's feature design.
 * This implementation was rewritten for PixelXpert and the libxposed API.
 */
@FrameworkModPack
public class LockScreenPowerMenu extends XposedModPack {
	private static boolean blockPowerMenu;

	public LockScreenPowerMenu(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {
		blockPowerMenu = Xprefs.getBoolean("LockScreenBlockPowerMenu", false);
	}

	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) {
		try {
			ReflectedClass.of("com.android.server.policy.PhoneWindowManager")
					.before("showGlobalActions")
					.run(param -> {
						if (blockPowerMenu && isDeviceLocked()) param.setResult(null);
					});
		} catch (Throwable t) {
			Logger.log("LockScreenPowerMenu: failed to hook global actions", t);
		}
	}

	private boolean isDeviceLocked() {
		KeyguardManager keyguardManager = mContext.getSystemService(KeyguardManager.class);
		return keyguardManager != null && keyguardManager.isKeyguardLocked();
	}
}
