package com.tsinbei.pixelxpert.xposed.modpacks.launcher.mods;

import android.content.Context;
import android.view.View;

import com.tsinbei.pixelxpert.xposed.modpacks.launcher.TaskbarActivator;
import com.tsinbei.pixelxpert.xposed.utils.reflection.ReflectedClass;
import com.tsinbei.pixelxpert.xposed.utils.reflection.ReflectedClass.ReflectionConsumer;
import com.tsinbei.pixelxpert.xposed.utils.toolkit.Logger;

import java.util.ArrayList;
import java.util.List;

import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.callMethod;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.getFloatField;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.getObjectField;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.setIntField;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.setBooleanField;

/**
 * Recents screen tweaks: phone-style recents while the taskbar is forced on,
 * bottom-aligned grid rows and recents header icon scaling.
 * Adapted from Pixel Taskbar Enabler by beyman-dev (GPLv3).
 */
public class RecentsMod extends BaseLauncherMod {

	public RecentsMod(TaskbarActivator activator, Context context, TaskbarSettings settings) {
		super(activator, context, settings);
	}

	@Override
	public void applyHooks() throws Throwable {
		ReflectedClass RecentsViewClass = ReflectedClass.ofIfPossible("com.android.quickstep.views.RecentsView");
		ReflectedClass OverviewActionsViewClass = ReflectedClass.ofIfPossible("com.android.quickstep.views.OverviewActionsView");
		ReflectedClass TaskViewClass = ReflectedClass.ofIfPossible("com.android.quickstep.views.TaskView");
		ReflectedClass BaseContainerInterfaceClass = ReflectedClass.ofIfPossible("com.android.quickstep.BaseContainerInterface");

		if (RecentsViewClass.getClazz() != null) {
			// Force the classic phone carousel when mobile recents is on
			RecentsViewClass.before("showAsGrid").run(param -> {
				if (mSettings.mobileRecents) {
					param.setResult(false);
				}
			});
			RecentsViewClass.before("setOverviewGridEnabled").run(param -> {
				if (mSettings.mobileRecents && param.args.length > 0) {
					param.args[0] = false;
				}
			});

			// Invert the Y position calculation of each TaskView so grid rows
			// end up at the bottom of the screen (easier one-handed reach).
			RecentsViewClass.after("updateGridProperties").run(param -> {
				if (param.args.length != 1) return;
				if (mSettings.taskbarMode != TaskbarSettings.TASKBAR_ON) return;
				if (mSettings.mobileRecents) return; // grid is disabled anyway
				try {
					Object recentsView = param.thisObject;
					boolean showAsGrid = (boolean) callMethod(recentsView, "showAsGrid");
					if (!showAsGrid) return;

					Object mTopRowIdSet = getObjectField(recentsView, "mTopRowIdSet");

					int taskCount = (int) callMethod(recentsView, "getTaskViewCount");
					List<Integer> newTopRowIds = new ArrayList<>();

					for (int i = 0; i < taskCount; i++) {
						Object taskView = callMethod(recentsView, "getTaskViewAt", i);
						if (taskView != null) {
							boolean isLargeTile = (boolean) callMethod(taskView, "isLargeTile");
							if (!isLargeTile) {
								int taskId = (int) callMethod(taskView, "getTaskViewId");
								boolean wasInTopRow = (boolean) callMethod(mTopRowIdSet, "contains", taskId);
								if (!wasInTopRow) {
									newTopRowIds.add(taskId);
								}
							}
						}
					}

					Object mArray = getObjectField(mTopRowIdSet, "mArray");
					setIntField(mArray, "mSize", 0);

					for (Integer id : newTopRowIds) {
						callMethod(mTopRowIdSet, "add", id);
					}

					float topBottomRowHeightDiff = getFloatField(recentsView, "mTopBottomRowHeightDiff");
					for (int i = 0; i < taskCount; i++) {
						Object taskView = callMethod(recentsView, "getTaskViewAt", i);
						if (taskView != null) {
							boolean isLargeTile = (boolean) callMethod(taskView, "isLargeTile");
							if (!isLargeTile) {
								int taskId = (int) callMethod(taskView, "getTaskViewId");
								boolean isTopRow = (boolean) callMethod(mTopRowIdSet, "contains", taskId);
								float newTranslationY = isTopRow ? 0.0f : topBottomRowHeightDiff;
								callMethod(taskView, "setGridTranslationY", newTranslationY);
							}
						}
					}
				} catch (Throwable e) {
					Logger.log("Error inverting recents grid: " + e.getMessage());
				}
			});
		}

		// Block the grid layout in the launcher states to force the mobile carousel
		ReflectionConsumer displayOverviewTasksAsGridHook = param -> {
			if (mSettings.mobileRecents) {
				param.setResult(false);
			}
		};

		ReflectedClass.ofIfPossible("com.android.launcher3.uioverrides.states.OverviewState")
				.before("displayOverviewTasksAsGrid").run(displayOverviewTasksAsGridHook);

		ReflectedClass.ofIfPossible("com.android.launcher3.uioverrides.states.BackgroundAppState")
				.before("displayOverviewTasksAsGrid").run(displayOverviewTasksAsGridHook);

		ReflectedClass.ofIfPossible("com.android.quickstep.fallback.RecentsState")
				.before("displayOverviewTasksAsGrid").run(displayOverviewTasksAsGridHook);

		ReflectionConsumer isLargeScreenToggleHookBefore = param -> {
			if (mSettings.mobileRecents) {
				try {
					Object deviceProfile = param.args[1];
					Object deviceProperties = getObjectField(deviceProfile, "deviceProperties");
					setBooleanField(deviceProperties, "isLargeScreen", false);
				} catch (Throwable ignored) {
				}
			}
		};

		ReflectionConsumer isLargeScreenToggleHookAfter = param -> {
			if (mSettings.mobileRecents) {
				try {
					Object deviceProfile = param.args[1];
					Object deviceProperties = getObjectField(deviceProfile, "deviceProperties");
					setBooleanField(deviceProperties, "isLargeScreen", true);
				} catch (Throwable ignored) {
				}
			}
		};

		// Temporarily fool the size calculations into small-screen mode, then restore
		if (BaseContainerInterfaceClass.getClazz() != null) {
			BaseContainerInterfaceClass.before("calculateTaskSize").run(isLargeScreenToggleHookBefore);
			BaseContainerInterfaceClass.after("calculateTaskSize").run(isLargeScreenToggleHookAfter);
			BaseContainerInterfaceClass.before("calculateModalTaskSize").run(isLargeScreenToggleHookBefore);
			BaseContainerInterfaceClass.after("calculateModalTaskSize").run(isLargeScreenToggleHookAfter);

			BaseContainerInterfaceClass.before("calculateGridTaskSize").run(param -> {
				if (mSettings.mobileRecents) {
					try {
						Object context = param.args[0];
						Object deviceProfile = param.args[1];
						android.graphics.Rect rect = (android.graphics.Rect) param.args[2];
						Object recentsPagedOrientationHandler = param.args[3];
						Object baseContainer = param.thisObject;

						callMethod(baseContainer, "calculateTaskSize", context, deviceProfile, rect, recentsPagedOrientationHandler);
						param.setResult(null);
					} catch (Throwable ignored) {
					}
				}
			});
		}

		// Scale the header icons of the recents views
		if (TaskViewClass.getClazz() != null) {
			TaskViewClass.before("isGridTask").run(param -> {
				if (mSettings.mobileRecents) param.setResult(false);
			});
			TaskViewClass.after("onLayout").run(param -> {
				try {
					boolean isGridTask = (boolean) callMethod(param.thisObject, "isGridTask");
					float scale = isGridTask ? mSettings.gridHeaderScale : mSettings.mobileHeaderScale;
					if (scale == 1.0f) return;
					List<?> taskContainers = (List<?>) callMethod(param.thisObject, "getTaskContainers");
					if (taskContainers != null) {
						for (Object container : taskContainers) {
							View iconView = (View) callMethod(container, "getIconView");
							if (iconView != null) {
								iconView.setScaleX(scale);
								iconView.setScaleY(scale);
							}
						}
					}
				} catch (Throwable ignored) {
				}
			});
		}

		// Give the bottom mobile-recents UI room below the actions ("Clear all") view
		if (OverviewActionsViewClass.getClazz() != null) {
			OverviewActionsViewClass.before("updateHiddenFlags").run(param -> {
				if (mSettings.mobileRecents && (int) param.args[0] == 32) {
					param.args[1] = false;
				}
			});
			OverviewActionsViewClass.after("getBottomMargin").run(param -> {
				if (mSettings.mobileRecents) {
					int margin = (int) param.getResult();
					param.setResult(margin + 150);
				}
			});
		}
	}
}
