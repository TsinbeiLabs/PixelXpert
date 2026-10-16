package com.tsinbei.pixelxpert.xposed.modpacks.launcher;

import static com.tsinbei.pixelxpert.xposed.XPrefs.Xprefs;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.callMethod;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.findFieldIfExists;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.view.MotionEvent;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;

import com.tsinbei.pixelxpert.Constants;
import com.tsinbei.pixelxpert.xposed.XposedModPack;
import com.tsinbei.pixelxpert.xposed.annotations.LauncherModPack;
import com.tsinbei.pixelxpert.xposed.utils.SystemUtils;
import com.tsinbei.pixelxpert.xposed.utils.reflection.HookHelper;
import com.tsinbei.pixelxpert.xposed.utils.reflection.ReflectedClass;
import com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat;

import io.github.libxposed.api.XposedModuleInterface;

/**
 * Home screen double tap and pinch gestures, ported from PixelLauncherEnhanced's GPL-3.0 GestureMod.
 * <br>
 * The "launch a chosen app" action of the original is not ported: it requires an app picker UI.
 */
@LauncherModPack
public class LauncherHomeGestures extends XposedModPack {
	//Persisted action values, kept identical to PixelLauncherEnhanced's GestureAction enum
	private static final String ACTION_NONE = "none";
	private static final String ACTION_SLEEP = "sleep";
	private static final String ACTION_NOTIFICATIONS = "notifications";
	private static final String ACTION_QUICK_SETTINGS = "quick_settings";
	private static final String ACTION_RECENTS = "recents";
	private static final String ACTION_APP_DRAWER = "app_drawer";
	private static final String ACTION_SCREENSHOT = "screenshot";
	private static final String ACTION_FLASHLIGHT = "flashlight";
	private static final String ACTION_ASSISTANT = "assistant";
	//GestureAction.OPEN_APP ("open_app") is intentionally absent: it needs an app picker UI

	private static final long DOUBLE_TAP_TIMEOUT = 400L;
	private static final float TAP_DISTANCE_THRESHOLD = 50f;
	private static final float PINCH_MIN_DISTANCE_DP = 64f;
	private static final float PINCH_IN_RATIO = 0.7f;
	private static final float PINCH_OUT_RATIO = 1.4f;

	//WindowManager.ScreenshotType / WindowManager.ScreenshotSource, both hidden constants
	private static final int TAKE_SCREENSHOT_FULLSCREEN = 1;
	private static final int SCREENSHOT_SOURCE_KEY_CHORD = 1;

	private String doubleTapAction = ACTION_NONE;
	private String pinchInAction = ACTION_NONE;
	private String pinchOutAction = ACTION_NONE;

	private long firstTapTime;
	private float firstTapX;
	private float firstTapY;
	private boolean firstTapRunning;
	private boolean firstTapComplete;
	private boolean secondTapPending;

	private boolean pinchEligible;
	private boolean pinchTracking;
	private boolean pinchConsumed;
	private float pinchStartSpan;
	private String pendingPinchAction;

	private WeakReference<Object> launcherRef;
	private Class<?> launcherStateClass;
	private Class<?> floatingViewClass;
	private Object systemUiProxy;

	public LauncherHomeGestures(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {
		if (Xprefs == null) return;

		doubleTapAction = actionFrom(Xprefs.getString("LauncherGestureDoubleTap", "0"));
		pinchInAction = actionFrom(Xprefs.getString("LauncherGesturePinchIn", "0"));
		pinchOutAction = actionFrom(Xprefs.getString("LauncherGesturePinchOut", "0"));

		//The torch callback is registered lazily on the first camera access: touch the camera
		//manager now so the first toggle already knows whether the flashlight is on
		if (ACTION_FLASHLIGHT.equals(doubleTapAction)
				|| ACTION_FLASHLIGHT.equals(pinchInAction)
				|| ACTION_FLASHLIGHT.equals(pinchOutAction)) {
			SystemUtils.CameraManager();
		}
	}

	/** Unknown, empty and unsupported values (including "open_app") all mean "do nothing". */
	private static String actionFrom(String value) {
		if (value == null) return ACTION_NONE;
		return switch (value) {
			case ACTION_SLEEP, ACTION_NOTIFICATIONS, ACTION_QUICK_SETTINGS, ACTION_RECENTS,
					ACTION_APP_DRAWER, ACTION_SCREENSHOT, ACTION_FLASHLIGHT, ACTION_ASSISTANT -> value;
			default -> ACTION_NONE;
		};
	}

	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) throws Throwable {
		launcherStateClass = ReflectedClass.ofIfPossible("com.android.launcher3.LauncherState").getClazz();
		floatingViewClass = ReflectedClass.ofIfPossible("com.android.launcher3.AbstractFloatingView").getClazz();

		hookDoubleTap();
		hookPinch();
		hookSystemUiProxy();
	}

	/** Hooks are installed unconditionally, the consumers below decide whether to do any work. */
	private void hookDoubleTap() {
		try {
			ReflectedClass workspaceTouchListener = ReflectedClass.ofIfPossible("com.android.launcher3.touch.WorkspaceTouchListener");
			if (workspaceTouchListener.getClazz() == null) return;

			workspaceTouchListener.after("onTouch").run(param -> {
				try {
					//No action configured: the event passes through untouched. When all three
					//gesture actions are NONE, both hooks return on this first comparison
					if (ACTION_NONE.equals(doubleTapAction)) return;

					rememberLauncher(fieldValue(param.thisObject, "mLauncher"));
					if (param.args.length > 1 && param.args[1] instanceof MotionEvent event) detectDoubleTap(event);
				} catch (Throwable throwable) {
					log("LauncherHomeGestures: double tap detection failed", throwable);
				}
			});
		} catch (Throwable throwable) {
			log("LauncherHomeGestures: double tap hook unavailable", throwable);
		}
	}

	private void hookPinch() {
		try {
			ReflectedClass dragLayer = ReflectedClass.ofIfPossible("com.android.launcher3.dragndrop.DragLayer");
			if (dragLayer.getClazz() == null) return;

			dragLayer.before("dispatchTouchEvent").run(param -> {
				try {
					//No pinch action configured: the event passes through untouched
					if (ACTION_NONE.equals(pinchInAction) && ACTION_NONE.equals(pinchOutAction)) return;
					if (param.args.length == 0 || !(param.args[0] instanceof MotionEvent event)) return;

					onDragLayerTouch(param, event);
				} catch (Throwable throwable) {
					log("LauncherHomeGestures: pinch detection failed", throwable);
				}
			});
		} catch (Throwable throwable) {
			log("LauncherHomeGestures: pinch hook unavailable", throwable);
		}
	}

	//Captured whatever the settings are: the action to run is resolved at runtime
	private void hookSystemUiProxy() {
		try {
			ReflectedClass proxy = ReflectedClass.ofIfPossible("com.android.quickstep.SystemUiProxy");
			if (proxy.getClazz() == null) return;

			proxy.afterConstruction().run(param -> systemUiProxy = param.thisObject);
		} catch (Throwable throwable) {
			log("LauncherHomeGestures: SystemUiProxy hook unavailable", throwable);
		}
	}

	private void onDragLayerTouch(HookHelper.RunParam param, MotionEvent event) {
		switch (event.getActionMasked()) {
			case MotionEvent.ACTION_DOWN -> {
				rememberLauncher(anyField(param.thisObject, "mContainer", "mActivity"));
				pinchEligible = isHomeIdle();
				pinchTracking = false;
				pinchConsumed = false;
				pendingPinchAction = null;
			}

			case MotionEvent.ACTION_POINTER_DOWN -> {
				if (pinchEligible && !pinchConsumed && event.getPointerCount() == 2) {
					pinchStartSpan = span(event);
					pinchTracking = pinchStartSpan > 0f;
				} else {
					pinchTracking = false;
				}
			}

			case MotionEvent.ACTION_MOVE -> {
				//The pinch was claimed: swallow everything until the fingers are lifted
				if (pinchConsumed) {
					param.setResult(true);
					return;
				}
				if (!pinchTracking || event.getPointerCount() < 2) return;

				float currentSpan = span(event);
				float minDistance = PINCH_MIN_DISTANCE_DP * mContext.getResources().getDisplayMetrics().density;
				String action = null;

				if (currentSpan < pinchStartSpan * PINCH_IN_RATIO && pinchStartSpan - currentSpan > minDistance) {
					action = pinchInAction;
				} else if (currentSpan > pinchStartSpan * PINCH_OUT_RATIO && currentSpan - pinchStartSpan > minDistance) {
					action = pinchOutAction;
				}
				if (action == null) return;

				pinchTracking = false;
				if (ACTION_NONE.equals(action)) return;

				pinchConsumed = true;
				pendingPinchAction = action;

				//Hand a cancellation down the launcher so it drops its own gesture handling
				MotionEvent cancel = MotionEvent.obtain(event);
				cancel.setAction(MotionEvent.ACTION_CANCEL);
				param.args[0] = cancel;
			}

			case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
				pinchTracking = false;
				if (pinchConsumed) {
					pinchConsumed = false;
					param.setResult(true);
					if (event.getActionMasked() == MotionEvent.ACTION_UP) perform(pendingPinchAction);
				}
				pendingPinchAction = null;
			}

			default -> {
				if (pinchConsumed) param.setResult(true);
			}
		}
	}

	private void detectDoubleTap(MotionEvent event) {
		switch (event.getActionMasked()) {
			case MotionEvent.ACTION_DOWN -> {
				long currentTime = SystemClock.uptimeMillis();

				if (currentTime - firstTapTime > DOUBLE_TAP_TIMEOUT) {
					firstTapRunning = false;
					firstTapComplete = false;
				}
				secondTapPending = false;

				if (!firstTapRunning) {
					firstTapTime = currentTime;
					firstTapX = event.getX();
					firstTapY = event.getY();
					firstTapRunning = true;
				} else if (firstTapComplete) {
					secondTapPending = tapDistance(event) <= TAP_DISTANCE_THRESHOLD && isHomeIdle();
					firstTapRunning = false;
					firstTapComplete = false;
				}
			}

			case MotionEvent.ACTION_UP -> {
				if (secondTapPending) {
					secondTapPending = false;
					if (isHomeIdle()) perform(doubleTapAction);
				} else if (firstTapRunning && !firstTapComplete) {
					firstTapComplete = true;
				}
			}

			case MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
				firstTapRunning = false;
				firstTapComplete = false;
				secondTapPending = false;
			}

			case MotionEvent.ACTION_MOVE -> {
				if ((firstTapRunning || secondTapPending) && tapDistance(event) > TAP_DISTANCE_THRESHOLD) {
					firstTapRunning = false;
					firstTapComplete = false;
					secondTapPending = false;
				}
			}

			default -> { }
		}
	}

	private void perform(String action) {
		if (action == null || ACTION_NONE.equals(action)) return;

		try {
			SystemUtils.vibrate(VibrationEffect.EFFECT_TICK, VibrationAttributes.USAGE_TOUCH);

			switch (action) {
				case ACTION_SLEEP -> requestSleep();
				case ACTION_NOTIFICATIONS -> callSystemUiProxy("expandNotificationPanel");
				case ACTION_QUICK_SETTINGS -> callSystemUiProxy("toggleQuickSettingsPanel");
				case ACTION_RECENTS -> goToLauncherState("OVERVIEW");
				case ACTION_APP_DRAWER -> goToLauncherState("ALL_APPS");
				case ACTION_SCREENSHOT -> takeScreenshot();
				case ACTION_FLASHLIGHT -> SystemUtils.toggleFlash(false);
				case ACTION_ASSISTANT -> callSystemUiProxy("startAssistant", new Bundle());
				default -> { }
			}
		} catch (Throwable throwable) {
			log("LauncherHomeGestures: gesture action " + action + " failed", throwable);
		}
	}

	//PowerManager.goToSleep needs DEVICE_POWER, which the launcher process does not hold:
	//the framework side of PixelXpert takes the broadcast and calls SystemUtils.sleep() instead
	private void requestSleep() {
		mContext.sendBroadcast(new Intent(Constants.ACTION_SLEEP));
	}

	private void goToLauncherState(String stateFieldName) {
		Object launcher = launcher();
		Object state = staticField(launcherStateClass, stateFieldName);
		if (launcher == null || state == null) return;

		Object stateManager = callMethodSilently(launcher, "getStateManager");
		if (stateManager == null) stateManager = fieldValue(launcher, "mStateManager");
		if (stateManager == null) return;

		callMethod(stateManager, "goToState", state);
	}

	private void takeScreenshot() throws Exception {
		Object builder = ReflectedClass.of("com.android.internal.util.ScreenshotRequest$Builder")
				.getClazz()
				.getConstructor(int.class, int.class)
				.newInstance(TAKE_SCREENSHOT_FULLSCREEN, SCREENSHOT_SOURCE_KEY_CHORD);

		callSystemUiProxy("takeScreenshot", callMethod(builder, "build"));
	}

	private void callSystemUiProxy(String method, Object... args) {
		if (systemUiProxy == null) {
			log("LauncherHomeGestures: SystemUiProxy is unavailable, cannot run " + method);
			return;
		}
		callMethod(systemUiProxy, method, args);
	}

	/** Home screen with no floating view (popup, widget picker, ...) on top of it. */
	private boolean isHomeIdle() {
		Object launcher = launcher();
		if (launcher == null) return false;

		Object normalState = staticField(launcherStateClass, "NORMAL");
		//An unreadable state counts as idle, same as in the original implementation
		boolean inNormalState = normalState == null
				|| !Boolean.FALSE.equals(callMethodSilently(launcher, "isInState", normalState));

		return inNormalState && callStaticMethodSilently(floatingViewClass, "getTopOpenView", launcher) == null;
	}

	private void rememberLauncher(Object launcher) {
		if (launcher != null && launcher() != launcher) launcherRef = new WeakReference<>(launcher);
	}

	private Object launcher() {
		return launcherRef == null ? null : launcherRef.get();
	}

	private static float span(MotionEvent event) {
		return (float) Math.hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1));
	}

	private float tapDistance(MotionEvent event) {
		return (float) Math.hypot(event.getX() - firstTapX, event.getY() - firstTapY);
	}

	private static Object anyField(Object target, String... names) {
		for (String name : names) {
			Object value = fieldValue(target, name);
			if (value != null) return value;
		}
		return null;
	}

	private static Object fieldValue(Object target, String name) {
		Field field = findFieldIfExists(target.getClass(), name);
		if (field == null) return null;
		try {
			return field.get(target);
		} catch (Throwable ignored) {
			return null;
		}
	}

	private static Object staticField(Class<?> type, String name) {
		if (type == null) return null;
		try {
			return XposedCompat.getStaticObjectField(type, name);
		} catch (Throwable ignored) {
			return null;
		}
	}

	private static Object callMethodSilently(Object target, String name, Object... args) {
		try {
			return XposedCompat.callMethod(target, name, args);
		} catch (Throwable ignored) {
			return null;
		}
	}

	private static Object callStaticMethodSilently(Class<?> type, String name, Object... args) {
		if (type == null) return null;
		try {
			return XposedCompat.callStaticMethod(type, name, args);
		} catch (Throwable ignored) {
			return null;
		}
	}
}
