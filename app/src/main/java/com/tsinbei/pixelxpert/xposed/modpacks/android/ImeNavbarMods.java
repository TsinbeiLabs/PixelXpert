package com.tsinbei.pixelxpert.xposed.modpacks.android;

import static com.tsinbei.pixelxpert.xposed.XPrefs.Xprefs;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.callMethod;

import android.content.Context;

import io.github.libxposed.api.XposedModuleInterface;
import com.tsinbei.pixelxpert.xposed.XposedModPack;
import com.tsinbei.pixelxpert.xposed.annotations.FrameworkModPack;
import com.tsinbei.pixelxpert.xposed.utils.reflection.ReflectedClass;

/**
 * Hides the row the IME draws under its keys (back chevron + keyboard switcher) in gesture
 * navigation, by clearing the nav-button flags system_server hands to the IME.
 * Adapted from PixelXpert-Next (GPLv3).
 */
@SuppressWarnings("RedundantThrows")
@FrameworkModPack
public class ImeNavbarMods extends XposedModPack {
	/** NAV_BUTTON_FLAG_IME_DRAWS_NAV_BAR */
	private static final int FLAG_IME_DRAWS_NAV_BAR = 1;
	/** NAV_BUTTON_FLAG_IME_SWITCHER_IN_NAV_BAR */
	private static final int FLAG_IME_SWITCHER_IN_NAV_BAR = 4;
	private static final int HIDDEN_FLAGS = FLAG_IME_DRAWS_NAV_BAR | FLAG_IME_SWITCHER_IN_NAV_BAR;

	private static boolean hideButtons = false;
	private static Object service = null;
	private static Object lastUserData = null;

	public ImeNavbarMods(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {
		if (Xprefs == null) return;
		boolean enabled = Xprefs.getBoolean("KeyboardSpaceHideButtons", false);
		if (enabled == hideButtons) return;

		hideButtons = enabled;
		resendFlags();
	}

	/** Makes the service recompute and re-send the flags so the toggle applies without an IME rebind. */
	private static void resendFlags() {
		Object inputMethodService = service;
		Object userData = lastUserData;
		if (inputMethodService == null || userData == null) return;
		try {
			callMethod(inputMethodService, "sendOnNavButtonFlagsChangedLocked", userData);
		} catch (Throwable ignored) {
		}
	}

	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) throws Throwable {
		ReflectedClass immsClass = ReflectedClass.ofIfPossible("com.android.server.inputmethod.InputMethodManagerService");
		if (immsClass.getClazz() == null) return;

		immsClass
				.after("getInputMethodNavButtonFlagsLocked")
				.run(param -> {
					service = param.thisObject;
					if (param.args != null && param.args.length > 0) {
						lastUserData = param.args[0];
					}
					if (hideButtons) {
						int flags = (int) param.getResult();
						param.setResult(flags & ~HIDDEN_FLAGS);
					}
				});
	}
}
