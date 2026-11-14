/* Adapted from AIXINJUELUOAI/NativeComplements StatusBarOffset, Apache-2.0.
 * Changes: use PixelXpert modpack lifecycle and shared preferences. */
package com.tsinbei.pixelxpert.xposed.modpacks.systemui;

import android.content.Context;
import android.view.View;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import io.github.libxposed.api.XposedModuleInterface;
import com.tsinbei.pixelxpert.xposed.XposedModPack;
import com.tsinbei.pixelxpert.xposed.annotations.SystemUIModPack;
import com.tsinbei.pixelxpert.xposed.utils.reflection.ReflectedClass;
import static com.tsinbei.pixelxpert.xposed.XPrefs.Xprefs;

@SystemUIModPack
public class StatusBarOffset extends XposedModPack {
	private final List<WeakReference<View>> bars = new ArrayList<>();
	private volatile int offsetDp;
	public StatusBarOffset(Context context) { super(context); }

	@Override public void onPreferenceUpdated(String... keys) {
		offsetDp = Xprefs.getSliderInt("StatusBarContentOffset", 0);
		synchronized (bars) {
			bars.removeIf(reference -> reference.get() == null);
			for (WeakReference<View> reference : bars) {
				View bar = reference.get();
				if (bar != null) bar.post(() -> apply(bar));
			}
		}
	}

	@Override public void onPackageLoaded(XposedModuleInterface.PackageReadyParam param) {
		for (String name : new String[]{"com.android.systemui.statusbar.phone.PhoneStatusBarView",
				"com.android.systemui.statusbar.phone.KeyguardStatusBarView"}) {
			ReflectedClass type = ReflectedClass.ofIfPossible(name);
			type.afterConstruction().run(hook -> remember((View) hook.thisObject));
			type.after("onFinishInflate").run(hook -> apply((View) hook.thisObject));
			type.after("onConfigurationChanged").run(hook -> apply((View) hook.thisObject));
		}
		ReflectedClass.ofIfPossible("com.android.systemui.statusbar.phone.PhoneStatusBarView")
				.after("updateStatusBarHeight").run(hook -> apply((View) hook.thisObject));
	}

	private void remember(View bar) {
		synchronized (bars) {
			bars.removeIf(reference -> reference.get() == null);
			for (WeakReference<View> reference : bars) if (reference.get() == bar) return;
			bars.add(new WeakReference<>(bar));
		}
		bar.post(() -> apply(bar));
	}

	private void apply(View bar) {
		if (bar.getClass().getName().endsWith("KeyguardStatusBarView")) {
			bar.setTranslationY(offsetDp * bar.getResources().getDisplayMetrics().density);
			return;
		}
		int id = bar.getResources().getIdentifier("status_bar_contents", "id", "com.android.systemui");
		View content = id == 0 ? null : bar.findViewById(id);
		// Wait for inflation instead of shifting the parent and later shifting
		// its child as well, which would apply the offset twice.
		if (content == null) return;
		content.setTranslationY(offsetDp * bar.getResources().getDisplayMetrics().density);
	}
}
