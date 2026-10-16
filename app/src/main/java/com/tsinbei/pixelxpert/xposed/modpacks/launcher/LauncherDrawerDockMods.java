package com.tsinbei.pixelxpert.xposed.modpacks.launcher;

import static com.tsinbei.pixelxpert.xposed.XPrefs.Xprefs;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.callMethod;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.getAdditionalInstanceField;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.getBooleanField;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.getIntField;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.getObjectField;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.getStaticObjectField;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.setAdditionalInstanceField;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.setObjectField;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewParent;

import java.lang.reflect.Constructor;
import java.util.Collections;

import com.tsinbei.pixelxpert.xposed.XposedModPack;
import com.tsinbei.pixelxpert.xposed.annotations.LauncherModPack;
import com.tsinbei.pixelxpert.xposed.utils.SystemUtils;
import com.tsinbei.pixelxpert.xposed.utils.reflection.ReflectedClass;

import io.github.libxposed.api.XposedModuleInterface;

/** Drawer, dock and opacity options adapted from PixelLauncherEnhanced's GPL-3.0 implementations. */
@LauncherModPack
public class LauncherDrawerDockMods extends XposedModPack {

	private static final String IS_DRAWER_SCROLLER = "pxIsDrawerScroller";
	private static final String ORIGINAL_BOTTOM_SPACE = "pxOriginalHotseatBottomSpace";
	private static final String PREVIEW_ORIGINAL_COLOR = "pxPreviewOriginalColor";

	private static final int SWIPE_UP_NOTHING = 0;
	private static final int SWIPE_UP_SEARCH = 1;

	private boolean hideDrawerScrollbar;
	private boolean disableDock;
	private boolean disableAppDrawer;
	private int noDrawerSwipeAction;
	private int dockTopSpacing;
	private int dockBottomSpacing;
	private int drawerOpacityAlpha;
	private int recentsOpacityAlpha;
	private int searchBarOpacityAlpha;
	private int folderOpacityAlpha;

	private Class<?> drawerClass;
	private Class<?> launcherStateClass;
	private Class<?> scrimColorsClass;

	private final ThreadLocal<Boolean> inProfileCopy = new ThreadLocal<>();

	public LauncherDrawerDockMods(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... key) {
		if (Xprefs == null) return;
		hideDrawerScrollbar = Xprefs.getBoolean("LauncherHideDrawerScrollbar", false);
		disableDock = Xprefs.getBoolean("LauncherDisableDock", false);
		disableAppDrawer = Xprefs.getBoolean("LauncherDisableAppDrawer", false);
		noDrawerSwipeAction = readSwipeAction();
		dockTopSpacing = Xprefs.getSliderInt("LauncherDockTopSpacing", 0);
		dockBottomSpacing = Xprefs.getSliderInt("LauncherDockBottomSpacing", 0);
		drawerOpacityAlpha = Xprefs.getSliderInt("LauncherDrawerOpacity", 100) * 255 / 100;
		recentsOpacityAlpha = Xprefs.getSliderInt("LauncherRecentsOpacity", 100) * 255 / 100;
		searchBarOpacityAlpha = Xprefs.getSliderInt("LauncherSearchBarOpacity", 100) * 255 / 100;
		folderOpacityAlpha = Xprefs.getSliderInt("LauncherFolderOpacity", 100) * 255 / 100;
		if (key.length > 0 && (key[0].equals("LauncherHideDrawerScrollbar")
				|| key[0].equals("LauncherDisableDock")
				|| key[0].equals("LauncherDisableAppDrawer")
				|| key[0].equals("LauncherNoDrawerSwipeAction")
				|| key[0].equals("LauncherDockTopSpacing")
				|| key[0].equals("LauncherDockBottomSpacing")
				|| key[0].equals("LauncherFolderOpacity"))) {
			SystemUtils.killSelf();
		}
	}

	private int readSwipeAction() {
		try {
			return Integer.parseInt(Xprefs.getString("LauncherNoDrawerSwipeAction", "0"));
		} catch (Throwable ignored) {
			return SWIPE_UP_NOTHING;
		}
	}

	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam param) {
		drawerClass = ReflectedClass.ofIfPossible("com.android.launcher3.allapps.ActivityAllAppsContainerView").getClazz();
		launcherStateClass = ReflectedClass.ofIfPossible("com.android.launcher3.LauncherState").getClazz();
		scrimColorsClass = ReflectedClass.ofIfPossible("com.android.launcher3.views.ScrimColors").getClazz();
		hookDrawerScrollbar();
		hookDisableDock();
		hookNoDrawer();
		hookDockSpacing();
		hookOpacities();
	}

	private void hookDrawerScrollbar() {
		try {
			ReflectedClass fastScroller = ReflectedClass.ofIfPossible("com.android.launcher3.views.RecyclerViewFastScroller");
			if (fastScroller.getClazz() == null) return;
			fastScroller.before("onDraw").run(hook -> {
				if (!hideDrawerScrollbar) return;
				View scroller = (View) hook.thisObject;
				if (!isDrawerScroller(scroller)) return;
				clearGestureExclusion(scroller);
				hook.setResult(null);
			});
			fastScroller.before("handleTouchEvent").run(hook -> {
				if (hideDrawerScrollbar && hook.thisObject instanceof View && isDrawerScroller((View) hook.thisObject)) hook.setResult(false);
			});
			fastScroller.before("isHitInParent").run(hook -> {
				if (hideDrawerScrollbar && hook.thisObject instanceof View && isDrawerScroller((View) hook.thisObject)) hook.setResult(false);
			});
			fastScroller.before("shouldBlockIntercept").run(hook -> {
				if (hideDrawerScrollbar && hook.thisObject instanceof View && isDrawerScroller((View) hook.thisObject)) hook.setResult(false);
			});
		} catch (Throwable throwable) {
			log("LauncherDrawerDockMods: drawer scrollbar hook unavailable", throwable);
		}
	}

	private boolean isDrawerScroller(View scroller) {
		Object cached = getAdditionalInstanceField(scroller, IS_DRAWER_SCROLLER);
		if (cached instanceof Boolean) return (Boolean) cached;
		if (!scroller.isAttachedToWindow()) return false;
		ViewParent parent = scroller.getParent();
		boolean isDrawer = false;
		while (parent != null) {
			if ((drawerClass != null && drawerClass.isInstance(parent)) || parent.getClass().getName().endsWith("AllAppsContainerView")) {
				isDrawer = true;
				break;
			}
			parent = parent.getParent();
		}
		setAdditionalInstanceField(scroller, IS_DRAWER_SCROLLER, isDrawer);
		return isDrawer;
	}

	private void clearGestureExclusion(View scroller) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !scroller.getSystemGestureExclusionRects().isEmpty()) {
			scroller.setSystemGestureExclusionRects(Collections.emptyList());
		}
	}

	private void hookDisableDock() {
		try {
			ReflectedClass hotseat = ReflectedClass.ofIfPossible("com.android.launcher3.Hotseat");
			if (hotseat.getClazz() != null) {
				hotseat.before("onInterceptTouchEvent").run(hook -> { if (disableDock) hook.setResult(true); });
				hotseat.before("onTouchEvent").run(hook -> { if (disableDock) hook.setResult(false); });
				hotseat.before("isValidDropTarget").run(hook -> { if (disableDock) hook.setResult(false); });
			}
			ReflectedClass workspace = ReflectedClass.ofIfPossible("com.android.launcher3.Workspace");
			if (workspace.getClazz() != null) {
				workspace.before("shouldUseHotseatAsDropLayout").run(hook -> { if (disableDock) hook.setResult(false); });
			}
			for (String name : new String[] {
					"com.android.launcher3.util.HybridHotseatOrganizer",
					"com.android.launcher3.hybridhotseat.HotseatPredictionController" }) {
				ReflectedClass cls = ReflectedClass.ofIfPossible(name);
				if (cls.getClazz() != null) {
					cls.before("fillGapsWithPrediction").run(hook -> { if (disableDock) hook.setResult(null); });
				}
			}
		} catch (Throwable throwable) {
			log("LauncherDrawerDockMods: disable dock hook unavailable", throwable);
		}
	}

	private void hookNoDrawer() {
		try {
			ReflectedClass sessionCommitReceiver = ReflectedClass.ofIfPossible("com.android.launcher3.SessionCommitReceiver");
			if (sessionCommitReceiver.getClazz() != null) {
				sessionCommitReceiver.before("isEnabled").run(hook -> { if (disableAppDrawer) hook.setResult(false); });
			}

			final Object normalState = getStaticLauncherState("NORMAL");
			final Object allAppsState = getStaticLauncherState("ALL_APPS");

			for (String name : new String[] {
					"com.android.launcher3.uioverrides.touchcontrollers.PortraitStatesTouchController",
					"com.android.launcher3.uioverrides.touchcontrollers.NoButtonNavbarToOverviewTouchController" }) {
				ReflectedClass cls = ReflectedClass.ofIfPossible(name);
				if (cls.getClazz() == null) continue;
				cls.after("getTargetState").run(hook -> {
					if (!disableAppDrawer || noDrawerSwipeAction != SWIPE_UP_NOTHING) return;
					if (normalState == null || allAppsState == null) return;
					if (hook.args.length > 0 && hook.getArg(0) == normalState && hook.getResult() == allAppsState) {
						hook.setResult(normalState);
					}
				});
			}

			ReflectedClass quickstepLauncher = ReflectedClass.ofIfPossible("com.android.launcher3.uioverrides.QuickstepLauncher");
			if (quickstepLauncher.getClazz() != null) {
				quickstepLauncher.after("onStateSetEnd").run(hook -> {
					if (!disableAppDrawer || noDrawerSwipeAction != SWIPE_UP_SEARCH) return;
					if (allAppsState == null || hook.args.length == 0 || hook.getArg(0) != allAppsState) return;
					showAllAppsKeyboard(hook.thisObject);
				});
			}
		} catch (Throwable throwable) {
			log("LauncherDrawerDockMods: no drawer hook unavailable", throwable);
		}
	}

	private Object getStaticLauncherState(String name) {
		if (launcherStateClass == null) return null;
		try {
			return getStaticObjectField(launcherStateClass, name);
		} catch (Throwable ignored) {
			return null;
		}
	}

	private void showAllAppsKeyboard(Object launcher) {
		try {
			Object appsView = callMethod(launcher, "getAppsView");
			Object searchUiManager = getObjectField(appsView, "mSearchUiManager");
			Object editText = callMethod(searchUiManager, "getEditText");
			callMethod(editText, "showKeyboard");
		} catch (Throwable ignored) { }
	}

	private void hookDockSpacing() {
		try {
			ReflectedClass hotseatProfile = ReflectedClass.ofIfPossible("com.android.launcher3.deviceprofile.HotseatProfile");
			if (hotseatProfile.getClazz() != null) {
				hotseatProfile.before("copy").run(hook -> inProfileCopy.set(true));
				hotseatProfile.after("copy").run(hook -> inProfileCopy.remove());
				try {
					hotseatProfile.before("copy$default").run(hook -> inProfileCopy.set(true));
					hotseatProfile.after("copy$default").run(hook -> inProfileCopy.remove());
				} catch (Throwable ignored) { }
				hotseatProfile.afterConstruction().run(hook -> {
					if (Boolean.TRUE.equals(inProfileCopy.get())) return;
					adjustHotseatSize(hook.thisObject, "barSizePx", "barBottomSpacePx", "qsbSpace", "qsbVisualHeight");
				});
			}

			ReflectedClass deviceProfile = ReflectedClass.ofIfPossible("com.android.launcher3.DeviceProfile");
			if (deviceProfile.getClazz() != null) {
				deviceProfile.after("updateHotseatSizes").run(hook ->
						adjustHotseatSize(hook.thisObject, "hotseatBarSizePx", "hotseatBarBottomSpacePx", "hotseatQsbSpace", "hotseatQsbVisualHeight"));
			}

			ReflectedClass workspace = ReflectedClass.ofIfPossible("com.android.launcher3.Workspace");
			if (workspace.getClazz() != null) {
				workspace.after("setInsets").run(hook -> {
					if (dockTopSpacing <= 0) return;
					View ws = (View) hook.thisObject;
					Rect padding = getWorkspacePadding(ws);
					if (padding == null) return;
					int bottom = Math.max(0, padding.bottom + toPx(dockTopSpacing));
					ws.setPadding(padding.left, padding.top, padding.right, bottom);
				});
			}
		} catch (Throwable throwable) {
			log("LauncherDrawerDockMods: dock spacing hook unavailable", throwable);
		}
	}

	private void adjustHotseatSize(Object profile, String barSizeField, String bottomSpaceField, String qsbSpaceField, String qsbVisualHeightField) {
		try {
			int barSize = getIntField(profile, barSizeField);
			int bottomSpace = getIntField(profile, bottomSpaceField);
			Object cached = getAdditionalInstanceField(profile, ORIGINAL_BOTTOM_SPACE);
			int originalBottomSpace;
			if (cached instanceof Integer) {
				originalBottomSpace = (Integer) cached;
			} else {
				originalBottomSpace = bottomSpace;
				setAdditionalInstanceField(profile, ORIGINAL_BOTTOM_SPACE, bottomSpace);
			}
			int targetBottomSpace = Math.max(0, originalBottomSpace + toPx(dockBottomSpacing));
			int delta = targetBottomSpace - bottomSpace;
			bottomSpace += delta;
			barSize += delta;
			setObjectField(profile, bottomSpaceField, bottomSpace);
			setObjectField(profile, barSizeField, Math.max(0, barSize));
		} catch (Throwable ignored) { }
	}

	private Rect getWorkspacePadding(View workspace) {
		try {
			Object launcher = getObjectField(workspace, "mLauncher");
			Object grid = null;
			try {
				grid = callMethod(launcher, "getDeviceProfile");
			} catch (Throwable ignored) { }
			if (grid == null) {
				Object deviceProfileRef = getObjectField(launcher, "deviceProfileRef");
				grid = getObjectField(deviceProfileRef, "value");
			}
			Object padding = null;
			try {
				padding = getObjectField(grid, "workspacePadding");
			} catch (Throwable ignored) { }
			if (padding == null) {
				Object profile = null;
				try {
					profile = getObjectField(grid, "mWorkspaceProfile");
				} catch (Throwable ignored) { }
				if (profile == null) profile = getObjectField(grid, "workspaceProfile");
				padding = getObjectField(profile, "workspacePadding");
			}
			return (Rect) padding;
		} catch (Throwable ignored) {
			return null;
		}
	}

	private int toPx(int dp) {
		return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, mContext.getResources().getDisplayMetrics());
	}

	private void hookOpacities() {
		hookDrawerOpacity();
		hookRecentsOpacity();
		hookSearchBarOpacity();
		hookFolderOpacity();
	}

	private void hookDrawerOpacity() {
		try {
			ReflectedClass allApps = ReflectedClass.ofIfPossible("com.android.launcher3.allapps.ActivityAllAppsContainerView");
			if (allApps.getClazz() != null) {
				allApps.afterConstruction().run(hook -> applyScrimColorField(hook.thisObject));
				allApps.after("updateHeaderScroll").run(hook -> {
					if (drawerOpacityAlpha == 255) return;
					try {
						setObjectField(hook.thisObject, "mHeaderColor", Color.TRANSPARENT);
					} catch (Throwable ignored) { }
					try {
						callMethod(hook.thisObject, "invalidateHeader");
					} catch (Throwable ignored) { }
				});
				for (String name : new String[] { "getHeaderColor", "getScrimColor", "getBackgroundColor", "getBottomSheetBackgroundColor" }) {
					allApps.after(name).run(hook -> hook.setResult(applyScrimAlpha(hook.getResult(), drawerOpacityAlpha)));
				}
			}

			ReflectedClass allAppsState = ReflectedClass.ofIfPossible("com.android.launcher3.uioverrides.states.AllAppsState");
			if (allAppsState.getClazz() != null) {
				allAppsState.after("getWorkspaceScrimColor").run(hook -> {
					if (hook.args.length > 0 && isTablet(hook.getArg(0))) return;
					hook.setResult(applyScrimAlpha(hook.getResult(), drawerOpacityAlpha));
				});
			}

			ReflectedClass taskbar = ReflectedClass.ofIfPossible("com.android.launcher3.taskbar.allapps.TaskbarAllAppsSlideInView");
			if (taskbar.getClazz() != null) {
				taskbar.after("getScrimColor").run(hook -> hook.setResult(applyScrimAlpha(hook.getResult(), drawerOpacityAlpha)));
			}
		} catch (Throwable throwable) {
			log("LauncherDrawerDockMods: drawer opacity hook unavailable", throwable);
		}
	}

	private void applyScrimColorField(Object allApps) {
		Integer scrimColor;
		try {
			scrimColor = getObjectField(allApps, "mScrimColor");
		} catch (Throwable ignored) {
			return;
		}
		if (scrimColor != null) {
			setObjectField(allApps, "mScrimColor", withAlpha(scrimColor, drawerOpacityAlpha));
		}
	}

	private void hookRecentsOpacity() {
		try {
			for (String name : new String[] {
					"com.android.launcher3.uioverrides.states.OverviewState",
					"com.android.launcher3.uioverrides.states.QuickSwitchState" }) {
				ReflectedClass cls = ReflectedClass.ofIfPossible(name);
				if (cls.getClazz() == null) continue;
				cls.after("getWorkspaceScrimColor").run(hook -> {
					Object result = hook.getResult();
					if (name.endsWith("QuickSwitchState")) {
						if (hook.args.length > 0 && isTaskbarPresent(hook.getArg(0))) return;
						if (result instanceof Integer && (Integer) result == Color.TRANSPARENT) return;
					}
					hook.setResult(applyScrimAlpha(result, recentsOpacityAlpha));
				});
			}

			ReflectedClass recentsState = ReflectedClass.ofIfPossible("com.android.quickstep.fallback.RecentsState");
			if (recentsState.getClazz() != null) {
				recentsState.after("getScrimColor").run(hook -> hook.setResult(applyScrimAlpha(hook.getResult(), recentsOpacityAlpha)));
			}

			for (String name : new String[] {
					"com.android.launcher3.states.HintState",
					"com.android.launcher3.uioverrides.states.HintState" }) {
				ReflectedClass cls = ReflectedClass.ofIfPossible(name);
				if (cls.getClazz() == null) continue;
				cls.after("getWorkspaceScrimColor").run(hook -> hook.setResult(applyScrimAlpha(hook.getResult(), recentsOpacityAlpha)));
			}
		} catch (Throwable throwable) {
			log("LauncherDrawerDockMods: recents opacity hook unavailable", throwable);
		}
	}

	private void hookSearchBarOpacity() {
		try {
			ReflectedClass hotseat = ReflectedClass.ofIfPossible("com.android.launcher3.Hotseat");
			if (hotseat.getClazz() == null) return;
			hotseat.afterConstruction().run(hook -> captureSearchBar(hook.thisObject));
			hotseat.after("setInsets").run(hook -> captureSearchBar(hook.thisObject));
		} catch (Throwable throwable) {
			log("LauncherDrawerDockMods: search bar opacity hook unavailable", throwable);
		}
	}

	private void captureSearchBar(Object hotseat) {
		try {
			Object qsb = getObjectField(hotseat, "mQsb");
			if (qsb instanceof View && ((View) qsb).getBackground() != null) {
				((View) qsb).getBackground().setAlpha(searchBarOpacityAlpha);
			}
		} catch (Throwable ignored) { }
	}

	private void hookFolderOpacity() {
		try {
			ReflectedClass folder = ReflectedClass.ofIfPossible("com.android.launcher3.folder.Folder");
			if (folder.getClazz() != null) {
				folder.after("onFinishInflate").run(hook -> applyFolderBackgroundAlpha(hook.thisObject));
				folder.after("animateOpen").run(hook -> applyFolderBackgroundAlpha(hook.thisObject));
			}

			ReflectedClass preview = ReflectedClass.ofIfPossible("com.android.launcher3.folder.PreviewBackground");
			if (preview.getClazz() != null) {
				preview.before("drawBackground").run(hook -> {
					Integer color;
					try {
						color = getObjectField(hook.thisObject, "mBgColor");
					} catch (Throwable ignored) {
						return;
					}
					if (color == null) return;
					setAdditionalInstanceField(hook.thisObject, PREVIEW_ORIGINAL_COLOR, color);
					setObjectField(hook.thisObject, "mBgColor", withAlpha(color, folderOpacityAlpha));
				});
				preview.after("drawBackground").run(hook -> {
					Object original = getAdditionalInstanceField(hook.thisObject, PREVIEW_ORIGINAL_COLOR);
					if (original instanceof Integer) {
						setObjectField(hook.thisObject, "mBgColor", original);
						setAdditionalInstanceField(hook.thisObject, PREVIEW_ORIGINAL_COLOR, null);
					}
				});
			}
		} catch (Throwable throwable) {
			log("LauncherDrawerDockMods: folder opacity hook unavailable", throwable);
		}
	}

	private void applyFolderBackgroundAlpha(Object folder) {
		try {
			Object background = getObjectField(folder, "mBackground");
			if (background instanceof GradientDrawable) {
				((GradientDrawable) background).setAlpha(folderOpacityAlpha);
			}
		} catch (Throwable ignored) { }
	}

	private Object applyScrimAlpha(Object result, int alpha) {
		if (result instanceof Integer) return withAlpha((Integer) result, alpha);
		if (scrimColorsClass != null && scrimColorsClass.isInstance(result)) {
			try {
				int bg = readColor(result, "backgroundColor", "getBackgroundColor");
				int fg = readColor(result, "foregroundColor", "getForegroundColor");
				Constructor<?> ctor = scrimColorsClass.getDeclaredConstructor(int.class, int.class);
				ctor.setAccessible(true);
				return ctor.newInstance(withAlpha(bg, alpha), fg);
			} catch (Throwable ignored) { }
		}
		return result;
	}

	private int readColor(Object obj, String field, String getter) {
		try {
			return (Integer) callMethod(obj, getter);
		} catch (Throwable ignored) {
			return getIntField(obj, field);
		}
	}

	private boolean isTablet(Object launcher) {
		try {
			Object deviceProfile = callMethod(launcher, "getDeviceProfile");
			Boolean isTablet = null;
			try {
				isTablet = getObjectField(deviceProfile, "isTablet");
			} catch (Throwable ignored) { }
			if (isTablet == null) {
				Object props = null;
				try {
					props = getObjectField(deviceProfile, "mDeviceProperties");
				} catch (Throwable ignored) { }
				if (props == null) props = getObjectField(deviceProfile, "deviceProperties");
				isTablet = getObjectField(props, "isTablet");
			}
			return Boolean.TRUE.equals(isTablet);
		} catch (Throwable ignored) {
			return false;
		}
	}

	private boolean isTaskbarPresent(Object launcher) {
		try {
			Object deviceProfile = callMethod(launcher, "getDeviceProfile");
			try {
				Boolean present = callMethod(deviceProfile, "isTaskbarPresentInApps");
				if (present != null) return present;
			} catch (Throwable ignored) { }
			return getBooleanField(deviceProfile, "isTaskbarPresentInApps");
		} catch (Throwable ignored) {
			return false;
		}
	}

	private static int withAlpha(int color, int alpha) {
		return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
	}
}
