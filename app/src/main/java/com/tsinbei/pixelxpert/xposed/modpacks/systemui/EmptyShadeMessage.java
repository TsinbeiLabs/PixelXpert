package com.tsinbei.pixelxpert.xposed.modpacks.systemui;

import static com.tsinbei.pixelxpert.xposed.XPrefs.Xprefs;

import android.content.Context;
import android.os.Build;

import io.github.libxposed.api.XposedModuleInterface;
import com.tsinbei.pixelxpert.xposed.XposedModPack;
import com.tsinbei.pixelxpert.xposed.annotations.SystemUIModPack;
import com.tsinbei.pixelxpert.xposed.utils.reflection.ReflectedClass;

/**
 * Replaces the empty notification shade message with custom text (Android 17 Compose shade).
 * Adapted from PixelInjector "Empty shade message" by hxreborn (GPL-3.0).
 */
@SuppressWarnings("RedundantThrows")
@SystemUIModPack
public class EmptyShadeMessage extends XposedModPack {
	// Android 17 (Cinnamon Bun) moved the empty shade to a Compose implementation
	private static final int MIN_SDK = 37;
	private static final String CONTENT =
			"com.android.systemui.notifications.stack.emptyshade.ui.composable.EmptyShadeContentImplKt";

	private static boolean enabled = false;
	private static String customText = "";

	public EmptyShadeMessage(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {
		if (Xprefs == null) return;
		enabled = Xprefs.getBoolean("emptyShadeEnabled", false);
		customText = Xprefs.getString("emptyShadeText", "").trim();
	}

	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) throws Throwable {
		if (Build.VERSION.SDK_INT < MIN_SDK) return;

		ReflectedClass contentClass = ReflectedClass.ofIfPossible(CONTENT);
		if (contentClass.getClazz() == null) return;

		// EmptyShadeContent(message: String, icon, ...): override the message argument.
		// Recomposed each time the empty shade is shown, so text changes apply on next open.
		contentClass
				.before("EmptyShadeContent")
				.run(param -> {
					if (!enabled || customText.isEmpty()) return;
					if (param.args != null && param.args.length > 0 && param.args[0] instanceof String) {
						param.args[0] = customText;
					}
				});
	}
}
