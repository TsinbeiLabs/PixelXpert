package com.tsinbei.pixelxpert.xposed.modpacks.launcher.mods;

import android.content.Context;

import com.tsinbei.pixelxpert.xposed.modpacks.launcher.TaskbarActivator;
import com.tsinbei.pixelxpert.xposed.utils.reflection.ReflectedClass;
import com.tsinbei.pixelxpert.xposed.utils.toolkit.Logger;

import java.util.ArrayList;
import java.util.List;

import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.callMethod;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.callStaticMethod;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.findClass;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.getIntField;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.getObjectField;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.setBooleanField;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.setIntField;

/**
 * Controls how many icons the taskbar dock shows and prepares DeviceProfile on
 * Android 17 where the profile is constructed directly.
 * Adapted from Pixel Taskbar Enabler by beyman-dev (GPLv3).
 */
public class IconsMod extends BaseLauncherMod {

	public IconsMod(TaskbarActivator activator, Context context, TaskbarSettings settings) {
		super(activator, context, settings);
	}

	@Override
	public void applyHooks() throws Throwable {
		ReflectedClass TaskbarViewClass = ReflectedClass.ofIfPossible("com.android.launcher3.taskbar.TaskbarView");
		ReflectedClass TaskbarModelCallbacksClass = ReflectedClass.ofIfPossible("com.android.launcher3.taskbar.TaskbarModelCallbacks");
		ReflectedClass TaskbarActivityContextClass = ReflectedClass.ofIfPossible("com.android.launcher3.taskbar.TaskbarActivityContext");
		ReflectedClass DeviceProfileClass = ReflectedClass.ofIfPossible("com.android.launcher3.DeviceProfile");

		// Maximum number of icons shown on the taskbar
		if (TaskbarViewClass.getClazz() != null) {
			TaskbarViewClass.after("calculateMaxNumIcons").run(param -> {
				if (mSettings.taskbarMode == TaskbarSettings.TASKBAR_ON) {
					param.setResult(mSettings.taskbarIconCount + 3);
				}
			});
		}

		if (TaskbarModelCallbacksClass.getClazz() != null) {
			TaskbarModelCallbacksClass.before("commitHotseatItemUpdates").run(param -> {
				if (mSettings.taskbarMode != TaskbarSettings.TASKBAR_ON) return;

				Object[] originalPinned = (Object[]) param.args[0];
				if (originalPinned == null) return;

				// Limit pinned apps based on the icon count setting
				Object[] newPinned = originalPinned.clone();
				int pinnedCount = 0;
				for (int i = 0; i < newPinned.length; i++) {
					if (newPinned[i] != null) {
						if (pinnedCount >= mSettings.taskbarIconCount) {
							newPinned[i] = null; // Remove excess pinned apps
						} else {
							pinnedCount++;
						}
					}
				}
				param.args[0] = newPinned;

				// Recents
				int maxRecent = Math.max(0, mSettings.taskbarIconCount - pinnedCount);
				int recentCount = 0;
				try {
					Object mControllers = getObjectField(param.thisObject, "mControllers");
					Object taskbarRecentAppsController = getObjectField(mControllers, "taskbarRecentAppsController");
					List<?> allRecentTasks = (List<?>) getObjectField(taskbarRecentAppsController, "allRecentTasks");

					List<Object> filteredRecents = new ArrayList<>();
					if (allRecentTasks != null) {
						for (Object groupTask : allRecentTasks) {
							if (filteredRecents.size() >= maxRecent) break;

							boolean isPinned = false;

							try {
								List<?> runningTaskIds = (List<?>) getObjectField(taskbarRecentAppsController, "orderedRunningTaskIds");
								if (runningTaskIds != null) {
									for (Object taskId : runningTaskIds) {
										Boolean contains = (Boolean) callMethod(groupTask, "containsTask", taskId);
										if (contains != null && contains) {
											isPinned = true;
											break;
										}
									}
								}
							} catch (Throwable ignored) { }

							if (isPinned) continue;

							for (Object pinnedItem : newPinned) {
								if (pinnedItem != null) {
									String pinnedPackage = (String) callMethod(pinnedItem, "getTargetPackage");
									Object userHandle = getObjectField(pinnedItem, "user");
									int pinnedUserId = (int) callMethod(userHandle, "getIdentifier");

									Boolean contains = (Boolean) callMethod(groupTask, "containsPackage", pinnedPackage, pinnedUserId);
									if (contains != null && contains) {
										isPinned = true;
										break;
									}
								}
							}
							if (!isPinned) {
								filteredRecents.add(groupTask);
							}
						}
					}
					param.args[1] = filteredRecents;
					recentCount = filteredRecents.size();

					// Force TaskbarRecentAppsController to refresh its own list so it
					// loads and assigns the icon bitmaps correctly in the UI.
					com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.setObjectField(taskbarRecentAppsController, "shownTasks", filteredRecents);

					try {
						callMethod(taskbarRecentAppsController, "fetchIcons", true);
					} catch (Throwable t2) {
						try {
							callMethod(taskbarRecentAppsController, "fetchIcons", false);
						} catch (Throwable ignored) {
						}
					}
				} catch (Throwable t) {
					List<?> recent = (List<?>) param.args[1];
					if (recent != null && recent.size() > maxRecent) {
						param.args[1] = new ArrayList<>(recent.subList(0, maxRecent));
					}
					recentCount = (recent == null) ? 0 : Math.min(recent.size(), maxRecent);
				}

				// Suggestions (Handoff)
				List<?> suggestions = (List<?>) param.args[2];
				int maxSuggestions = Math.max(0, mSettings.taskbarIconCount - pinnedCount - recentCount);
				if (suggestions != null && suggestions.size() > maxSuggestions) {
					param.args[2] = new ArrayList<>(suggestions.subList(0, maxSuggestions));
				}
			});
		}

		if (DeviceProfileClass.getClazz() != null) {
			// A17+: DeviceProfile is constructed directly; force the tablet/taskbar flags
			// and inject a real TaskbarProfile before the constructor runs.
			DeviceProfileClass.beforeConstruction().run(param -> {
				if (mSettings.taskbarMode != TaskbarSettings.TASKBAR_ON) return;
				try {
					if (param.args.length >= 15) { // Android 17+
						Object launcherDisplayInfo = param.args[1];
						Object deviceProperties = param.args[2];
						Object displayOptionSpec = param.args[4];

						if (deviceProperties != null) {
							trySetBoolean(deviceProperties, "isLargeScreen", true);
							trySetBoolean(deviceProperties, "isPhone", false);
							Object taskbarConfiguration = tryGetObjectField(deviceProperties, "taskbarConfiguration");
							if (taskbarConfiguration != null) {
								trySetBoolean(taskbarConfiguration, "isTaskbarPresent", true);
							}
						}

						Context ctx = (Context) tryGetObjectField(launcherDisplayInfo, "context");
						if (ctx != null) {
							Class<?> taskbarFactoryClass = findClass("com.android.launcher3.deviceprofile.TaskbarProfile$Factory", launcherDisplayInfo.getClass().getClassLoader());
							Object newTaskbarProfile = callStaticMethod(
									taskbarFactoryClass,
									"createTaskbarProfile",
									ctx.getResources(),
									true, // isTransientTaskbar
									true, // isTaskbarPresent
									displayOptionSpec
							);
							param.args[14] = newTaskbarProfile;
						}
					}
				} catch (Throwable e) {
					Logger.log("TaskbarActivator Android 17 injection failed: " + e.getMessage());
				}
			});

			DeviceProfileClass.afterConstruction().run(param -> {
				if (mSettings.taskbarMode == TaskbarSettings.TASKBAR_DEFAULT) return;

				if (mSettings.taskbarMode == TaskbarSettings.TASKBAR_ON) {
					mActivator.setDeviceProfile(param.thisObject);

					trySetBoolean(param.thisObject, "isTablet", true);
					trySetBoolean(param.thisObject, "isTaskbarPresent", true);
					trySetBoolean(param.thisObject, "isPhone", false);

					try {
						Object overviewProfile = getObjectField(param.thisObject, "overviewProfile");
						if (overviewProfile != null) {
							int currentRowSpacing = getIntField(overviewProfile, "rowSpacing");
							setIntField(overviewProfile, "rowSpacing", currentRowSpacing + 100);
						}
					} catch (Throwable ignored) {
					}
				}
			});
		}

		if (TaskbarActivityContextClass.getClazz() != null) {
			TaskbarActivityContextClass
					.afterConstruction()
					.run(param -> {
						if (mSettings.taskbarMode != TaskbarSettings.TASKBAR_ON) return;
						try {
							Object taskbarDeviceProfile = tryGetObjectField(param.thisObject, "mDeviceProfile");
							if (taskbarDeviceProfile == null) return;

							Object hotseatProfile = tryGetObjectField(taskbarDeviceProfile, "hotseatProfile");
							if (hotseatProfile != null) {
								setIntField(hotseatProfile, "numShownIcons", mSettings.taskbarIconCount);
							}

							Object mHotseatProfile = tryGetObjectField(taskbarDeviceProfile, "mHotseatProfile");
							if (mHotseatProfile != null) {
								setIntField(mHotseatProfile, "numShownIcons", mSettings.taskbarIconCount);
							}
						} catch (Throwable ignored) {
						}
					});
		}
	}

	private static void trySetBoolean(Object target, String field, boolean value) {
		try {
			setBooleanField(target, field, value);
		} catch (Throwable ignored) {
		}
	}

	private static Object tryGetObjectField(Object target, String field) {
		try {
			return getObjectField(target, field);
		} catch (Throwable ignored) {
			return null;
		}
	}
}
