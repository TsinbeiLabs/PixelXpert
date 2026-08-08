package com.tsinbei.pixelxpert.xposed.modpacks.settings;

import static com.tsinbei.pixelxpert.xposed.XPrefs.Xprefs;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModuleInterface;
import com.tsinbei.pixelxpert.xposed.XposedModPack;
import com.tsinbei.pixelxpert.xposed.annotations.SettingsModPack;
import com.tsinbei.pixelxpert.xposed.utils.reflection.ReflectedClass;

/**
 * App info enhancements adapted from Juby210/AppInfoFix under the MIT License.
 */
@SettingsModPack
public class AppInfoEnhancer extends XposedModPack {
	private static boolean showVersionCode;
	private static boolean allowDisableUserApps;

	public AppInfoEnhancer(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {
		showVersionCode = Xprefs.getBoolean("appInfoShowVersionCode", false);
		allowDisableUserApps = Xprefs.getBoolean("appInfoAllowDisableUserApps", false);
	}

	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) throws Throwable {
		ReflectedClass appInfoProviderCompanion = ReflectedClass.ofIfPossible(
				"com.android.settingslib.spaprivileged.template.app.AppInfoProvider$Companion");
		if (appInfoProviderCompanion.getClazz() != null) {
			Method getVersionName = appInfoProviderCompanion.getClazz().getDeclaredMethod(
					"getVersionNameBidiWrapped", PackageInfo.class);
			appInfoProviderCompanion.after(getVersionName).run(param -> {
				if (!showVersionCode || param.getResult() == null) return;

				PackageInfo packageInfo = (PackageInfo) param.args[0];
				param.setResult(param.getResult() + " (" + packageInfo.getLongVersionCode() + ")");
			});
		}

		ReflectedClass appDisableButton = ReflectedClass.ofIfPossible(
				"com.android.settings.spa.app.appinfo.AppDisableButton");
		ReflectedClass composer = ReflectedClass.ofIfPossible("androidx.compose.runtime.Composer");
		if (appDisableButton.getClazz() != null && composer.getClazz() != null) {
			Method getActionButton = appDisableButton.getClazz().getDeclaredMethod(
					"getActionButton", ApplicationInfo.class, composer.getClazz(), int.class);
			appDisableButton.before(getActionButton).run(param -> {
				if (!allowDisableUserApps) return;

				ApplicationInfo applicationInfo = (ApplicationInfo) param.args[0];
				if ((applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
					ApplicationInfo systemLikeInfo = new ApplicationInfo(applicationInfo);
					systemLikeInfo.flags |= ApplicationInfo.FLAG_SYSTEM;
					param.args[0] = systemLikeInfo;
				}
			});
		}
	}
}
