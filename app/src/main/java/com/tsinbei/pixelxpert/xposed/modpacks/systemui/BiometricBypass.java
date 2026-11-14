package com.tsinbei.pixelxpert.xposed.modpacks.systemui;

import static com.tsinbei.pixelxpert.xposed.XPrefs.Xprefs;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.View;
import android.widget.Button;

import java.util.regex.Pattern;

import io.github.libxposed.api.XposedModuleInterface;
import com.tsinbei.pixelxpert.xposed.XposedModPack;
import com.tsinbei.pixelxpert.xposed.annotations.SystemUIModPack;
import com.tsinbei.pixelxpert.xposed.utils.reflection.ReflectedClass;
import com.tsinbei.pixelxpert.xposed.utils.toolkit.Logger;

/**
 * Auto-taps the confirm button on the biometric prompt after a successful face unlock,
 * skipping the manual confirmation step.
 * Adapted from biometric-bypass by rafareborn/hxreborn (MIT License).
 */
@SuppressWarnings("RedundantThrows")
@SystemUIModPack
public class BiometricBypass extends XposedModPack {
	private static final String[] TARGET_CLASSES = {
			"com.android.systemui.biometrics.prompt.ui.AuthContainerView",
			"com.android.systemui.biometrics.AuthContainerView",
	};
	private static final String BUTTON_CONFIRM_ID = "button_confirm";
	private static final long MAX_WAIT_MS = 1500L;

	private static boolean enabled = false;
	private static int confirmButtonId = 0;

	public BiometricBypass(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {
		if (Xprefs == null) return;
		enabled = Xprefs.getBoolean("biometricBypassEnabled", false);
	}

	@SuppressLint("DiscouragedApi")
	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) throws Throwable {
		Class<?> targetClass = null;
		for (String name : TARGET_CLASSES) {
			targetClass = ReflectedClass.ofIfPossible(name).getClazz();
			if (targetClass != null) break;
		}
		if (targetClass == null) {
			return;
		}

		// onDialogAnimatedIn can be obfuscated with a $suffix, so match by prefix
		ReflectedClass.of(targetClass)
				.after(Pattern.compile("onDialogAnimatedIn.*"))
				.run(param -> {
					if (!enabled) return;
					if (!(param.thisObject instanceof View)) return;

					View authContainerView = (View) param.thisObject;
					if (confirmButtonId == 0) {
						Context context = authContainerView.getContext();
						confirmButtonId = context.getResources().getIdentifier(
								BUTTON_CONFIRM_ID, "id", context.getPackageName());
					}
					if (confirmButtonId == 0) return;

					long startUptimeMs = android.os.SystemClock.uptimeMillis();
					if (!clickConfirm(authContainerView, startUptimeMs)) {
						retryUntilShown(authContainerView, startUptimeMs);
					}
				});
	}

	private static boolean clickConfirm(View parentView, long startUptimeMs) {
		Button button = parentView.findViewById(confirmButtonId);
		if (button == null || !button.isShown()) return false;
		button.performClick();
		return true;
	}

	private static void retryUntilShown(View parentView, long startUptimeMs) {
		parentView.postOnAnimation(() -> {
			if (clickConfirm(parentView, startUptimeMs)) return;
			long elapsed = android.os.SystemClock.uptimeMillis() - startUptimeMs;
			if (elapsed >= MAX_WAIT_MS) {
				Logger.log("BiometricBypass: confirm button not found after " + elapsed + "ms");
				return;
			}
			retryUntilShown(parentView, startUptimeMs);
		});
	}
}
