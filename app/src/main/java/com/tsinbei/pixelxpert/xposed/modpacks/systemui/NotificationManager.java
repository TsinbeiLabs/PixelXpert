package com.tsinbei.pixelxpert.xposed.modpacks.systemui;

import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.setObjectField;
import static com.tsinbei.pixelxpert.xposed.XPrefs.Xprefs;


import android.content.Context;
import android.service.notification.StatusBarNotification;

import io.github.libxposed.api.XposedModuleInterface;
import com.tsinbei.pixelxpert.xposed.XposedModPack;
import com.tsinbei.pixelxpert.xposed.annotations.SystemUIModPack;
import com.tsinbei.pixelxpert.xposed.utils.reflection.ReflectedClass;

@SuppressWarnings("RedundantThrows")
@SystemUIModPack
public class NotificationManager extends XposedModPack {
	private Object HeadsUpManager = null;

	private static int HeadupAutoDismissNotificationDecay = -1;
	private boolean DisableOngoingNotifDismiss = false;

	public NotificationManager(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {
		HeadupAutoDismissNotificationDecay = Xprefs.getSliderInt( "HeadupAutoDismissNotificationDecay", -1);
		DisableOngoingNotifDismiss = Xprefs.getBoolean("DisableOngoingNotifDismiss", false);
		try {
			applyDurations();
		} catch (Throwable ignored) {}
	}

	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) throws Throwable {
		ReflectedClass HeadsUpManagerClass = ReflectedClass.of("com.android.systemui.statusbar.notification.headsup.HeadsUpManagerImpl");

		HeadsUpManagerClass
				.afterConstruction()
				.run(param -> {
					HeadsUpManager = param.thisObject;
					applyDurations();
				});

		ReflectedClass.of(StatusBarNotification.class)
				.after("isNonDismissable")
				.run(param -> {
					if(DisableOngoingNotifDismiss) {
						param.setResult((boolean) param.getResult() || ((StatusBarNotification) param.thisObject).isOngoing());
					}
				});
	}

	private void applyDurations() {
		if(HeadsUpManager != null && HeadupAutoDismissNotificationDecay > 0)
		{
			setObjectField(HeadsUpManager, "mMinimumDisplayTimeDefault", Math.round(HeadupAutoDismissNotificationDecay / 2.5f));
			setObjectField(HeadsUpManager, "mAutoDismissTime", HeadupAutoDismissNotificationDecay);
		}
	}
}