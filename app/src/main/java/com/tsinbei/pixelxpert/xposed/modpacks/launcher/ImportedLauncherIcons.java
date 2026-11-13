/* Adapted from NativeComplements IconLayerModule (Apache-2.0).
 * Changes: use PixelXpert preferences and provider, preload normalized layers,
 * preserve profile badges, and refresh only changed package cache entries. */
package com.tsinbei.pixelxpert.xposed.modpacks.launcher;

import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageItemInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Process;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import com.tsinbei.pixelxpert.BuildConfig;
import com.tsinbei.pixelxpert.utils.LauncherIconLibrary;
import com.tsinbei.pixelxpert.utils.AospPerfectIconPack;
import com.tsinbei.pixelxpert.xposed.XposedModPack;
import com.tsinbei.pixelxpert.xposed.annotations.LauncherModPack;
import com.tsinbei.pixelxpert.xposed.utils.reflection.ReflectedClass;
import io.github.libxposed.api.XposedModuleInterface;
import static com.tsinbei.pixelxpert.xposed.XPrefs.Xprefs;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.callMethod;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.getObjectField;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.getStaticObjectField;

@LauncherModPack
public class ImportedLauncherIcons extends XposedModPack {
	private final java.util.concurrent.ExecutorService worker = Executors.newSingleThreadExecutor();
	private final AtomicInteger epoch = new AtomicInteger();
	private volatile AospPerfectIconPack.Catalog icons = new AospPerfectIconPack.Catalog(Map.of(), Map.of());
	private Class<?> appState;
	private record Layers(Bitmap background, Bitmap foreground, Bitmap monochrome) { }
	public ImportedLauncherIcons(Context context) { super(context); }

	@Override public void onPreferenceUpdated(String... keys) {
		if (keys.length > 0 && Arrays.stream(keys).noneMatch(Set.of("LauncherIconOverridesEnabled", "LauncherIconOriginals", AospPerfectIconPack.SOURCE_KEY, LauncherIconLibrary.GENERATION_KEY)::contains)) return;
		int request = epoch.incrementAndGet();
		boolean enabled = Xprefs.getBoolean("LauncherIconOverridesEnabled", false);
		String generation = Xprefs.getString(LauncherIconLibrary.GENERATION_KEY, "");
		String source = Xprefs.getString(AospPerfectIconPack.SOURCE_KEY, AospPerfectIconPack.IMPORTED);
		Set<String> excluded = new HashSet<>(Xprefs.getStringSet("LauncherIconOriginals", Set.of()));
		worker.execute(() -> load(request, enabled, source, generation, excluded));
	}

	@Override public void onPackageLoaded(XposedModuleInterface.PackageReadyParam param) {
		appState = ReflectedClass.of("com.android.launcher3.LauncherAppState").getClazz();
		ReflectedClass provider = ReflectedClass.of("com.android.launcher3.icons.IconProvider");
		provider.after("getIcon").run(hook -> {
			PackageItemInfo item = null; ApplicationInfo application = null;
			ComponentName component = null;
			for (Object arg : hook.args) {
				if (arg instanceof LauncherActivityInfo info) {
					if (!Process.myUserHandle().equals(info.getUser())) return;
					component = info.getComponentName(); application = info.getApplicationInfo();
				} else if (arg instanceof ActivityInfo info) {
					item = info; application = info.applicationInfo;
					component = new ComponentName(info.packageName, info.name);
				} else if (arg instanceof ApplicationInfo info) application = info;
				else if (arg instanceof PackageItemInfo info) item = info;
			}
			if (item == null) item = application;
			if (item == null || (application != null && !Process.myUserHandle().equals(android.os.UserHandle.getUserHandleForUid(application.uid)))) return;
			AospPerfectIconPack.Catalog catalog = icons;
			Drawable.ConstantState state = component == null ? null : catalog.components().get(component);
			if (state == null) state = catalog.packages().get(item.packageName);
			if (state != null) hook.setResult(state.newDrawable().mutate());
		});
		// Refresh again once the launcher singleton is available at startup.
		ReflectedClass.of("com.android.launcher3.LauncherAppState").afterConstruction().run(hook -> onPreferenceUpdated());
		IntentFilter packages = new IntentFilter();
		packages.addAction(Intent.ACTION_PACKAGE_ADDED);
		packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
		packages.addAction(Intent.ACTION_PACKAGE_REPLACED);
		packages.addDataScheme("package");
		mContext.registerReceiver(new BroadcastReceiver() {
			@Override public void onReceive(Context context, Intent intent) { onPreferenceUpdated(); }
		}, packages, Context.RECEIVER_NOT_EXPORTED);
	}

	private Drawable drawable(Layers layers) {
		return new AdaptiveIconDrawable(new BitmapDrawable(mContext.getResources(), layers.background),
				new BitmapDrawable(mContext.getResources(), layers.foreground),
				new BitmapDrawable(mContext.getResources(), layers.monochrome));
	}
	private void load(int request, boolean enabled, String source, String generation, Set<String> excluded) {
		Map<String, Drawable.ConstantState> next = new HashMap<>();
		Map<ComponentName, Drawable.ConstantState> components = new HashMap<>();
		try {
			if (enabled && AospPerfectIconPack.PACKAGE.equals(source)) {
				AospPerfectIconPack.Catalog pack = AospPerfectIconPack.load(mContext);
				pack.components().forEach((component, state) -> { if (!excluded.contains(component.getPackageName())) components.put(component, state); });
				pack.packages().forEach((pkg, state) -> { if (!excluded.contains(pkg)) next.put(pkg, state); });
			} else if (enabled && AospPerfectIconPack.IMPORTED.equals(source) && LauncherIconLibrary.validGeneration(generation)) {
				Uri authority = Uri.parse("content://" + BuildConfig.APPLICATION_ID + ".launcher-icons");
				Bundle catalog = mContext.getContentResolver().call(authority, "catalog", generation, null);
				if (catalog == null) throw new IllegalStateException("No icon catalog");
				ArrayList<String> packages = catalog.getStringArrayList("packages");
				if (packages != null) for (String pkg : packages) {
					if (request != epoch.get()) return;
					if (excluded.contains(pkg)) continue;
					try { mContext.getPackageManager().getApplicationInfo(pkg, 0); }
					catch (android.content.pm.PackageManager.NameNotFoundException ignored) { continue; }
					Bitmap background = image(authority, generation, pkg, 0), foreground = image(authority, generation, pkg, 1);
					Bitmap mask = Bitmap.createBitmap(foreground.getWidth(), foreground.getHeight(), Bitmap.Config.ARGB_8888);
					Paint paint = new Paint(); paint.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
					new Canvas(mask).drawBitmap(foreground, 0, 0, paint);
					next.put(pkg, drawable(new Layers(background, foreground, mask)).getConstantState());
				}
			}
		} catch (Exception failure) { log("ImportedLauncherIcons: load failed; restoring native icons", failure); next.clear(); components.clear(); }
		if (request != epoch.get()) return;
		AospPerfectIconPack.Catalog catalog = new AospPerfectIconPack.Catalog(Map.copyOf(components), Map.copyOf(next));
		Set<String> changed = icons.affectedPackages(); changed.addAll(catalog.affectedPackages());
		// Persisted launcher databases can contain overrides even when this
		// process starts after the library was disabled or removed.
		changed.addAll(Xprefs.getStringSet(LauncherIconLibrary.RESTORE_KEY, Set.of()));
		if (enabled && AospPerfectIconPack.PACKAGE.equals(source) && !catalog.affectedPackages().isEmpty()) {
			try {
				Uri authority = Uri.parse("content://" + BuildConfig.APPLICATION_ID + ".launcher-icons");
				Bundle restore = new Bundle(); restore.putStringArrayList("packages", new ArrayList<>(catalog.affectedPackages()));
				mContext.getContentResolver().call(authority, "remember", null, restore);
			} catch (Exception failure) { log("ImportedLauncherIcons: cannot persist restoration targets", failure); return; }
		}
		if (request != epoch.get()) return;
		icons = catalog;
		refresh(changed);
	}
	private Bitmap image(Uri authority, String generation, String pkg, int layer) throws Exception {
		Uri uri = authority.buildUpon().appendPath(generation).appendPath(pkg).appendPath(String.valueOf(layer)).build();
		try (InputStream stream = mContext.getContentResolver().openInputStream(uri)) {
			Bitmap result = BitmapFactory.decodeStream(stream);
			if (result == null) throw new IllegalStateException("Invalid imported image");
			return result;
		}
	}
	private void refresh(Set<String> packages) {
		if (appState == null || packages.isEmpty()) return;
		try {
			Set<String> installed = new HashSet<>();
			for (ApplicationInfo app : mContext.getPackageManager().getInstalledApplications(0)) installed.add(app.packageName);
			packages.retainAll(installed);
			if (packages.isEmpty()) return;
			Object state = callMethod(getStaticObjectField(appState, "INSTANCE"), "get", mContext);
			Object cache = getObjectField(state, "iconCache"), model = getObjectField(state, "model");
			Handler cacheWorker = getObjectField(cache, "workerHandler");
			Object callbacks = callMethod(model, "newModelCallbacks");
			cacheWorker.post(() -> {
				for (String pkg : packages) try {
					callMethod(cache, "removeIconsForPkg", Process.myUserHandle(), pkg);
					callMethod(callbacks, "onPackageChanged", pkg, Process.myUserHandle());
				} catch (Throwable failure) { log("ImportedLauncherIcons: refresh failed for " + pkg, failure); }
			});
		} catch (Throwable failure) { log("ImportedLauncherIcons: cache refresh unavailable", failure); }
	}
}
