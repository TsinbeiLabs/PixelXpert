package com.tsinbei.pixelxpert.xposed.modpacks.launcher;

import static com.tsinbei.pixelxpert.xposed.XPrefs.Xprefs;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.callMethod;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.findFieldIfExists;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.getAdditionalInstanceField;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.setAdditionalInstanceField;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.util.SparseIntArray;
import android.view.View;

import androidx.core.graphics.ColorUtils;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import com.tsinbei.pixelxpert.xposed.XposedModPack;
import com.tsinbei.pixelxpert.xposed.annotations.LauncherModPack;
import com.tsinbei.pixelxpert.xposed.utils.SystemUtils;
import com.tsinbei.pixelxpert.xposed.utils.reflection.ReflectedClass;

import io.github.libxposed.api.XposedModuleInterface;

/**
 * Icon/text scaling, notification dot counters and themed icon colors, adapted from
 * PixelLauncherEnhanced's GPL-3.0 implementations.
 */
@SuppressWarnings("RedundantThrows")
@LauncherModPack
public class LauncherIconMods extends XposedModPack {
	private float iconSizeModifier = 1f;
	private float textSizeModifier = 1f;

	private boolean showDotCount;
	private boolean dotBlockEnabled;
	private Set<String> dotBlockList = Collections.emptySet();
	private final ThreadLocal<Integer> pendingDotCount = new ThreadLocal<>();

	private boolean customThemedIconColor;
	private int iconFgColorLight = Color.BLACK;
	private int iconBgColorLight = Color.WHITE;
	private int iconFgColorDark = Color.WHITE;
	private int iconBgColorDark = Color.BLACK;
	private int folderColorLight = Color.WHITE;
	private int folderColorDark = Color.BLACK;

	private static final String SCALED = "pxIconTextScaled";
	private static final int DEFAULT_DOT_COLOR = 0xFFE53935;
	private static final int MAX_DOT_COUNT = 99;

	private static final int ROLE_NONE = 0;
	private static final int ROLE_ICON_BG = 1;
	private static final int ROLE_ICON_FG = 2;
	private static final int ROLE_FOLDER_LIGHT = 3;
	private static final int ROLE_FOLDER_DARK = 4;
	private static final SparseIntArray themedColorRoles = new SparseIntArray();

	private final Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
	private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

	public LauncherIconMods(Context context) {
		super(context);
		textPaint.setTextAlign(Paint.Align.CENTER);
		textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
	}

	@Override
	public void onPreferenceUpdated(String... key) {
		if (Xprefs == null) return;
		iconSizeModifier = Xprefs.getSliderInt("LauncherIconSize", 100) / 100f;
		textSizeModifier = Xprefs.getSliderInt("LauncherTextSize", 100) / 100f;
		showDotCount = Xprefs.getBoolean("LauncherNotificationDotCount", false);
		dotBlockEnabled = Xprefs.getBoolean("LauncherNotificationDotBlockEnabled", false);
		dotBlockList = Xprefs.getStringSet("LauncherNotificationDotBlockList", Collections.emptySet());
		customThemedIconColor = Xprefs.getBoolean("ThemedIconCustomColor", false);
		iconFgColorLight = Xprefs.getInt("ThemedIconFgColorLight", Color.BLACK);
		iconBgColorLight = Xprefs.getInt("ThemedIconBgColorLight", Color.WHITE);
		iconFgColorDark = Xprefs.getInt("ThemedIconFgColorDark", Color.WHITE);
		iconBgColorDark = Xprefs.getInt("ThemedIconBgColorDark", Color.BLACK);
		folderColorLight = Xprefs.getInt("FolderCustomColorLight", Color.WHITE);
		folderColorDark = Xprefs.getInt("FolderCustomColorDark", Color.BLACK);

		if (key.length > 0 && key[0] != null && requiresLauncherRestart(key[0])) {
			SystemUtils.killSelf();
		}
	}

	private static boolean requiresLauncherRestart(String key) {
		return key.equals("LauncherIconSize")
				|| key.equals("LauncherTextSize")
				|| key.equals("LauncherNotificationDotCount")
				|| key.equals("ThemedIconCustomColor")
				|| key.equals("ThemedIconFgColorLight")
				|| key.equals("ThemedIconBgColorLight")
				|| key.equals("ThemedIconFgColorDark")
				|| key.equals("ThemedIconBgColorDark")
				|| key.equals("FolderCustomColorLight")
				|| key.equals("FolderCustomColorDark");
	}

	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) throws Throwable {
		hookIconTextSize();
		hookNotificationDots();
		resolveThemedColorIds(PRParam.getPackageName());
		hookThemedIconColors();
	}

	//region icon and label scaling

	private void hookIconTextSize() {
		try {
			ReflectedClass deviceProfile = ReflectedClass.ofIfPossible("com.android.launcher3.DeviceProfile");
			if (deviceProfile.getClazz() != null) {
				deviceProfile.afterConstruction().run(param -> {
					Object profile = param.thisObject;
					try {
						applySizeModifiers(profile);
					} catch (Throwable throwable) {
						log("LauncherIconMods: device profile scaling failed", throwable);
					} finally {
						IconFit.fitIconsToCells(profile, mContext);
					}
				});
			}

			hookProfileBuilder("com.android.launcher3.DeviceProfile$Builder");
			hookProfileBuilder("com.android.launcher3.deviceprofile.DeviceProfileBuilder");
		} catch (Throwable throwable) {
			log("LauncherIconMods: icon size hook unavailable", throwable);
		}
	}

	private void hookProfileBuilder(String className) {
		ReflectedClass builder = ReflectedClass.ofIfPossible(className);
		if (builder.getClazz() == null) return;
		builder.after("build").run(param -> {
			Object profile = param.getResult();
			try {
				applySizeModifiers(profile);
			} catch (Throwable throwable) {
				log("LauncherIconMods: device profile builder scaling failed", throwable);
			} finally {
				IconFit.fitIconsToCells(profile, mContext);
			}
		});
	}

	private void applySizeModifiers(Object profile) {
		if (profile == null) return;
		if (iconSizeModifier == 1f && textSizeModifier == 1f) return;

		if (getField(profile, "iconSizePx") instanceof Integer) {
			if (!scaleOnce(profile)) return;
			scaleField(profile, "iconSizePx", iconSizeModifier);
			scaleField(profile, "folderIconSizePx", iconSizeModifier);
			scaleField(profile, "folderChildIconSizePx", iconSizeModifier);
			scaleField(profile, "allAppsIconSizePx", iconSizeModifier);
			scaleField(profile, "iconTextSizePx", textSizeModifier);
			scaleField(profile, "folderLabelTextSizePx", textSizeModifier);
			scaleField(profile, "folderChildTextSizePx", textSizeModifier);
			scaleField(profile, "allAppsIconTextSizePx", textSizeModifier);
			scaleField(profile, "folderCellWidthPx", iconSizeModifier);
			scaleField(profile, "folderCellHeightPx", iconSizeModifier);
			return;
		}

		Object workspace = getAnyField(profile, "mWorkspaceProfile", "workspaceProfile");
		if (workspace != null && scaleOnce(workspace)) {
			scaleField(workspace, "iconSizePx", iconSizeModifier);
			scaleField(workspace, "iconTextSizePx", textSizeModifier);
		}

		Object folder = getAnyField(profile, "mFolderProfile", "folderProfile");
		if (folder != null && scaleOnce(folder)) {
			scaleField(folder, "folderIconSizePx", iconSizeModifier);
			scaleField(folder, "labelTextSizePx", textSizeModifier);
			scaleField(folder, "childIconSizePx", iconSizeModifier);
			scaleField(folder, "childTextSizePx", textSizeModifier);
			scaleField(folder, "cellWidthPx", iconSizeModifier);
			scaleField(folder, "cellHeightPx", iconSizeModifier);
		}

		Object allApps = getAnyField(profile, "mAllAppsProfile", "allAppsProfile");
		if (allApps != null && scaleOnce(allApps)) {
			scaleField(allApps, "iconSizePx", iconSizeModifier);
			scaleField(allApps, "iconTextSizePx", textSizeModifier);
		}

		if (scaleOnce(profile)) {
			scaleField(profile, "folderIconSizePx", iconSizeModifier);
			scaleField(profile, "folderLabelTextSizePx", textSizeModifier);
		}
	}

	private static boolean scaleOnce(Object target) {
		if (Boolean.TRUE.equals(getAdditionalInstanceField(target, SCALED))) return false;
		setAdditionalInstanceField(target, SCALED, true);
		return true;
	}

	private static void scaleField(Object target, String name, float modifier) {
		Object value = getField(target, name);
		if (value instanceof Integer) {
			setField(target, name, (int) ((Integer) value * modifier));
		} else if (value instanceof Float) {
			setField(target, name, (Float) value * modifier);
		}
	}

	//endregion

	//region notification dots

	private void hookNotificationDots() {
		try {
			ReflectedClass bubbleTextView = ReflectedClass.ofIfPossible("com.android.launcher3.BubbleTextView");
			if (bubbleTextView.getClazz() != null) {
				bubbleTextView.before("drawDotIfNecessary").run(param -> {
					try {
						View view = (View) param.thisObject;
						if (isDotBlocked(view)) {
							param.setResult(null);
							return;
						}
						if (showDotCount) pendingDotCount.set(dotCount(view));
					} catch (Throwable ignored) { }
				});
				bubbleTextView.after("drawDotIfNecessary").run(param -> pendingDotCount.remove());
			}

			ReflectedClass folderIcon = ReflectedClass.ofIfPossible("com.android.launcher3.folder.FolderIcon");
			if (folderIcon.getClazz() != null) {
				folderIcon.before("drawDot").run(param -> {
					try {
						if (!showDotCount && !dotBlockEnabled) return;

						List<View> icons = new ArrayList<>();
						Object rawIcons = callMethodSilently(getField(param.thisObject, "mFolder"), "getIconsInReadingOrder");
						if (rawIcons instanceof List<?> list) {
							for (Object item : list) {
								if (item instanceof View view && dotCount(view) > 0) icons.add(view);
							}
						}

						int visibleCount;
						if (icons.isEmpty()) {
							visibleCount = notificationCount(getField(param.thisObject, "mDotInfo"));
						} else {
							visibleCount = 0;
							for (View view : icons) {
								if (!isDotBlocked(view)) visibleCount += dotCount(view);
							}
						}

						if (visibleCount <= 0 && !icons.isEmpty()) {
							param.setResult(null);
							return;
						}
						if (showDotCount) pendingDotCount.set(visibleCount);
					} catch (Throwable ignored) { }
				});
				folderIcon.after("drawDot").run(param -> pendingDotCount.remove());
			}

			ReflectedClass dotRenderer = ReflectedClass.ofIfPossible("com.android.launcher3.icons.DotRenderer");
			if (dotRenderer.getClazz() != null) {
				dotRenderer.before("draw").run(param -> {
					try {
						Object renderer = param.thisObject;
						if (renderer == null) return;
						if (param.args.length < 2 || !(param.args[0] instanceof Canvas canvas)) return;
						Object params = param.args[1];
						if (params == null) return;
						Integer count = pendingDotCount.get();
						if (count == null || count <= 0) return;
						if (drawCount(canvas, renderer, params, count)) param.setResult(null);
					} catch (Throwable ignored) { }
				});
			}
		} catch (Throwable throwable) {
			log("LauncherIconMods: notification dot hook unavailable", throwable);
		}
	}

	private boolean drawCount(Canvas canvas, Object renderer, Object params, int count) {
		if (!(getField(params, "iconBounds") instanceof Rect iconBounds)) return false;

		float scale = 1f;
		Object scaleValue = getField(params, "scale");
		if (scaleValue instanceof Float) scale = (Float) scaleValue;
		if (scale <= 0f) return true;

		boolean leftAlign = Boolean.TRUE.equals(getField(params, "leftAlign"));
		PointF position = dotPosition(renderer, params, leftAlign);

		float baseRadius;
		Object radiusValue = getField(renderer, "mCircleRadius");
		baseRadius = radiusValue instanceof Float ? (Float) radiusValue : iconBounds.width() * 0.1f;

		int color = DEFAULT_DOT_COLOR;
		Object colorValue = getAnyField(params, "mDotColor", "dotColor");
		if (colorValue instanceof Integer) color = (Integer) colorValue;

		String text = count > MAX_DOT_COUNT ? MAX_DOT_COUNT + "+" : Integer.toString(count);

		textPaint.setTextSize(baseRadius * 1.45f);
		float radius = Math.max(baseRadius * 1.3f, textPaint.measureText(text) / 2f + baseRadius * 0.45f);
		Rect clip = canvas.getClipBounds();

		float centerX = iconBounds.left + iconBounds.width() * position.x;
		float centerY = iconBounds.top + iconBounds.height() * position.y;
		centerX = leftAlign ? Math.max(centerX, clip.left + radius) : Math.min(centerX, clip.right - radius);
		centerY = Math.max(centerY, clip.top + radius);

		circlePaint.setColor(color);
		textPaint.setColor(contrastingTextColor(color));

		canvas.save();
		canvas.translate(centerX, centerY);
		canvas.scale(scale, scale);
		canvas.drawCircle(0f, 0f, radius, circlePaint);
		canvas.drawText(text, 0f, -(textPaint.ascent() + textPaint.descent()) / 2f, textPaint);
		canvas.restore();

		return true;
	}

	private static PointF dotPosition(Object renderer, Object params, boolean leftAlign) {
		Object legacy = getField(renderer, leftAlign ? "mLeftDotPosition" : "mRightDotPosition");
		if (legacy instanceof float[] position && position.length >= 2) {
			return new PointF(position[0], position[1]);
		}

		Object shapePosition = getField(getField(params, "shapeInfo"),
				leftAlign ? "leftCornerPosition" : "rightCornerPosition");
		if (shapePosition instanceof PointF point) return point;

		return leftAlign ? new PointF(0.15f, 0.15f) : new PointF(0.85f, 0.15f);
	}

	private static int contrastingTextColor(int background) {
		return ColorUtils.calculateLuminance(background | 0xFF000000) > 0.5 ? Color.BLACK : Color.WHITE;
	}

	private boolean isDotBlocked(View view) {
		if (!dotBlockEnabled || dotBlockList == null || dotBlockList.isEmpty()) return false;
		try {
			Object info = view.getTag();
			if (info == null) return false;

			String packageName = null;
			Object target = callMethodSilently(info, "getTargetPackage");
			if (target instanceof String) packageName = (String) target;
			if (packageName == null) {
				Object component = callMethodSilently(info, "getTargetComponent");
				if (component instanceof ComponentName) packageName = ((ComponentName) component).getPackageName();
			}
			return packageName != null && dotBlockList.contains(packageName);
		} catch (Throwable ignored) {
			return false;
		}
	}

	private static int dotCount(View view) {
		return notificationCount(getField(view, "mDotInfo"));
	}

	private static int notificationCount(Object dotInfo) {
		if (dotInfo == null) return 0;

		int total = 0;
		Object count = callMethodSilently(dotInfo, "getNotificationCount");
		if (count instanceof Integer) {
			total = (Integer) count;
		} else {
			total = intOf(getAnyField(dotInfo, "mTotalCount", "totalCount"));
		}

		Object keys = getAnyField(dotInfo, "mNotificationKeys", "notificationKeys");
		if (!(keys instanceof List<?>)) keys = declaredListField(dotInfo);
		int keyCount = 0;
		if (keys instanceof List<?> list) keyCount = list.size();

		return Math.max(Math.max(total, keyCount), 1);
	}

	private static List<?> declaredListField(Object target) {
		try {
			for (Class<?> type = target.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
				for (Field field : type.getDeclaredFields()) {
					if (!List.class.isAssignableFrom(field.getType())) continue;
					field.setAccessible(true);
					Object value = field.get(target);
					if (value instanceof List<?> list) return list;
				}
			}
		} catch (Throwable ignored) { }
		return null;
	}

	//endregion

	//region themed icon colors

	@SuppressLint("DiscouragedApi")
	private void resolveThemedColorIds(String packageName) {
		try {
			if (packageName == null || packageName.isEmpty()) return;
			Resources resources = mContext.getResources();
			putColorRole(resources, packageName, ROLE_ICON_BG,
					"themed_icon_background_color", "themed_badge_icon_background_color");
			putColorRole(resources, packageName, ROLE_ICON_FG,
					"themed_icon_color", "qsb_icon_tint_quaternary_mono", "themed_badge_icon_color");
			putColorRole(resources, packageName, ROLE_FOLDER_LIGHT,
					"folder_preview_light", "folder_background_light");
			putColorRole(resources, packageName, ROLE_FOLDER_DARK,
					"folder_preview_dark", "folder_background_dark");
		} catch (Throwable throwable) {
			log("LauncherIconMods: themed color id resolution failed", throwable);
		}
	}

	@SuppressLint("DiscouragedApi")
	private static void putColorRole(Resources resources, String packageName, int role, String... names) {
		for (String name : names) {
			int id = resources.getIdentifier(name, "color", packageName);
			if (id == 0) continue;
			synchronized (themedColorRoles) {
				themedColorRoles.put(id, role);
			}
		}
	}

	private void hookThemedIconColors() {
		try {
			ReflectedClass.of(Resources.class).before("getColor").run(param -> {
				try {
					if (!customThemedIconColor) return;
					if (param.args.length == 0 || !(param.args[0] instanceof Integer id)) return;

					int role;
					synchronized (themedColorRoles) {
						role = themedColorRoles.get(id, ROLE_NONE);
					}
					if (role == ROLE_NONE) return;

					param.setResult(colorForRole(role));
				} catch (Throwable ignored) { }
			});
		} catch (Throwable throwable) {
			log("LauncherIconMods: themed icon color hook unavailable", throwable);
		}
	}

	private int colorForRole(int role) {
		if (role == ROLE_FOLDER_LIGHT) return folderColorLight;
		if (role == ROLE_FOLDER_DARK) return folderColorDark;

		boolean dark = isDarkTheme();
		if (role == ROLE_ICON_BG) return dark ? iconBgColorDark : iconBgColorLight;
		return dark ? iconFgColorDark : iconFgColorLight;
	}

	private boolean isDarkTheme() {
		try {
			return (mContext.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
					== Configuration.UI_MODE_NIGHT_YES;
		} catch (Throwable ignored) {
			return false;
		}
	}

	//endregion

	//region silent reflection helpers

	private static Object getField(Object target, String name) {
		if (target == null) return null;
		try {
			Field field = findFieldIfExists(target.getClass(), name);
			return field == null ? null : field.get(target);
		} catch (Throwable ignored) {
			return null;
		}
	}

	private static void setField(Object target, String name, Object value) {
		if (target == null) return;
		try {
			Field field = findFieldIfExists(target.getClass(), name);
			if (field != null) field.set(target, value);
		} catch (Throwable ignored) { }
	}

	private static Object getAnyField(Object target, String... names) {
		for (String name : names) {
			Object value = getField(target, name);
			if (value != null) return value;
		}
		return null;
	}

	private static Object callMethodSilently(Object target, String name, Object... args) {
		if (target == null) return null;
		try {
			return callMethod(target, name, args);
		} catch (Throwable ignored) {
			return null;
		}
	}

	private static int intOf(Object value) {
		return value instanceof Integer ? (Integer) value : 0;
	}

	//endregion

	/** Shrinks icons that outgrew their cell after scaling. */
	private static final class IconFit {
		private static final int MIN_GAP_DP = 8;

		private static final IconFields WORKSPACE_FIELDS =
				new IconFields("iconSizePx", "cellWidthPx", "cellLayoutBorderSpacePx");
		private static final IconFields ALL_APPS_PROFILE_FIELDS =
				new IconFields("iconSizePx", "cellWidthPx", "borderSpacePx");
		private static final IconFields FOLDER_PROFILE_FIELDS =
				new IconFields("childIconSizePx", "cellWidthPx", "cellLayoutBorderSpacePx");
		private static final IconFields LEGACY_FOLDER_FIELDS =
				new IconFields("folderChildIconSizePx", "folderCellWidthPx", "folderCellLayoutBorderSpacePx");
		private static final IconFields LEGACY_ALL_APPS_FIELDS =
				new IconFields("allAppsIconSizePx", "allAppsCellWidthPx", "allAppsBorderSpacePx");

		private IconFit() { }

		static void fitIconsToCells(Object deviceProfile, Context context) {
			if (deviceProfile == null || context == null) return;
			try {
				float minGap = MIN_GAP_DP * context.getResources().getDisplayMetrics().density;

				Object workspace = getAnyField(deviceProfile, "mWorkspaceProfile", "workspaceProfile");
				Object hotseat = getAnyField(deviceProfile, "mHotseatProfile", "hotseatProfile");

				int hotseatWidth;
				if (hotseat != null) {
					hotseatWidth = intOf(getField(hotseat, "widthPx"));
				} else {
					hotseatWidth = intOf(getField(deviceProfile, "availableWidthPx"))
							- intOf(getField(deviceProfile, "hotseatBarSidePaddingStartPx"))
							- intOf(getField(deviceProfile, "hotseatBarSidePaddingEndPx"));
				}

				int hotseatCount = intOf(getAnyField(hotseat != null ? hotseat : deviceProfile,
						"numShownIcons", "numShownHotseatIcons"));
				Integer hotseatPitch = null;
				if (hotseatWidth > 0 && hotseatCount > 0) hotseatPitch = hotseatWidth / hotseatCount;

				Object folderProfile = getAnyField(deviceProfile, "mFolderProfile", "folderProfile");
				Object folder = folderProfile != null ? folderProfile : deviceProfile;

				float scale = fitIconSize(workspace != null ? workspace : deviceProfile,
						WORKSPACE_FIELDS, minGap, hotseatPitch);
				if (scale < 1f) {
					scaleIntField(folder, "folderIconSizePx", scale);
					scaleIntField(folder, "folderIconOffsetYPx", scale);
				}

				fitIconSize(folder, folderProfile != null ? FOLDER_PROFILE_FIELDS : LEGACY_FOLDER_FIELDS, minGap, null);

				Object allApps = getAnyField(deviceProfile, "mAllAppsProfile", "allAppsProfile");
				fitIconSize(allApps != null ? allApps : deviceProfile,
						allApps != null ? ALL_APPS_PROFILE_FIELDS : LEGACY_ALL_APPS_FIELDS,
						minGap, allAppsPitch(deviceProfile, allApps, context));
			} catch (Throwable throwable) {
				log("LauncherIconMods: icon fit failed", throwable);
			}
		}

		private static Integer allAppsPitch(Object deviceProfile, Object allApps, Context context) {
			Object profile = allApps != null ? allApps : deviceProfile;
			int columns = intOf(getField(profile, "numShownAllAppsColumns"));
			if (columns <= 0) return null;

			Object properties = getAnyField(deviceProfile, "mDeviceProperties", "deviceProperties");
			if (properties == null) properties = deviceProfile;

			int width = intOf(getAnyField(properties, "availableWidthPx", "widthPx"));
			if (width <= 0) width = context.getResources().getDisplayMetrics().widthPixels;

			int padding;
			if (getField(profile, "padding") instanceof Rect rect) {
				padding = rect.left + rect.right;
			} else {
				padding = intOf(getField(deviceProfile, "allAppsLeftRightPadding")) * 2;
			}
			int margin = intOf(getAnyField(profile, "leftRightMargin", "allAppsLeftRightMargin")) * 2;

			int pitch = (width - padding - margin) / columns;
			return pitch > 0 ? pitch : null;
		}

		private static float fitIconSize(Object target, IconFields fields, float minGap, Integer extraPitch) {
			int iconSize = intOf(getField(target, fields.iconSize));
			int cellWidth = intOf(getField(target, fields.cellWidth));
			if (iconSize <= 0 || cellWidth <= 0) return 1f;

			Object border = getField(target, fields.borderSpace);
			int borderX = 0;
			if (border instanceof Point point) {
				borderX = point.x;
			} else if (border instanceof Integer) {
				borderX = (Integer) border;
			}

			int pitch = cellWidth + borderX;
			if (extraPitch != null && extraPitch < pitch) pitch = extraPitch;

			int maxIconSize = Math.round(pitch - minGap);
			if (maxIconSize <= 0 || iconSize <= maxIconSize) return 1f;

			setField(target, fields.iconSize, maxIconSize);
			return (float) maxIconSize / iconSize;
		}

		private static void scaleIntField(Object target, String name, float scale) {
			Object value = getField(target, name);
			if (value instanceof Integer) setField(target, name, Math.round((Integer) value * scale));
		}

		private static final class IconFields {
			final String iconSize;
			final String cellWidth;
			final String borderSpace;

			IconFields(String iconSize, String cellWidth, String borderSpace) {
				this.iconSize = iconSize;
				this.cellWidth = cellWidth;
				this.borderSpace = borderSpace;
			}
		}
	}
}
