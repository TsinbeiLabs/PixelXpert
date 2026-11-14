package com.tsinbei.pixelxpert.xposed.modpacks.launcher;

import static com.tsinbei.pixelxpert.xposed.XPrefs.Xprefs;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.getAdditionalInstanceField;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.getIntField;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.getObjectField;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.setAdditionalInstanceField;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.setIntField;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.setObjectField;

import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.graphics.Point;
import android.util.DisplayMetrics;

import com.tsinbei.pixelxpert.xposed.XposedModPack;
import com.tsinbei.pixelxpert.xposed.annotations.LauncherModPack;
import com.tsinbei.pixelxpert.xposed.utils.SystemUtils;
import com.tsinbei.pixelxpert.xposed.utils.reflection.ReflectedClass;

import java.util.Arrays;

import io.github.libxposed.api.XposedModuleInterface;

/** Home/drawer/folder grid options and free widget resize, adapted from PixelLauncherEnhanced's GPL-3.0 implementations. */
@LauncherModPack
public class LauncherGridMods extends XposedModPack {
	private int homeGridRows = 0;
	private int homeGridColumns = 0;
	private int drawerGridColumns = 0;
	private float drawerRowHeightMultiplier = 1f;
	private int folderGridRows = 0;
	private int folderGridColumns = 0;
	private boolean freeWidgetResize = false;

	private static final String ORIGINAL_RESIZE_MODE = "pxOriginalResizeMode";
	private static final int FOLDER_PREVIEW_ITEMS = 4;
	private static final int FOLDER_SCREEN_MARGIN_DP = 16;
	private static final float FOLDER_MAX_HEIGHT_RATIO = 0.8f;
	private static final String[] BUILDER_CLASS_NAMES = {
			"com.android.launcher3.DeviceProfile$Builder",
			"com.android.launcher3.deviceprofile.DeviceProfileBuilder"
	};

	private record FolderFields(String cellWidth, String cellHeight, String iconSize, String textSize,
	                            String drawablePadding, String borderSpace, String paddingSide, String paddingTop,
	                            String footer) { }

	private static final FolderFields FOLDER_PROFILE_FIELDS = new FolderFields(
			"cellWidthPx", "cellHeightPx", "childIconSizePx", "childTextSizePx", "childDrawablePaddingPx",
			"cellLayoutBorderSpacePx", "contentPaddingLeftRight", "contentPaddingTop", "footerHeightPx");

	private static final FolderFields LEGACY_FOLDER_FIELDS = new FolderFields(
			"folderCellWidthPx", "folderCellHeightPx", "folderChildIconSizePx", "folderChildTextSizePx",
			"folderChildDrawablePaddingPx", "folderCellLayoutBorderSpacePx", "folderContentPaddingLeftRight",
			"folderContentPaddingTop", "folderFooterHeightPx");

	public LauncherGridMods(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {
		if (Xprefs == null) return;
		// MaterialRangeSliderPreference stores values in slider format: must read via getSliderInt
		homeGridRows = Xprefs.getSliderInt("LauncherHomeGridRows", 0);
		homeGridColumns = Xprefs.getSliderInt("LauncherHomeGridColumns", 0);
		drawerGridColumns = Xprefs.getSliderInt("LauncherDrawerGridColumns", 0);
		drawerRowHeightMultiplier = Xprefs.getSliderInt("LauncherDrawerRowHeight", 100) / 100f;
		folderGridRows = Xprefs.getSliderInt("LauncherFolderGridRows", 0);
		folderGridColumns = Xprefs.getSliderInt("LauncherFolderGridColumns", 0);
		freeWidgetResize = Xprefs.getBoolean("LauncherFreeWidgetResize", false);
		if (Key.length > 0 && (Key[0].equals("LauncherHomeGridRows")
				|| Key[0].equals("LauncherHomeGridColumns")
				|| Key[0].equals("LauncherDrawerGridColumns")
				|| Key[0].equals("LauncherDrawerRowHeight")
				|| Key[0].equals("LauncherFolderGridRows")
				|| Key[0].equals("LauncherFolderGridColumns")
				|| Key[0].equals("LauncherFreeWidgetResize"))) {
			SystemUtils.killSelf();
		}
	}

	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) {
		hookGridOptions();
		hookWidgetResize();
	}

	private void hookGridOptions() {
		ReflectedClass idp = ReflectedClass.ofIfPossible("com.android.launcher3.InvariantDeviceProfile");
		ReflectedClass deviceProfile = ReflectedClass.ofIfPossible("com.android.launcher3.DeviceProfile");

		if (idp.getClazz() != null) {
			try {
				idp.before("initGrid").run(param -> {
					try {
						if (param.args.length >= 3) {
							patchFolderGrid(readField(param.args[2], "grid"));
						}
					} catch (Throwable throwable) { log("LauncherGridMods: initGrid before patch failed", throwable); }
				});
				idp.after("initGrid").run(param -> {
					try {
						if (homeGridRows != 0) writeField(param.thisObject, "numRows", homeGridRows);
						if (homeGridColumns != 0) writeField(param.thisObject, "numColumns", homeGridColumns);
						patchFolderGrid(param.thisObject);
					} catch (Throwable throwable) { log("LauncherGridMods: initGrid after patch failed", throwable); }
				});
				idp.after("invDistWeightedInterpolate").run(param -> patchGrid(param.getResult()));
			} catch (Throwable throwable) { log("LauncherGridMods: InvariantDeviceProfile hooks unavailable", throwable); }
		}

		if (deviceProfile.getClazz() != null) {
			try {
				Class<?> idpClass = idp.getClazz();
				deviceProfile.beforeConstruction().run(param -> {
					try {
						if (idpClass == null) return;
						for (Object arg : param.args) {
							if (idpClass.isInstance(arg)) {
								patchFolderGrid(arg);
								break;
							}
						}
					} catch (Throwable throwable) { log("LauncherGridMods: DeviceProfile constructor before patch failed", throwable); }
				});
				deviceProfile.afterConstruction().run(param -> {
					try {
						hookDeviceProfile(param.thisObject);
						fitFolderToScreen(param.thisObject, LEGACY_FOLDER_FIELDS);
						fitFolderToScreen(readAnyField(param.thisObject, "mFolderProfile", "folderProfile"), FOLDER_PROFILE_FIELDS);
					} catch (Throwable throwable) { log("LauncherGridMods: DeviceProfile constructor patch failed", throwable); }
				});
				deviceProfile.after("updateIconSize").run(param -> applyDrawerRowHeight(param.thisObject));
				deviceProfile.after("autoResizeAllAppsCells").run(param -> applyDrawerRowHeight(param.thisObject));
				deviceProfile.after("updateFolderCellSize").run(param -> {
					fitFolderToScreen(param.thisObject, LEGACY_FOLDER_FIELDS);
					fitFolderToScreen(param.getResult(), FOLDER_PROFILE_FIELDS);
				});
				deviceProfile.after("updateAvailableFolderCellDimensions").run(param -> {
					fitFolderToScreen(param.thisObject, LEGACY_FOLDER_FIELDS);
					fitFolderToScreen(param.getResult(), FOLDER_PROFILE_FIELDS);
				});
			} catch (Throwable throwable) { log("LauncherGridMods: DeviceProfile hooks unavailable", throwable); }
		}

		try {
			for (String builderName : BUILDER_CLASS_NAMES) {
				ReflectedClass builder = ReflectedClass.ofIfPossible(builderName);
				if (builder.getClazz() == null) continue;
				builder.after("build").run(param -> hookDeviceProfile(param.getResult()));
				builder.before("build").run(param -> patchFolderGrid(readAnyField(param.thisObject, "mInv", "inv")));
				builder.before("createFolderProfile").run(param -> patchFolderGrid(readAnyField(param.thisObject, "mInv", "inv")));
			}
		} catch (Throwable throwable) { log("LauncherGridMods: DeviceProfileBuilder hooks unavailable", throwable); }

		try {
			ReflectedClass displayOption = ReflectedClass.ofIfPossible("com.android.launcher3.deviceprofile.parser.DisplayOption");
			if (displayOption.getClazz() != null) {
				displayOption.after("parseWeightedPredefinedDisplayOption").run(param -> patchGrid(param.getResult()));
			}
		} catch (Throwable throwable) { log("LauncherGridMods: DisplayOption hooks unavailable", throwable); }

		try {
			ReflectedClass organizer = ReflectedClass.ofIfPossible("com.android.launcher3.folder.FolderGridOrganizer");
			if (organizer.getClazz() != null) {
				organizer.after("setContentSize").run(param -> {
					try {
						if (folderGridColumns == 0) return;
						if (param.args.length == 0 || !(param.args[0] instanceof Integer count) || count <= 0) return;
						Integer maxColumns = readInt(param.thisObject, "mMaxCountX");
						Integer maxRows = readInt(param.thisObject, "mMaxCountY");
						if (maxColumns == null || maxRows == null) return;
						int columns = Math.max(Math.min(count, maxColumns), 1);
						int rows = Math.max(Math.min((count + columns - 1) / columns, maxRows), 1);
						writeField(param.thisObject, "mCountX", columns);
						writeField(param.thisObject, "mCountY", rows);
					} catch (Throwable throwable) { log("LauncherGridMods: folder content size patch failed", throwable); }
				});
				organizer.before("isItemInPreview").run(param -> {
					try {
						if (folderGridColumns == 0 || param.args.length == 0) return;
						if (!(param.args[param.args.length - 1] instanceof Integer rank)) return;
						int page = 0;
						if (param.args.length >= 2) {
							if (!(param.args[0] instanceof Integer firstPage)) return;
							page = firstPage;
						}
						param.setResult(page == 0 && rank < FOLDER_PREVIEW_ITEMS);
					} catch (Throwable throwable) { log("LauncherGridMods: folder preview patch failed", throwable); }
				});
			}
		} catch (Throwable throwable) { log("LauncherGridMods: FolderGridOrganizer hooks unavailable", throwable); }

		try {
			ReflectedClass folderProfile = ReflectedClass.ofIfPossible("com.android.launcher3.deviceprofile.FolderProfile");
			if (folderProfile.getClazz() != null) {
				folderProfile.afterConstruction().run(param -> fitFolderToScreen(param.thisObject, FOLDER_PROFILE_FIELDS));
			}
		} catch (Throwable throwable) { log("LauncherGridMods: FolderProfile hooks unavailable", throwable); }

		try {
			ReflectedClass responsiveSpec = ReflectedClass.ofIfPossible("com.android.launcher3.responsive.CalculatedResponsiveSpec");
			if (responsiveSpec.getClazz() != null) {
				responsiveSpec.afterConstruction().run(param -> fitResponsiveSpec(param.thisObject));
				responsiveSpec.after("updateRemainderSpaces").run(param -> fitResponsiveSpec(param.thisObject));
			}
		} catch (Throwable throwable) { log("LauncherGridMods: responsive spec hooks unavailable", throwable); }

		try {
			ReflectedClass allAppsProfile = ReflectedClass.ofIfPossible("com.android.launcher3.deviceprofile.AllAppsProfile");
			if (allAppsProfile.getClazz() != null) {
				allAppsProfile.afterConstruction().run(param -> {
					try {
						if (drawerRowHeightMultiplier == 1f) return;
						Integer cellHeight = readInt(param.thisObject, "cellHeightPx");
						if (cellHeight == null) return;
						writeField(param.thisObject, "cellHeightPx", Math.round(cellHeight * drawerRowHeightMultiplier));
						writeField(param.thisObject, "iconDrawablePaddingPx", 0);
					} catch (Throwable throwable) { log("LauncherGridMods: AllAppsProfile patch failed", throwable); }
				});
			}
		} catch (Throwable throwable) { log("LauncherGridMods: AllAppsProfile hooks unavailable", throwable); }
	}

	private void hookWidgetResize() {
		try {
			ReflectedClass widgetInfo = ReflectedClass.ofIfPossible("com.android.launcher3.widget.LauncherAppWidgetProviderInfo");
			if (widgetInfo.getClazz() == null) return;
			widgetInfo.after("initSpans").run(param -> {
				try {
					if (!(param.thisObject instanceof AppWidgetProviderInfo info)) return;
					Object saved = getAdditionalInstanceField(info, ORIGINAL_RESIZE_MODE);
					int originalMode;
					if (saved instanceof Integer savedMode) {
						originalMode = savedMode;
					} else {
						originalMode = info.resizeMode;
						setAdditionalInstanceField(info, ORIGINAL_RESIZE_MODE, originalMode);
					}
					if (!freeWidgetResize) {
						info.resizeMode = originalMode;
						return;
					}
					if (param.args.length < 2 || param.args[1] == null) return;
					Object idp = param.args[1];
					info.resizeMode = AppWidgetProviderInfo.RESIZE_BOTH;
					setIntField(info, "minSpanX", 1);
					setIntField(info, "minSpanY", 1);
					setIntField(info, "maxSpanX", getIntField(idp, "numColumns"));
					setIntField(info, "maxSpanY", getIntField(idp, "numRows"));
				} catch (Throwable throwable) { log("LauncherGridMods: free widget resize patch failed", throwable); }
			});
		} catch (Throwable throwable) { log("LauncherGridMods: widget resize hook unavailable", throwable); }
	}

	/** Drawer column override across legacy and split-profile DeviceProfile layouts. */
	private void hookDeviceProfile(Object profile) {
		try {
			if (profile == null || drawerGridColumns == 0) return;
			writeField(profile, "numShownAllAppsColumns", drawerGridColumns);
			// Missing iconSizePx marks the newer split-profile layout (AllAppsProfile holder)
			if (readField(profile, "iconSizePx") == null) {
				writeField(readAnyField(profile, "mAllAppsProfile", "allAppsProfile"), "numShownAllAppsColumns", drawerGridColumns);
			}
			writeField(readAnyField(profile, "mDisplayOptionSpec", "displayOptionSpec"), "numAllAppsColumns", drawerGridColumns);
		} catch (Throwable throwable) { log("LauncherGridMods: DeviceProfile field patch failed", throwable); }
	}

	private void applyDrawerRowHeight(Object profile) {
		try {
			if (drawerRowHeightMultiplier == 1f) return;
			Integer cellHeight = readInt(profile, "allAppsCellHeightPx");
			if (cellHeight == null) return;
			writeField(profile, "allAppsCellHeightPx", Math.round(cellHeight * drawerRowHeightMultiplier));
			writeField(profile, "allAppsIconDrawablePaddingPx", 0);
		} catch (Throwable throwable) { log("LauncherGridMods: drawer row height patch failed", throwable); }
	}

	/** Patches the GridOption carried by a DisplayOption before it gets interpolated into the IDP. */
	private void patchGrid(Object displayOption) {
		try {
			Object grid = readField(displayOption, "grid");
			if (grid == null) return;
			if (homeGridRows != 0) writeField(grid, "numRows", homeGridRows);
			if (homeGridColumns != 0) writeField(grid, "numColumns", homeGridColumns);
			patchFolderGrid(grid);
			if (drawerGridColumns != 0) {
				writeField(grid, "numAllAppsColumns", drawerGridColumns);
				writeField(grid, "numDatabaseAllAppsColumns", drawerGridColumns);
				writeField(readField(grid, "displayOptionSpec"), "numAllAppsColumns", drawerGridColumns);
				writeField(readField(grid, "twoPanelDisplayOptionSpec"), "numAllAppsColumns", drawerGridColumns);
			}
		} catch (Throwable throwable) { log("LauncherGridMods: display option patch failed", throwable); }
	}

	/** numFolderRows/numFolderColumns are int on legacy builds and int[] on newer ones. */
	private void patchFolderGrid(Object grid) {
		try {
			if (grid == null) return;
			if (folderGridColumns != 0) patchGridValue(grid, "numFolderColumns", folderGridColumns);
			if (folderGridRows != 0) patchGridValue(grid, "numFolderRows", folderGridRows);
		} catch (Throwable throwable) { log("LauncherGridMods: folder grid patch failed", throwable); }
	}

	private void patchGridValue(Object grid, String name, int value) {
		Object current = readField(grid, name);
		if (current instanceof Integer) {
			writeField(grid, name, value);
		} else if (current instanceof int[] array) {
			int[] replacement = new int[array.length];
			Arrays.fill(replacement, value);
			writeField(grid, name, replacement);
		}
	}

	/** Recomputes cell size and paddings so an oversized folder grid still fits on screen. */
	private void fitFolderToScreen(Object profile, FolderFields fields) {
		try {
			if (profile == null || (folderGridColumns == 0 && folderGridRows == 0)) return;
			Integer cellWidth = readInt(profile, fields.cellWidth());
			Integer cellHeight = readInt(profile, fields.cellHeight());
			if (cellWidth == null || cellHeight == null) return;
			Object border = readField(profile, fields.borderSpace());
			int borderX = border instanceof Point point ? point.x : (border instanceof Integer value ? value : 0);
			int borderY = border instanceof Point point ? point.y : (border instanceof Integer value ? value : 0);
			int paddingSide = readIntOr(profile, fields.paddingSide(), 0);
			int paddingTop = readIntOr(profile, fields.paddingTop(), 0);
			int footer = readIntOr(profile, fields.footer(), 0);
			Integer numColumns = readInt(profile, "numColumns");
			Integer numRows = readInt(profile, "numRows");
			int columns = (numColumns != null && numColumns > 0) ? numColumns : folderGridColumns;
			int rows = (numRows != null && numRows > 0) ? numRows : folderGridRows;
			DisplayMetrics metrics = mContext.getResources().getDisplayMetrics();
			float margin = FOLDER_SCREEN_MARGIN_DP * metrics.density;
			float scale = 1f;
			if (columns > 0) {
				float required = columns * cellWidth + (columns - 1) * borderX + paddingSide * 2f;
				float available = metrics.widthPixels - margin * 2f;
				if (required > available) scale = Math.min(scale, available / required);
			}
			if (rows > 0) {
				float required = rows * cellHeight + (rows - 1) * borderY + paddingTop + footer;
				float available = metrics.heightPixels * FOLDER_MAX_HEIGHT_RATIO;
				if (required > available) scale = Math.min(scale, available / required);
			}
			if (scale >= 1f) return;
			writeField(profile, fields.cellWidth(), Math.round(cellWidth * scale));
			writeField(profile, fields.cellHeight(), Math.round(cellHeight * scale));
			for (String name : new String[]{ fields.iconSize(), fields.textSize(), fields.drawablePadding() }) {
				Integer value = readInt(profile, name);
				if (value != null) writeField(profile, name, Math.round(value * scale));
			}
			if (border instanceof Point point) {
				writeField(profile, fields.borderSpace(), new Point(Math.round(point.x * scale), Math.round(point.y * scale)));
			} else if (border instanceof Integer) {
				writeField(profile, fields.borderSpace(), Math.round(borderX * scale));
			}
		} catch (Throwable throwable) { log("LauncherGridMods: folder fit patch failed", throwable); }
	}

	/** Recenters cells when a responsive spec was left with unspecified (negative) paddings. */
	private void fitResponsiveSpec(Object spec) {
		try {
			if (homeGridColumns == 0 && homeGridRows == 0 && drawerGridColumns == 0
					&& folderGridColumns == 0 && folderGridRows == 0) return;
			Integer available = readInt(spec, "availableSpace");
			Integer cells = readInt(spec, "cells");
			Integer startPadding = readInt(spec, "startPaddingPx");
			Integer endPadding = readInt(spec, "endPaddingPx");
			Integer gutter = readInt(spec, "gutterPx");
			if (available == null || cells == null || startPadding == null || endPadding == null || gutter == null) return;
			if (cells <= 0 || available <= 0 || (startPadding >= 0 && endPadding >= 0)) return;
			int padding = Math.max(gutter, 0);
			int cellSize = (available - padding * 2 - gutter * (cells - 1)) / cells;
			if (cellSize <= 0) return;
			int used = cellSize * cells + gutter * (cells - 1);
			writeField(spec, "cellSizePx", cellSize);
			writeField(spec, "startPaddingPx", (available - used) / 2);
			writeField(spec, "endPaddingPx", available - used - (available - used) / 2);
		} catch (Throwable throwable) { log("LauncherGridMods: responsive spec patch failed", throwable); }
	}

	private static Object readField(Object object, String name) {
		if (object == null) return null;
		try { return getObjectField(object, name); } catch (Throwable ignored) { return null; }
	}

	private static Integer readInt(Object object, String name) {
		Object value = readField(object, name);
		return value instanceof Integer integer ? integer : null;
	}

	private static int readIntOr(Object object, String name, int fallback) {
		Integer value = readInt(object, name);
		return value == null ? fallback : value;
	}

	private static Object readAnyField(Object object, String... names) {
		if (object == null) return null;
		for (String name : names) {
			try { return getObjectField(object, name); } catch (Throwable ignored) { }
		}
		return null;
	}

	private static void writeField(Object object, String name, Object value) {
		if (object == null) return;
		try { setObjectField(object, name, value); } catch (Throwable ignored) { }
	}
}
