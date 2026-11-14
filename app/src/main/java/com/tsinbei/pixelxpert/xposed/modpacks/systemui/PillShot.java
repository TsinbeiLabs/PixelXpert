package com.tsinbei.pixelxpert.xposed.modpacks.systemui;

import static com.tsinbei.pixelxpert.xposed.XPrefs.Xprefs;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.callMethod;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.getObjectField;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.setObjectField;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;

import java.util.Collections;
import java.util.Set;
import java.util.regex.Pattern;

import io.github.libxposed.api.XposedModuleInterface;
import com.tsinbei.pixelxpert.xposed.XposedModPack;
import com.tsinbei.pixelxpert.xposed.annotations.SystemUIModPack;
import com.tsinbei.pixelxpert.xposed.utils.reflection.ReflectedClass;
import com.tsinbei.pixelxpert.xposed.utils.toolkit.Logger;

/**
 * Draws the source app's name as a pill on screenshots. Can stamp all screenshots or only
 * (or all except) a selected set of apps.
 * Adapted from PixelInjector "PillShot" by hxreborn (GPL-3.0), originally eXtreames/PillShot.
 */
@SuppressWarnings("RedundantThrows")
@SystemUIModPack
public class PillShot extends XposedModPack {
	private static final String CONTROLLER = "com.android.systemui.screenshot.ScreenshotController";
	private static final String CAPTURE = "com.android.systemui.screenshot.ImageCaptureImpl";
	private static final int TAKE_SCREENSHOT_FULLSCREEN = 1;

	private static boolean enabled = false;
	private static boolean onlySelected = false;
	private static Set<String> selectedApps = Collections.emptySet();

	private static final ThreadLocal<Object[]> pendingScreenshot = new ThreadLocal<>();

	public PillShot(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {
		if (Xprefs == null) return;
		enabled = Xprefs.getBoolean("pillShotEnabled", false);
		onlySelected = Xprefs.getBoolean("pillShotOnlySelected", false);
		selectedApps = Xprefs.getStringSet("pillShotApps", Collections.emptySet());
	}

	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) throws Throwable {
		ReflectedClass controllerClass = ReflectedClass.ofIfPossible(CONTROLLER);
		if (controllerClass.getClazz() == null) return;

		controllerClass
				.before(Pattern.compile("handleScreenshot.*"))
				.run(param -> {
					if (!enabled) return;
					if (param.args == null || param.args.length == 0) return;
					Object shot = param.args[0];
					Context context = resolveContext(param.thisObject);
					if (context == null || shot == null) return;
					try {
						stamp(context, shot);
					} catch (Throwable t) {
						Logger.log("PillShot: stamp failed (entry): " + t);
					}
					pendingScreenshot.set(new Object[]{context, shot});
				});

		controllerClass
				.after(Pattern.compile("handleScreenshot.*"))
				.run(param -> pendingScreenshot.remove());

		// A16+: the bitmap is produced by captureDisplay after handleScreenshot starts
		ReflectedClass captureClass = ReflectedClass.ofIfPossible(CAPTURE);
		if (captureClass.getClazz() != null) {
			captureClass
					.after("captureDisplay")
					.run(param -> {
						Object[] pending = pendingScreenshot.get();
						if (!enabled || pending == null) return;
						Context context = (Context) pending[0];
						Object shot = pending[1];
						if (!awaitingCapture(shot)) return;
						Object result = param.getResult();
						if (!(result instanceof Bitmap captured)) return;
						try {
							Bitmap stamped = stampBitmap(context, shot, captured);
							if (stamped != null) param.setResult(stamped);
						} catch (Throwable t) {
							Logger.log("PillShot: stamp failed (capture): " + t);
						}
					});
		}
	}

	private static Context resolveContext(Object controller) {
		try {
			return (Context) getObjectField(controller, "context");
		} catch (Throwable t) {
			try {
				return (Context) getObjectField(controller, "mContext");
			} catch (Throwable t2) {
				return null;
			}
		}
	}

	private static int readType(Object shot) {
		try {
			return (int) getObjectField(shot, "type");
		} catch (Throwable t) {
			try {
				return (int) getObjectField(shot, "mType");
			} catch (Throwable t2) {
				return -1;
			}
		}
	}

	private static Bitmap readBitmap(Object shot) {
		try {
			return (Bitmap) getObjectField(shot, "bitmap");
		} catch (Throwable t) {
			try {
				return (Bitmap) getObjectField(shot, "mBitmap");
			} catch (Throwable t2) {
				return null;
			}
		}
	}

	private static void writeBitmap(Object shot, Bitmap bitmap) {
		try {
			setObjectField(shot, "bitmap", bitmap);
		} catch (Throwable t) {
			try {
				setObjectField(shot, "mBitmap", bitmap);
			} catch (Throwable ignored) {
			}
		}
	}

	private static boolean awaitingCapture(Object shot) {
		try {
			return readType(shot) == TAKE_SCREENSHOT_FULLSCREEN && readBitmap(shot) == null;
		} catch (Throwable t) {
			return false;
		}
	}

	private static void stamp(Context context, Object shot) {
		Bitmap src = readBitmap(shot);
		if (src == null) return;
		Bitmap stamped = stampBitmap(context, shot, src);
		if (stamped != null) writeBitmap(shot, stamped);
	}

	private static Bitmap stampBitmap(Context context, Object shot, Bitmap src) {
		String pkg;
		try {
			pkg = (String) callMethod(shot, "getPackageNameString");
		} catch (Throwable t) {
			return null;
		}
		if (pkg == null || pkg.isEmpty()) return null;
		if (!shouldStamp(pkg)) return null;
		return drawPill(context, label(context, pkg), src);
	}

	private static boolean shouldStamp(String pkg) {
		Set<String> selected = selectedApps;
		if (selected.isEmpty()) return true;
		return selected.contains(pkg) == onlySelected;
	}

	private static String label(Context context, String pkg) {
		try {
			PackageManager pm = context.getPackageManager();
			return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
		} catch (Throwable t) {
			return pkg;
		}
	}

	private static Bitmap drawPill(Context context, String label, Bitmap src) {
		Bitmap bitmap = src.isMutable() ? src : src.copy(Bitmap.Config.ARGB_8888, true);
		float density = context.getResources().getDisplayMetrics().density;
		boolean portrait = bitmap.getHeight() > bitmap.getWidth();
		float top = (portrait ? 12 : 2) * density;
		float hPad = 12 * density;
		float vPad = (portrait ? 8 : 6) * density;
		float radius = 18 * density;

		Paint pill = new Paint(Paint.ANTI_ALIAS_FLAG);
		pill.setColor(Color.rgb(28, 28, 30));

		Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
		text.setColor(Color.WHITE);
		text.setTextSize(16 * density);
		text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
		text.setTextAlign(Paint.Align.CENTER);

		Rect bounds = new Rect();
		text.getTextBounds(label, 0, label.length(), bounds);
		float width = Math.max(bounds.width() + 2 * hPad, 80 * density);
		float height = bounds.height() + 2 * vPad;
		float left = (bitmap.getWidth() - width) / 2f;

		Canvas canvas = new Canvas(bitmap);
		canvas.drawRoundRect(left, top, left + width, top + height, radius, radius, pill);
		canvas.drawText(
				label,
				bitmap.getWidth() / 2f,
				top + height / 2f + bounds.height() / 2f - bounds.bottom,
				text);
		return bitmap;
	}
}
