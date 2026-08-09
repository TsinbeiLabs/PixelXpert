package com.tsinbei.pixelxpert.xposed.modpacks.systemui;

import static com.tsinbei.pixelxpert.xposed.XPrefs.Xprefs;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;

import com.tsinbei.pixelxpert.xposed.XposedModPack;
import com.tsinbei.pixelxpert.xposed.annotations.SystemUIModPack;
import com.tsinbei.pixelxpert.xposed.utils.SystemUtils;
import com.tsinbei.pixelxpert.xposed.utils.reflection.ReflectedClass;

import io.github.libxposed.api.XposedModuleInterface;

/** Enables the platform split shade only while the phone is in landscape. */
@SystemUIModPack
public class LandscapeSplitShade extends XposedModPack {
	private static final String PREF_KEY = "LandscapeSplitShade";
	private boolean enabled;

	public LandscapeSplitShade(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... key) {
		if (Xprefs == null) return;
		enabled = Xprefs.getBoolean(PREF_KEY, false);
		if (key.length > 0 && PREF_KEY.equals(key[0])) {
			SystemUtils.restart("systemui");
		}
	}

	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam param) {
		final Resources resources = mContext.getResources();
		final int splitShadeResource = resources.getIdentifier(
				"config_use_split_notification_shade", "bool", mContext.getPackageName());
		final int oneHandedBouncerResource = resources.getIdentifier(
				"can_use_one_handed_bouncer", "bool", mContext.getPackageName());
		final int ringerDrawerResource = resources.getIdentifier(
				"volume_dialog_ringer_drawer_should_open_to_the_side", "bool", mContext.getPackageName());
		if (splitShadeResource == 0) return;

		ReflectedClass.of(Resources.class).before("getBoolean").run(hook -> {
			if (!enabled || !(hook.args[0] instanceof Integer resourceId)
					|| mContext.getResources().getConfiguration().orientation
					!= Configuration.ORIENTATION_LANDSCAPE) return;
			if (resourceId == splitShadeResource || resourceId == oneHandedBouncerResource) {
				hook.setResult(true);
			} else if (resourceId == ringerDrawerResource) {
				hook.setResult(false);
			}
		});
	}
}
