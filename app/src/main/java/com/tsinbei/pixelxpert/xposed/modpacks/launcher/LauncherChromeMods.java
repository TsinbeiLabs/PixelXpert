package com.tsinbei.pixelxpert.xposed.modpacks.launcher;

import static com.tsinbei.pixelxpert.xposed.XPrefs.Xprefs;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.callMethod;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.findFieldIfExists;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.getAdditionalInstanceField;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.getObjectField;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.getStaticObjectField;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.setAdditionalInstanceField;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.setBooleanField;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import com.tsinbei.pixelxpert.xposed.XposedModPack;
import com.tsinbei.pixelxpert.xposed.annotations.LauncherModPack;
import com.tsinbei.pixelxpert.xposed.utils.SystemUtils;
import com.tsinbei.pixelxpert.xposed.utils.reflection.ReflectedClass;

import io.github.libxposed.api.XposedModuleInterface;

/**
 * Launcher chrome options (status bar, top shadow, page indicator, wallpaper dimming)
 * adapted from PixelLauncherEnhanced's GPL-3.0 implementations.
 */
@LauncherModPack
public class LauncherChromeMods extends XposedModPack {
	private static final String LISTENER_KEY = "pxHideStatusbarStateListener";

	private static final int UI_STATE_BASE_WINDOW = 0;
	private static final int FLAG_LIGHT_NAV = 1 << 0;
	private static final int FLAG_DARK_NAV = 1 << 1;
	private static final int FLAG_LIGHT_STATUS = 1 << 2;
	private static final int FLAG_DARK_STATUS = 1 << 3;
	private static final int NAV_FLAGS = FLAG_LIGHT_NAV | FLAG_DARK_NAV;
	private static final int STOCK_LIGHT_FLAGS = FLAG_LIGHT_STATUS | FLAG_LIGHT_NAV;
	private static final int STOCK_DARK_FLAGS = FLAG_DARK_STATUS | FLAG_DARK_NAV;

	private static final List<String> RESTART_KEYS = Arrays.asList(
			"LauncherHideTopShadow",
			"LauncherDarkStatusbar",
			"LauncherDarkPageIndicator",
			"LauncherHidePageIndicator",
			"LauncherPreventWallpaperDimRestart");

	private static final String[] STATUSBAR_ACTIVITY_CLASSES = {
			"com.android.launcher3.Launcher",
			"com.android.launcher3.uioverrides.QuickstepLauncher",
			"com.android.quickstep.RecentsActivity"};

	private static final String[] INDICATOR_CLASSES = {
			"com.android.launcher3.pageindicators.PageIndicatorDots",
			"com.android.launcher3.pageindicators.PageIndicatorDotsWithArrows",
			"com.android.launcher3.pageindicators.WorkspacePageIndicator"};

	private static final String[] SCRIM_MASK_FIELDS = {"mTopMaskBitmap", "mTopScrim", "mBottomMask", "mBottomMaskBitmap"};

	private static final int INDICATOR_PARENT_LOOKUPS = 5;

	private boolean hideStatusbar;
	private boolean darkStatusbar;
	private boolean hideTopShadow;
	private boolean darkPageIndicator;
	private boolean hidePageIndicator;
	private boolean preventWallpaperDimRestart;

	private final Handler mainHandler = new Handler(Looper.getMainLooper());
	private WeakReference<Activity> launcherRef;
	private Object overviewState;

	private final Set<Activity> launcherActivities = Collections.newSetFromMap(new WeakHashMap<>());

	private Object scrimInstance;
	private final WeakHashMap<Object, Object> scrimStandIns = new WeakHashMap<>();

	private WeakReference<View> pageIndicatorRef;
	private int pageIndicatorId = 0;

	public LauncherChromeMods(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {
		if (Xprefs == null) return;
		hideStatusbar = Xprefs.getBoolean("LauncherHideStatusbar", false);
		darkStatusbar = Xprefs.getBoolean("LauncherDarkStatusbar", false);
		hideTopShadow = Xprefs.getBoolean("LauncherHideTopShadow", false);
		darkPageIndicator = Xprefs.getBoolean("LauncherDarkPageIndicator", false);
		hidePageIndicator = Xprefs.getBoolean("LauncherHidePageIndicator", false);
		preventWallpaperDimRestart = Xprefs.getBoolean("LauncherPreventWallpaperDimRestart", false);

		if (Key.length == 0) return;
		if (RESTART_KEYS.contains(Key[0])) {
			SystemUtils.killSelf();
			return;
		}
		// Status bar visibility can be toggled live, no launcher restart needed
		if ("LauncherHideStatusbar".equals(Key[0])) applyStatusbarVisibility();
	}

	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) throws Throwable {
		captureOverviewState();
		hookHideStatusbar();
		hookDarkStatusbar();
		hookTopShadow();
		hookPageIndicator();
		hookWallpaperDim();
	}

	// ---------------------------------------------------------------- hide statusbar

	private void captureOverviewState() {
		try {
			ReflectedClass launcherState = ReflectedClass.ofIfPossible("com.android.launcher3.LauncherState");
			if (launcherState.getClazz() != null) {
				overviewState = getStaticObjectField(launcherState.getClazz(), "OVERVIEW");
			}
		} catch (Throwable throwable) {
			log("LauncherChromeMods: LauncherState.OVERVIEW unavailable", throwable);
		}
	}

	private void hookHideStatusbar() {
		try {
			ReflectedClass quickstepLauncher = ReflectedClass.ofIfPossible("com.android.launcher3.uioverrides.QuickstepLauncher");
			if (quickstepLauncher.getClazz() == null) return;
			quickstepLauncher.after("onCreate").run(param -> {
				try {
					Activity launcherActivity = (Activity) param.thisObject;
					launcherRef = new WeakReference<>(launcherActivity);
					Object listener = createStateListenerProxy(launcherActivity);
					Object stateManager = callMethod(launcherActivity, "getStateManager");
					if (listener != null && stateManager != null) {
						setAdditionalInstanceField(launcherActivity, LISTENER_KEY, listener);
						callMethod(stateManager, "addStateListener", listener);
					}
					if (hideStatusbar) hideStatusBar(launcherActivity);
				} catch (Throwable throwable) {
					log("LauncherChromeMods: launcher onCreate hook failed", throwable);
				}
			});
			quickstepLauncher.after("onDestroy").run(param -> {
				try {
					Object listener = getAdditionalInstanceField(param.thisObject, LISTENER_KEY);
					if (listener == null) return;
					Object stateManager = callMethod(param.thisObject, "getStateManager");
					if (stateManager != null) callMethod(stateManager, "removeStateListener", listener);
				} catch (Throwable ignored) {
				}
			});
		} catch (Throwable throwable) {
			log("LauncherChromeMods: hide statusbar hooks unavailable", throwable);
		}
	}

	/** Dynamic proxy of StateManager$StateListener: reveals the status bar while entering overview. */
	private Object createStateListenerProxy(Activity launcherActivity) {
		try {
			Class<?> listenerInterface = ReflectedClass
					.ofIfPossible("com.android.launcher3.statemanager.StateManager$StateListener")
					.getClazz();
			if (listenerInterface == null) return null;
			return Proxy.newProxyInstance(listenerInterface.getClassLoader(), new Class<?>[]{listenerInterface},
					(proxy, method, args) -> {
						String name = method.getName();
						Object state = args != null && args.length > 0 ? args[0] : null;
						switch (name) {
							case "onStateTransitionStart":
								if (hideStatusbar && state == overviewState) showStatusBar(launcherActivity);
								break;
							case "onStateTransitionComplete":
								if (hideStatusbar && state != overviewState) hideStatusBar(launcherActivity);
								break;
							case "equals":
								return proxy == state;
							case "hashCode":
								return System.identityHashCode(proxy);
							case "toString":
								return "LauncherChromeModsStateListener";
						}
						return null;
					});
		} catch (Throwable throwable) {
			log("LauncherChromeMods: state listener proxy unavailable", throwable);
			return null;
		}
	}

	private void applyStatusbarVisibility() {
		mainHandler.post(() -> {
			try {
				Activity launcher = launcherRef == null ? null : launcherRef.get();
				if (launcher == null || launcher.isDestroyed()) return;
				if (hideStatusbar && !isInOverview(launcher)) hideStatusBar(launcher);
				else showStatusBar(launcher);
			} catch (Throwable throwable) {
				log("LauncherChromeMods: statusbar visibility update failed", throwable);
			}
		});
	}

	private boolean isInOverview(Activity activity) {
		try {
			Object stateManager = callMethod(activity, "getStateManager");
			if (stateManager == null) return false;
			Object state = null;
			Field stateField = findFieldIfExists(stateManager.getClass(), "mState");
			if (stateField != null) state = stateField.get(stateManager);
			if (state == null) state = callMethod(stateManager, "getState");
			return state != null && state == overviewState;
		} catch (Throwable throwable) {
			return false;
		}
	}

	private void hideStatusBar(Activity activity) {
		try {
			WindowInsetsController controller = activity.getWindow().getInsetsController();
			if (controller != null) controller.hide(WindowInsets.Type.statusBars());
		} catch (Throwable throwable) {
			log("LauncherChromeMods: hide statusbar failed", throwable);
		}
	}

	private void showStatusBar(Activity activity) {
		try {
			WindowInsetsController controller = activity.getWindow().getInsetsController();
			if (controller != null) controller.show(WindowInsets.Type.statusBars());
		} catch (Throwable throwable) {
			log("LauncherChromeMods: show statusbar failed", throwable);
		}
	}

	// ---------------------------------------------------------------- dark statusbar

	private void hookDarkStatusbar() {
		try {
			ReflectedClass systemUiController = ReflectedClass.ofIfPossible("com.android.launcher3.util.SystemUiController");
			if (systemUiController.getClazz() != null) {
				systemUiController.before("updateUiState").run(param -> {
					try {
						if (!darkStatusbar || param.args.length != 2) return;
						if (!(param.args[0] instanceof Integer uiState) || uiState != UI_STATE_BASE_WINDOW) return;
						if (!(param.args[1] instanceof Integer flags)) return;
						// Keep the nav bar flags, force dark status bar icons
						param.args[1] = (flags & NAV_FLAGS) | FLAG_LIGHT_STATUS;
					} catch (Throwable throwable) {
						log("LauncherChromeMods: updateUiState hook failed", throwable);
					}
				});
			}
			for (String className : STATUSBAR_ACTIVITY_CLASSES) {
				ReflectedClass activityClass = ReflectedClass.ofIfPossible(className);
				if (activityClass.getClazz() == null) continue;
				activityClass.after("onCreate").run(param -> {
					try {
						if (!(param.thisObject instanceof Activity activity)) return;
						launcherActivities.add(activity);
						if (darkStatusbar) reapplyDarkStatusbar(activity);
					} catch (Throwable throwable) {
						log("LauncherChromeMods: dark statusbar onCreate hook failed", throwable);
					}
				});
			}
		} catch (Throwable throwable) {
			log("LauncherChromeMods: dark statusbar hooks unavailable", throwable);
		}
	}

	/** Pushes stock flags through updateUiState so the hook above rewrites them immediately. */
	private void reapplyDarkStatusbar(Activity activity) {
		try {
			if (activity.isDestroyed()) return;
			int stockFlags = isWorkspaceDarkText(activity) ? STOCK_LIGHT_FLAGS : STOCK_DARK_FLAGS;
			Object controller = callMethod(activity, "getSystemUiController");
			if (controller != null) callMethod(controller, "updateUiState", UI_STATE_BASE_WINDOW, stockFlags);
		} catch (Throwable throwable) {
			log("LauncherChromeMods: dark statusbar reapply failed", throwable);
		}
	}

	@SuppressLint("DiscouragedApi")
	private static boolean isWorkspaceDarkText(Activity activity) {
		int attr = activity.getResources().getIdentifier("isWorkspaceDarkText", "attr", activity.getPackageName());
		if (attr == 0) return false;
		TypedValue value = new TypedValue();
		return activity.getTheme().resolveAttribute(attr, value, true) && value.data != 0;
	}

	// ---------------------------------------------------------------- top shadow

	private void hookTopShadow() {
		try {
			ReflectedClass sysUiScrim = ReflectedClass.ofIfPossible("com.android.launcher3.graphics.SysUiScrim");
			if (sysUiScrim.getClazz() == null) return;
			sysUiScrim.afterConstruction().run(param -> {
				try {
					scrimInstance = param.thisObject;
					if (hideTopShadow) hideScrim(param.thisObject);
				} catch (Throwable throwable) {
					log("LauncherChromeMods: SysUiScrim constructor hook failed", throwable);
				}
			});
			// Attach/detach callbacks would rebuild the masks we replaced
			sysUiScrim.before("onViewAttachedToWindow").run(param -> {
				if (hideTopShadow) param.setResult(null);
			});
			sysUiScrim.before("onViewDetachedFromWindow").run(param -> {
				if (hideTopShadow) param.setResult(null);
			});
			sysUiScrim.after("createDitheredAlphaMask").run(param -> {
				try {
					if (!hideTopShadow) return;
					if (param.getResult() instanceof Bitmap original) {
						Bitmap standIn = transparentBitmap(original);
						scrimStandIns.put(standIn, original);
						param.setResult(standIn);
					}
				} catch (Throwable throwable) {
					log("LauncherChromeMods: createDitheredAlphaMask hook failed", throwable);
				}
			});
		} catch (Throwable throwable) {
			log("LauncherChromeMods: top shadow hooks unavailable", throwable);
		}
	}

	private void hideScrim(Object scrim) {
		for (String fieldName : SCRIM_MASK_FIELDS) {
			try {
				Field field = findFieldIfExists(scrim.getClass(), fieldName);
				if (field == null) continue;
				Object current = field.get(scrim);
				// Missing or already replaced by a stand-in
				if (current == null || scrimStandIns.containsKey(current)) continue;
				Object standIn;
				if (current instanceof Bitmap bitmap) standIn = transparentBitmap(bitmap);
				else if (current instanceof Drawable) standIn = new ColorDrawable(Color.TRANSPARENT);
				else continue;
				scrimStandIns.put(standIn, current);
				field.set(scrim, standIn);
			} catch (Throwable ignored) {
			}
		}
		try {
			setBooleanField(scrim, "mHideSysUiScrim", true);
		} catch (Throwable ignored) {
		}
		try {
			Object root = getObjectField(scrim, "mRoot");
			if (root instanceof View view) view.invalidate();
		} catch (Throwable ignored) {
		}
	}

	private static Bitmap transparentBitmap(Bitmap source) {
		return Bitmap.createBitmap(Math.max(source.getWidth(), 1), Math.max(source.getHeight(), 1), Bitmap.Config.ALPHA_8);
	}

	// ---------------------------------------------------------------- page indicator

	private void hookPageIndicator() {
		try {
			ReflectedClass launcher = ReflectedClass.ofIfPossible("com.android.launcher3.Launcher");
			if (launcher.getClazz() != null) {
				launcher.after("setupViews").run(param -> capturePageIndicator(param.thisObject));
				// Fallback capture in case setupViews is renamed or removed
				launcher.after("onCreate").run(param -> capturePageIndicator(param.thisObject));
			}
			for (String className : INDICATOR_CLASSES) {
				ReflectedClass indicator = ReflectedClass.ofIfPossible(className);
				Class<?> clazz = indicator.getClazz();
				if (clazz == null) continue;
				for (Method method : clazz.getDeclaredMethods()) {
					if (!"setPaintColor".equals(method.getName()) || Modifier.isAbstract(method.getModifiers())) continue;
					indicator.before(method).run(param -> {
						try {
							if (darkPageIndicator && param.thisObject instanceof View view && isWorkspaceIndicator(view)) {
								param.args[0] = Color.BLACK;
							}
						} catch (Throwable throwable) {
							log("LauncherChromeMods: setPaintColor hook failed", throwable);
						}
					});
				}
				indicator.before("onDraw").run(param -> {
					try {
						if (hidePageIndicator && param.thisObject instanceof View view && isWorkspaceIndicator(view)) {
							param.setResult(null);
						}
					} catch (Throwable throwable) {
						log("LauncherChromeMods: indicator onDraw hook failed", throwable);
					}
				});
			}
		} catch (Throwable throwable) {
			log("LauncherChromeMods: page indicator hooks unavailable", throwable);
		}
	}

	private void capturePageIndicator(Object launcher) {
		try {
			Object workspace = getObjectField(launcher, "mWorkspace");
			if (workspace == null) return;
			Object indicator = callMethod(workspace, "getPageIndicator");
			if (indicator instanceof View view) {
				pageIndicatorRef = new WeakReference<>(view);
				if (darkPageIndicator) {
					try {
						callMethod(view, "setPaintColor", Color.BLACK);
					} catch (Throwable ignored) {
					}
				}
			}
		} catch (Throwable ignored) {
		}
	}

	@SuppressLint("DiscouragedApi")
	private boolean isWorkspaceIndicator(View view) {
		if (pageIndicatorId == 0) {
			pageIndicatorId = view.getResources().getIdentifier("page_indicator", "id", view.getContext().getPackageName());
		}
		View captured = pageIndicatorRef == null ? null : pageIndicatorRef.get();
		View current = view;
		for (int depth = 0; depth < INDICATOR_PARENT_LOOKUPS && current != null; depth++) {
			if (current == captured || (pageIndicatorId != 0 && current.getId() == pageIndicatorId)) return true;
			current = current.getParent() instanceof View parent ? parent : null;
		}
		return false;
	}

	// ---------------------------------------------------------------- wallpaper dim

	private void hookWallpaperDim() {
		try {
			// Kotlin anonymous listener class; the '$' parts belong to the runtime class name
			ReflectedClass listener = ReflectedClass
					.ofIfPossible("com.android.launcher3.util.WallpaperColorHints$onColorsChangedListener$1");
			if (listener.getClazz() == null) return;
			listener.before("onColorsChanged").run(param -> {
				try {
					if (preventWallpaperDimRestart) param.setResult(null);
				} catch (Throwable ignored) {
				}
			});
		} catch (Throwable throwable) {
			log("LauncherChromeMods: wallpaper dim hook unavailable", throwable);
		}
	}
}
