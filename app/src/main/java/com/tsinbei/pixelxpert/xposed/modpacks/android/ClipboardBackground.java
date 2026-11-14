package com.tsinbei.pixelxpert.xposed.modpacks.android;

import static com.tsinbei.pixelxpert.xposed.XPrefs.Xprefs;

import android.content.Context;

import java.util.Collections;
import java.util.Set;

import io.github.libxposed.api.XposedModuleInterface;
import com.tsinbei.pixelxpert.xposed.XposedModPack;
import com.tsinbei.pixelxpert.xposed.annotations.FrameworkModPack;
import com.tsinbei.pixelxpert.xposed.utils.reflection.ReflectedClass;

/**
 * Lets allow-listed apps read the clipboard while in the background by treating them as the
 * default IME for the clipboard access check.
 * Adapted from PixelInjector "Clipboard in background" by hxreborn (GPL-3.0).
 */
@SuppressWarnings("RedundantThrows")
@FrameworkModPack
public class ClipboardBackground extends XposedModPack {
	private static final String CLIPBOARD_SERVICE = "com.android.server.clipboard.ClipboardService";

	private static boolean enabled = false;
	private static Set<String> allowedApps = Collections.emptySet();

	public ClipboardBackground(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {
		if (Xprefs == null) return;
		enabled = Xprefs.getBoolean("clipboardBackgroundEnabled", false);
		allowedApps = Xprefs.getStringSet("clipboardBackgroundApps", Collections.emptySet());
	}

	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) throws Throwable {
		ReflectedClass clipboardServiceClass = ReflectedClass.ofIfPossible(CLIPBOARD_SERVICE);
		if (clipboardServiceClass.getClazz() == null) return;

		// isDefaultIme(int userId, String packageName): returning true for an allow-listed
		// package lets it bypass the background clipboard restriction.
		clipboardServiceClass
				.after("isDefaultIme")
				.run(param -> {
					if (!enabled || param.args == null || param.args.length < 2) return;
					if (param.args[1] instanceof String pkg && allowedApps.contains(pkg)) {
						param.setResult(true);
					}
				});
	}
}
