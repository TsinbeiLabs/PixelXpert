package com.tsinbei.pixelxpert.xposed.modpacks.android;

import static com.tsinbei.pixelxpert.xposed.XPrefs.Xprefs;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.callMethod;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.findClass;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.getObjectField;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.DisplayMetrics;
import android.view.MotionEvent;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModuleInterface;
import com.tsinbei.pixelxpert.xposed.XposedModPack;
import com.tsinbei.pixelxpert.xposed.annotations.FrameworkModPack;
import com.tsinbei.pixelxpert.xposed.utils.reflection.ReflectedClass;
import com.tsinbei.pixelxpert.xposed.utils.toolkit.Logger;

/**
 * Three-finger swipe down takes a screenshot. Detects the gesture on the system's own
 * pointer listener (read-only, no gesture monitor) and fires the platform screenshot.
 * Adapted from PixelSuite by RovianDev (GPL-3.0).
 */
@SuppressWarnings("RedundantThrows")
@FrameworkModPack
public class ThreeFingerScreenshot extends XposedModPack {
	private static final String PWM = "com.android.server.policy.PhoneWindowManager";
	private static final String LISTENER = "com.android.server.wm.SystemGesturesPointerEventListener";
	private static final String SCREENSHOT_HELPER = "com.android.internal.util.ScreenshotHelper";
	private static final String SCREENSHOT_REQUEST_BUILDER = "com.android.internal.util.ScreenshotRequest$Builder";

	private static final int FINGERS = 3;
	private static final long SWIPE_TIMEOUT_MS = 800L;
	private static final long COOLDOWN_MS = 1200L;
	private static final float DISTANCE_FRACTION = 0.12f;
	private static final float MIN_DISTANCE_DP = 80f;
	private static final float VERTICAL_RATIO = 1.2f;
	private static final int TAKE_SCREENSHOT_FULLSCREEN = 1;
	private static final int SCREENSHOT_VENDOR_GESTURE = 6;

	private static boolean enabled = false;
	private static volatile boolean initDone = false;
	private static ClassLoader classLoader;
	private static Context context;
	private static Object screenshotHelper;
	private static Handler worker;
	private static float minDistancePx;
	private static final Detector detector = new Detector();

	public ThreeFingerScreenshot(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {
		if (Xprefs == null) return;
		enabled = Xprefs.getBoolean("threeFingerScreenshotEnabled", false);
	}

	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) throws Throwable {
		classLoader = PRParam.getClassLoader();

		ReflectedClass pwmClass = ReflectedClass.ofIfPossible(PWM);
		if (pwmClass.getClazz() != null) {
			pwmClass.after("systemBooted").run(param -> {
				Context ctx = null;
				try {
					ctx = (Context) getObjectField(param.thisObject, "mContext");
				} catch (Throwable ignored) {
				}
				init(ctx);
			});
		}

		ReflectedClass listenerClass = ReflectedClass.ofIfPossible(LISTENER);
		if (listenerClass.getClazz() != null) {
			listenerClass.after("onPointerEvent").run(param -> {
				if (!enabled) return;
				if (param.args == null || param.args.length == 0 || !(param.args[0] instanceof MotionEvent)) return;
				if (!initDone) init(systemContext());
				if (!initDone) return;
				try {
					detector.onTouch((MotionEvent) param.args[0]);
				} catch (Throwable t) {
					Logger.log("ThreeFingerScreenshot: touch handling failed: " + t);
				}
			});
		}
	}

	private static synchronized void init(Context ctx) {
		if (initDone || ctx == null) return;
		try {
			context = ctx;
			DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
			float byFraction = dm.heightPixels * DISTANCE_FRACTION;
			float byDp = MIN_DISTANCE_DP * dm.density;
			minDistancePx = Math.max(byDp, Math.min(byFraction, dm.heightPixels * 0.25f));

			screenshotHelper = findClass(SCREENSHOT_HELPER, classLoader)
					.getConstructor(Context.class).newInstance(ctx);

			HandlerThread t = new HandlerThread("3FingerShot");
			t.start();
			worker = new Handler(t.getLooper());

			initDone = true;
		} catch (Throwable t) {
			Logger.log("ThreeFingerScreenshot: init failed", t);
		}
	}

	private static Context systemContext() {
		try {
			Class<?> at = findClass("android.app.ActivityThread", classLoader);
			Object thread = com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.callStaticMethod(at, "currentActivityThread");
			Object ctx = callMethod(thread, "getSystemContext");
			if (ctx instanceof Context) return (Context) ctx;
		} catch (Throwable ignored) {
		}
		return null;
	}

	private static final class Detector {
		private static final int MAX = 12;
		private final int[] mId = new int[MAX];
		private final float[] mDownX = new float[MAX];
		private final float[] mDownY = new float[MAX];
		private int mCount;
		private long mThreeAt;
		private boolean mFired;
		private long mLastShotAt;

		void onTouch(MotionEvent ev) {
			switch (ev.getActionMasked()) {
				case MotionEvent.ACTION_DOWN:
					reset();
					capture(ev, ev.getActionIndex());
					break;
				case MotionEvent.ACTION_POINTER_DOWN:
					capture(ev, ev.getActionIndex());
					if (ev.getPointerCount() == FINGERS && mThreeAt == 0) {
						mThreeAt = ev.getEventTime();
					}
					break;
				case MotionEvent.ACTION_MOVE:
					if (enabled && !mFired && mThreeAt != 0 && ev.getPointerCount() == FINGERS) {
						detect(ev);
					}
					break;
				case MotionEvent.ACTION_UP:
				case MotionEvent.ACTION_CANCEL:
					reset();
					break;
			}
		}

		private void detect(MotionEvent ev) {
			long now = ev.getEventTime();
			if (now - mThreeAt > SWIPE_TIMEOUT_MS) return;
			for (int p = 0; p < FINGERS; p++) {
				int slot = indexOf(ev.getPointerId(p));
				if (slot < 0) return;
				float dy = ev.getY(p) - mDownY[slot];
				float dx = Math.abs(ev.getX(p) - mDownX[slot]);
				if (dy < minDistancePx) return;
				if (dy < dx * VERTICAL_RATIO) return;
			}
			mFired = true;
			if (now - mLastShotAt < COOLDOWN_MS) return;
			mLastShotAt = now;
			takeScreenshot();
		}

		private void reset() {
			mCount = 0;
			mThreeAt = 0;
			mFired = false;
		}

		private void capture(MotionEvent ev, int index) {
			if (mCount >= MAX) return;
			int id = ev.getPointerId(index);
			if (indexOf(id) >= 0) return;
			mId[mCount] = id;
			mDownX[mCount] = ev.getX(index);
			mDownY[mCount] = ev.getY(index);
			mCount++;
		}

		private int indexOf(int id) {
			for (int i = 0; i < mCount; i++) if (mId[i] == id) return i;
			return -1;
		}
	}

	private static void takeScreenshot() {
		Object helper = screenshotHelper;
		Handler h = worker;
		if (helper == null || h == null) return;
		h.post(() -> {
			try {
				Class<?> consumer = Class.forName("java.util.function.Consumer");
				Object request = buildRequest();
				if (request != null) {
					helper.getClass().getMethod("takeScreenshot", request.getClass(), Handler.class, consumer)
							.invoke(helper, request, h, null);
					return;
				}
				helper.getClass().getMethod("takeScreenshot", int.class, int.class, Handler.class, consumer)
						.invoke(helper, TAKE_SCREENSHOT_FULLSCREEN, SCREENSHOT_VENDOR_GESTURE, h, null);
			} catch (Throwable t) {
				Logger.log("ThreeFingerScreenshot: request failed", t);
			}
		});
	}

	private static Object buildRequest() {
		try {
			Class<?> builderCls = findClass(SCREENSHOT_REQUEST_BUILDER, classLoader);
			Object builder = builderCls.getConstructor(int.class, int.class)
					.newInstance(TAKE_SCREENSHOT_FULLSCREEN, SCREENSHOT_VENDOR_GESTURE);
			Method build = builderCls.getMethod("build");
			return build.invoke(builder);
		} catch (Throwable t) {
			return null;
		}
	}
}
