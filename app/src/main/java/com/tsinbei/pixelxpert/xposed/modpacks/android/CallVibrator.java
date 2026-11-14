package com.tsinbei.pixelxpert.xposed.modpacks.android;

import static android.os.SystemClock.uptimeMillis;
import static android.os.VibrationAttributes.USAGE_ACCESSIBILITY;
import static com.tsinbei.pixelxpert.xposed.XPrefs.Xprefs;

import android.content.Context;
import android.os.VibrationEffect;

import io.github.libxposed.api.XposedModuleInterface;
import com.tsinbei.pixelxpert.annotations.ChildProcessModPack;
import com.tsinbei.pixelxpert.annotations.MainProcessModPack;
import com.tsinbei.pixelxpert.xposed.XposedModPack;
import com.tsinbei.pixelxpert.xposed.annotations.TelecomServerModPack;
import com.tsinbei.pixelxpert.xposed.utils.SystemUtils;
import com.tsinbei.pixelxpert.xposed.utils.reflection.ReflectedClass;

@SuppressWarnings("RedundantThrows")
@TelecomServerModPack
@MainProcessModPack //telecom server shouldn't fall under child process checks
@ChildProcessModPack(processNameContains = "")
public class CallVibrator extends XposedModPack {
	public static final int DIALING = 3;
	public static final int ACTIVE = 5;
	public static final int DISCONNECTED = 7;

	private static final VibrationEffect mVibrationEffect = VibrationEffect.createWaveform(new long[]{0, 100, 100, 100}, -1); //100ms on, 100 off, 100 again. don't repeat
	private static long mLastVibration = 0;
	private static boolean vibrateOnAnswered = false, vibrateOnDrop = false;

	public CallVibrator(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {
		vibrateOnAnswered = Xprefs.getBoolean("vibrateOnAnswered", false);
		vibrateOnDrop = Xprefs.getBoolean("vibrateOnDrop", false);
	}
	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) throws Throwable {
		try {
			ReflectedClass InCallControllerClass = ReflectedClass.of("com.android.server.telecom.InCallController");

			InCallControllerClass
					.before("onCallStateChanged")
					.run(param -> {
						try {
							int oldState = (int) param.args[1];
							int newState = (int) param.args[2];

							if (
									(vibrateOnAnswered
											&& oldState == DIALING
											&& newState == ACTIVE)
											||
											(vibrateOnDrop
													&& oldState == ACTIVE
													&& newState == DISCONNECTED)
							) {
								vibrate();
							}
						} catch (Throwable ignored) {}
					});
		} catch (Throwable ignored) {}
	}

	private void vibrate() {
		if(uptimeMillis() - mLastVibration > 200L) {
			mLastVibration = uptimeMillis();
			SystemUtils.vibrate(mVibrationEffect, USAGE_ACCESSIBILITY);
		}
	}
}