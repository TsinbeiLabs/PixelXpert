package com.tsinbei.pixelxpert.xposed.modpacks.launcher;

import static com.tsinbei.pixelxpert.xposed.XPrefs.Xprefs;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.getObjectField;

import android.content.Context;
import android.content.res.Resources;
import android.util.SparseIntArray;
import android.view.View;

import java.lang.ref.WeakReference;

import io.github.libxposed.api.XposedModuleInterface;
import com.tsinbei.pixelxpert.xposed.XposedModPack;
import com.tsinbei.pixelxpert.xposed.annotations.LauncherModPack;
import com.tsinbei.pixelxpert.xposed.utils.reflection.ReflectedClass;

/**
 * Hides the Pixel Launcher search bar above the hotseat and frees the row it reserves.
 * Adapted from PixelInjector "Hide search bar" by hxreborn (GPL-3.0).
 */
@SuppressWarnings("RedundantThrows")
@LauncherModPack
public class HideLauncherSearchBar extends XposedModPack {
	private static final String HOTSEAT = "com.android.launcher3.Hotseat";
	private static final String QSB_HEIGHT = "qsb_widget_height";
	private static final int UNKNOWN = 0;
	private static final int ZERO = 1;
	private static final int KEEP = 2;

	private static boolean enabled = false;
	private static WeakReference<View> qsbViewRef = null;
	private static final SparseIntArray dimensionVerdicts = new SparseIntArray();

	public HideLauncherSearchBar(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {
		if (Xprefs == null) return;
		enabled = Xprefs.getBoolean("hideLauncherSearchBar", false);
		applyToLiveView();
	}

	private static void applyToLiveView() {
		View view = qsbViewRef == null ? null : qsbViewRef.get();
		if (view == null) return;
		view.post(() -> {
			try {
				view.setVisibility(enabled ? View.GONE : View.VISIBLE);
			} catch (Throwable ignored) {
			}
		});
	}

	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) throws Throwable {
		ReflectedClass hotseatClass = ReflectedClass.ofIfPossible(HOTSEAT);
		if (hotseatClass.getClazz() != null) {
			ReflectedClass.ReflectionConsumer capture = param -> captureQsb(param.thisObject);
			hotseatClass.afterConstruction().run(capture);
			hotseatClass.after("setInsets").run(capture); //optional on some builds
		}

		// Zero out the reserved search-bar height so the hotseat reclaims the row
		ReflectedClass.of(Resources.class)
				.before("getDimensionPixelSize")
				.run(param -> {
					if (!enabled) return;
					if (!(param.thisObject instanceof Resources)) return;
					if (param.args.length == 0 || !(param.args[0] instanceof Integer)) return;
					if (isQsbHeight((Resources) param.thisObject, (int) param.args[0])) {
						param.setResult(0);
					}
				});
	}

	private static void captureQsb(Object hotseat) {
		try {
			Object qsb = getObjectField(hotseat, "mQsb");
			if (qsb instanceof View view) {
				if (qsbViewRef == null || qsbViewRef.get() != view) {
					qsbViewRef = new WeakReference<>(view);
				}
				view.setVisibility(enabled ? View.GONE : View.VISIBLE);
			}
		} catch (Throwable ignored) {
		}
	}

	private static boolean isQsbHeight(Resources resources, int id) {
		int cached;
		synchronized (dimensionVerdicts) {
			cached = dimensionVerdicts.get(id, UNKNOWN);
		}
		if (cached != UNKNOWN) return cached == ZERO;

		String name;
		try {
			name = resources.getResourceEntryName(id);
		} catch (Throwable t) {
			name = "";
		}
		boolean zero = QSB_HEIGHT.equals(name);
		synchronized (dimensionVerdicts) {
			dimensionVerdicts.put(id, zero ? ZERO : KEEP);
		}
		return zero;
	}
}
