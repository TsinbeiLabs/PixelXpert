package com.tsinbei.pixelxpert.xposed.modpacks.launcher;

import static com.tsinbei.pixelxpert.xposed.XPrefs.Xprefs;
import static de.robv.android.xposed.XposedHelpers.getIntField;
import static de.robv.android.xposed.XposedHelpers.getObjectField;
import static de.robv.android.xposed.XposedHelpers.setObjectField;

import android.content.Context;
import android.content.ComponentName;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Set;

import com.tsinbei.pixelxpert.xposed.XposedModPack;
import com.tsinbei.pixelxpert.xposed.annotations.LauncherModPack;
import com.tsinbei.pixelxpert.xposed.utils.SystemUtils;
import com.tsinbei.pixelxpert.xposed.utils.reflection.ReflectedClass;

import io.github.libxposed.api.XposedModuleInterface;

/** Visual Launcher options adapted from PixelLauncherEnhanced's GPL-3.0 implementations. */
@LauncherModPack
public class LauncherVisualMods extends XposedModPack {
	private boolean hideDesktopLabels;
	private boolean hideDrawerLabels;
	private boolean hideDesktopSearch;
	private boolean hideDrawerSearch;
	private boolean hideShortcutBadge;
	private boolean lockDesktop;
	private boolean hideAtAGlance;
	private boolean allowWallpaperZooming;
	private boolean quickLaunch;
	private boolean hideDrawerApps;
	private boolean searchHiddenApps;
	private Set<String> hiddenApps;
	private View desktopSearchBar;
	private View drawerSearchBar;

	public LauncherVisualMods(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... key) {
		if (Xprefs == null) return;
		hideDesktopLabels = Xprefs.getBoolean("LauncherHideDesktopLabels", false);
		hideDrawerLabels = Xprefs.getBoolean("LauncherHideDrawerLabels", false);
		hideDesktopSearch = Xprefs.getBoolean("LauncherHideDesktopSearch", false);
		hideDrawerSearch = Xprefs.getBoolean("LauncherHideDrawerSearch", false);
		hideShortcutBadge = Xprefs.getBoolean("LauncherHideShortcutBadge", false);
		lockDesktop = Xprefs.getBoolean("LauncherLockDesktop", false);
		hideAtAGlance = Xprefs.getBoolean("LauncherHideAtAGlance", false);
		allowWallpaperZooming = Xprefs.getBoolean("LauncherAllowWallpaperZooming", true);
		quickLaunch = Xprefs.getBoolean("LauncherQuickLaunch", false);
		hideDrawerApps = Xprefs.getBoolean("LauncherHideDrawerApps", false);
		searchHiddenApps = Xprefs.getBoolean("LauncherSearchHiddenApps", false);
		hiddenApps = Xprefs.getStringSet("LauncherHiddenApps", java.util.Collections.emptySet());
		if (key.length > 0 && (key[0].equals("LauncherHideDesktopLabels")
				|| key[0].equals("LauncherHideDrawerLabels")
				|| key[0].equals("LauncherHideShortcutBadge")
				|| key[0].equals("LauncherHideAtAGlance")
				|| key[0].equals("LauncherHideDrawerApps")
				|| key[0].equals("LauncherHiddenApps"))) {
			SystemUtils.killSelf();
		}
		updateSearchVisibility();
	}

	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam param) {
		hookIconLabels();
		hookDesktopSearch();
		hookDrawerSearch();
		hookShortcutBadge();
		hookDesktopLock();
		hookAtAGlance();
		hookWallpaperZoom();
		hookQuickLaunch();
		hookHiddenApps();
	}

	private void hookShortcutBadge() {
		try {
			ReflectedClass bubble = ReflectedClass.of("com.android.launcher3.BubbleTextView");
			bubble.afterConstruction().run(hook -> hideShortcutBadge(hook.thisObject));
			bubble.after("setHideBadge").run(hook -> hideShortcutBadge(hook.thisObject));
			ReflectedClass bitmapInfo = ReflectedClass.ofIfPossible("com.android.launcher3.icons.BitmapInfo");
			if (bitmapInfo.getClazz() != null) {
				bitmapInfo.after("newIcon").run(hook -> {
					if (!hideShortcutBadge || hook.getResult() == null) return;
					try { setObjectField(hook.getResult(), "badge", null); } catch (Throwable ignored) { }
					try { de.robv.android.xposed.XposedHelpers.callMethod(hook.getResult(), "updateFilter"); } catch (Throwable ignored) { }
				});
			}
		} catch (Throwable throwable) { log("LauncherVisualMods: shortcut badge hook unavailable", throwable); }
	}

	private void hideShortcutBadge(Object view) {
		if (!hideShortcutBadge) return;
		try { setObjectField(view, "mHideBadge", true); } catch (Throwable ignored) { }
	}

	private void hookDesktopLock() {
		try {
			ReflectedClass.of("com.android.launcher3.dragndrop.DragController")
				.before("onControllerInterceptTouchEvent").run(hook -> {
					if (!lockDesktop) return;
					try { de.robv.android.xposed.XposedHelpers.callMethod(hook.thisObject, "cancelDrag"); } catch (Throwable ignored) { }
					hook.setResult(false);
				});
			ReflectedClass.ofIfPossible("com.android.launcher3.widget.LauncherAppWidgetHostView")
				.before("onLongClick").run(hook -> { if (lockDesktop) hook.setResult(true); });
			ReflectedClass.ofIfPossible("com.android.launcher3.popup.SystemShortcut")
				.before("onClick").run(hook -> { if (lockDesktop) hook.setResult(null); });
		} catch (Throwable throwable) { log("LauncherVisualMods: desktop lock hook unavailable", throwable); }
	}

	private void hookAtAGlance() {
		try {
			ReflectedClass callbacks = ReflectedClass.ofIfPossible("com.android.launcher3.ModelCallbacks");
			if (callbacks.getClazz() == null) return;
			callbacks.afterConstruction().run(hook -> { if (hideAtAGlance) setObjectField(hook.thisObject, "isFirstPagePinnedItemEnabled", false); });
			callbacks.before("setIsFirstPagePinnedItemEnabled").run(hook -> { if (hideAtAGlance) hook.args[0] = false; });
			callbacks.before("getIsFirstPagePinnedItemEnabled").run(hook -> { if (hideAtAGlance) hook.setResult(false); });
		} catch (Throwable throwable) { log("LauncherVisualMods: At a Glance hook unavailable", throwable); }
	}

	private void hookWallpaperZoom() {
		try {
			ReflectedClass depth = ReflectedClass.ofIfPossible("com.android.quickstep.util.BaseDepthController");
			if (depth.getClazz() == null) depth = ReflectedClass.ofIfPossible("com.android.quickstep.util.BaseDepthControllerImpl");
			if (depth.getClazz() == null) return;
			depth.afterConstruction().run(hook -> {
				Object wallpaperManager = getObjectField(hook.thisObject, "mWallpaperManager");
				if (wallpaperManager == null) return;
				ReflectedClass.of(wallpaperManager.getClass()).before("setWallpaperZoomOut").run(zoomHook -> {
					if (!allowWallpaperZooming && zoomHook.thisObject == wallpaperManager && zoomHook.args.length > 1) zoomHook.args[1] = 1f;
				});
			});
		} catch (Throwable throwable) { log("LauncherVisualMods: wallpaper zoom hook unavailable", throwable); }
	}

	private void hookQuickLaunch() {
		try {
			ReflectedClass allApps = ReflectedClass.of("com.android.launcher3.allapps.ActivityAllAppsContainerView");
			allApps.after("onFinishInflate").run(hook -> attachQuickLaunch((ViewGroup) hook.thisObject));
		} catch (Throwable throwable) { log("LauncherVisualMods: quick launch hook unavailable", throwable); }
	}

	private void attachQuickLaunch(ViewGroup root) {
		EditText search = findEditText(root);
		if (search == null) return;
		search.setOnEditorActionListener((view, actionId, event) -> {
			boolean enter = actionId == EditorInfo.IME_ACTION_SEARCH || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN);
			if (!quickLaunch || !enter) return false;
			View result = findFirstClickable(root);
			if (result == null) return false;
			result.performClick();
			return true;
		});
	}

	private EditText findEditText(View view) {
		if (view instanceof EditText) return (EditText) view;
		if (!(view instanceof ViewGroup)) return null;
		ViewGroup group = (ViewGroup) view;
		for (int i = 0; i < group.getChildCount(); i++) { EditText found = findEditText(group.getChildAt(i)); if (found != null) return found; }
		return null;
	}

	private View findFirstClickable(ViewGroup root) {
		for (int i = 0; i < root.getChildCount(); i++) {
			View child = root.getChildAt(i);
			if (child.getClass().getName().contains("SearchRecyclerView") && child instanceof ViewGroup) {
				ViewGroup list = (ViewGroup) child;
				return list.getChildCount() == 0 ? null : list.getChildAt(0);
			}
			if (child instanceof ViewGroup) { View found = findFirstClickable((ViewGroup) child); if (found != null) return found; }
		}
		return null;
	}

	private void hookHiddenApps() {
		try {
			ReflectedClass alphabeticalApps = ReflectedClass.of("com.android.launcher3.allapps.AlphabeticalAppsList");
			alphabeticalApps.after("onAppsUpdated").run(hook -> {
				if (!hideDrawerApps || hiddenApps == null || hiddenApps.isEmpty()) return;
				Object value = getObjectField(hook.thisObject, "mAdapterItems");
				if (!(value instanceof ArrayList)) return;
				ArrayList<Object> items = new ArrayList<>((ArrayList<Object>) value);
				items.removeIf(item -> isHiddenItem(item));
				setObjectField(hook.thisObject, "mAdapterItems", items);
			});
			ReflectedClass search = ReflectedClass.ofIfPossible("com.android.launcher3.allapps.DefaultAppSearchAlgorithm");
			if (search.getClazz() == null) search = ReflectedClass.ofIfPossible("com.android.launcher3.allapps.search.DefaultAppSearchAlgorithm");
			if (search.getClazz() != null) {
				search.before("getTitleMatchResult").run(hook -> {
					if (!hideDrawerApps || searchHiddenApps || hiddenApps == null || hiddenApps.isEmpty()) return;
					for (int index = 0; index < hook.args.length; index++) {
						if (!(hook.args[index] instanceof java.util.List)) continue;
						ArrayList<Object> apps = new ArrayList<>((java.util.List<Object>) hook.args[index]);
						apps.removeIf(this::isHiddenAppInfo);
						hook.args[index] = apps;
						break;
					}
				});
			}
		} catch (Throwable throwable) { log("LauncherVisualMods: hidden apps hook unavailable", throwable); }
	}

	private boolean isHiddenItem(Object item) {
		try {
			Object info = getObjectField(item, "itemInfo");
			return isHiddenAppInfo(info);
		} catch (Throwable ignored) { return false; }
	}

	private boolean isHiddenAppInfo(Object info) {
		try {
			if (info == null) return false;
			Object component = getObjectField(info, "componentName");
			return component instanceof ComponentName && hiddenApps.contains(((ComponentName) component).getPackageName());
		} catch (Throwable ignored) { return false; }
	}

	private void hookIconLabels() {
		try {
			ReflectedClass bubbleTextView = ReflectedClass.of("com.android.launcher3.BubbleTextView");
			bubbleTextView.after("applyLabel").run(hook -> hideLabelIfNeeded((View) hook.thisObject));
			bubbleTextView.after("applyIconAndLabel").run(hook -> hideLabelIfNeeded((View) hook.thisObject));
		} catch (Throwable throwable) {
			log("LauncherVisualMods: icon label hook unavailable", throwable);
		}
	}

	private void hideLabelIfNeeded(View view) {
		try {
			int display = getIntField(view, "mDisplay");
			boolean desktop = display == 0 || display == 2 || display == 6 || display == 7;
			boolean drawer = display == 1 || display == 8 || display == 9;
			if ((desktop && hideDesktopLabels) || (drawer && hideDrawerLabels)) {
				((TextView) view).setText(null);
			}
		} catch (Throwable ignored) { }
	}

	private void hookDesktopSearch() {
		try {
			ReflectedClass hotseat = ReflectedClass.of("com.android.launcher3.Hotseat");
			hotseat.afterConstruction().run(hook -> captureDesktopSearch(hook.thisObject));
			hotseat.after("setInsets").run(hook -> captureDesktopSearch(hook.thisObject));
		} catch (Throwable throwable) {
			log("LauncherVisualMods: desktop search hook unavailable", throwable);
		}
	}

	private void captureDesktopSearch(Object hotseat) {
		try {
			Object qsb = getObjectField(hotseat, "mQsb");
			if (qsb instanceof View) desktopSearchBar = (View) qsb;
			updateSearchVisibility();
		} catch (Throwable ignored) { }
	}

	private void hookDrawerSearch() {
		try {
			ReflectedClass allApps = ReflectedClass.of("com.android.launcher3.allapps.ActivityAllAppsContainerView");
			allApps.after("onFinishInflate").run(hook -> {
				Object search = getObjectField(hook.thisObject, "mSearchContainer");
				if (search instanceof View) drawerSearchBar = (View) search;
				updateSearchVisibility();
			});
		} catch (Throwable throwable) {
			log("LauncherVisualMods: drawer search hook unavailable", throwable);
		}
	}

	private void updateSearchVisibility() {
		if (desktopSearchBar != null) desktopSearchBar.setVisibility(hideDesktopSearch ? View.GONE : View.VISIBLE);
		if (drawerSearchBar != null) drawerSearchBar.setVisibility(hideDrawerSearch ? View.GONE : View.VISIBLE);
	}
}
