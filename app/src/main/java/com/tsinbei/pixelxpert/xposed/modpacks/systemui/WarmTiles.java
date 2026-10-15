package com.tsinbei.pixelxpert.xposed.modpacks.systemui;

import static com.tsinbei.pixelxpert.xposed.XPrefs.Xprefs;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.findFieldIfExists;

import android.content.Context;

import java.lang.reflect.Field;

import io.github.libxposed.api.XposedModuleInterface;
import com.tsinbei.pixelxpert.xposed.XposedModPack;
import com.tsinbei.pixelxpert.xposed.annotations.SystemUIModPack;
import com.tsinbei.pixelxpert.xposed.utils.reflection.ReflectedClass;

/**
 * Raises the stock cap of 3 concurrently bound third-party Quick Settings tiles so more
 * custom tiles can be active at once.
 * Adapted from qs-boundless-tiles / PixelInjector "Warm Tiles" by hxreborn (GPL-3.0).
 */
@SuppressWarnings("RedundantThrows")
@SystemUIModPack
public class WarmTiles extends XposedModPack {
	private static final String TILE_SERVICES = "com.android.systemui.qs.external.TileServices";

	private static boolean enabled = false;
	private static Field maxBoundField = null;
	private static Object capValue = null;

	public WarmTiles(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {
		if (Xprefs == null) return;
		enabled = Xprefs.getBoolean("warmTilesEnabled", false);
	}

	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) throws Throwable {
		ReflectedClass tileServicesClass = ReflectedClass.ofIfPossible(TILE_SERVICES);
		Class<?> clazz = tileServicesClass.getClazz();
		if (clazz == null) return;

		maxBoundField = findFieldIfExists(clazz, "mMaxBound");
		if (maxBoundField == null) return;
		maxBoundField.setAccessible(true);
		capValue = (maxBoundField.getType() == byte.class || maxBoundField.getType() == Byte.class)
				? Byte.MAX_VALUE
				: Integer.MAX_VALUE;

		ReflectedClass.ReflectionConsumer raiseCap = param -> {
			if (!enabled) return;
			apply(param.thisObject);
		};

		// Raise the cap whenever the instance is created and before each bind-allowance pass
		tileServicesClass.afterConstruction().run(raiseCap);
		tileServicesClass.before("recalculateBindAllowance").run(raiseCap);
	}

	private static void apply(Object instance) {
		try {
			maxBoundField.set(instance, capValue);
		} catch (Throwable ignored) {
		}
	}
}
