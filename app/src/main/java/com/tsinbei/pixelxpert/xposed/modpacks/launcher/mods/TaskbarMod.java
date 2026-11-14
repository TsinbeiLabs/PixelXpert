package com.tsinbei.pixelxpert.xposed.modpacks.launcher.mods;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.view.MotionEvent;
import android.view.View;

import com.tsinbei.pixelxpert.xposed.modpacks.launcher.TaskbarActivator;
import com.tsinbei.pixelxpert.xposed.utils.reflection.ReflectedClass;

import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.getBooleanField;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.getObjectField;

/**
 * Forces the taskbar to appear on phones and scales the whole taskbar layer.
 * Adapted from Pixel Taskbar Enabler by beyman-dev (GPLv3).
 */
public class TaskbarMod extends BaseLauncherMod {

	public TaskbarMod(TaskbarActivator activator, Context context, TaskbarSettings settings) {
		super(activator, context, settings);
	}

	private boolean isTransientTaskbar(Object context) {
		if (context == null) return false;
		try {
			return getBooleanField(context, "mIsTransient");
		} catch (Throwable t) {
			return false;
		}
	}

	@Override
	public void applyHooks() throws Throwable {
		ReflectedClass FlagsClass = ReflectedClass.ofIfPossible("com.android.launcher3.Flags");
		ReflectedClass TaskbarDragLayerClass = ReflectedClass.ofIfPossible("com.android.launcher3.taskbar.TaskbarDragLayer");
		ReflectedClass BaseDragLayerClass = ReflectedClass.ofIfPossible("com.android.launcher3.views.BaseDragLayer");
		ReflectedClass TaskbarStashControllerClass = ReflectedClass.ofIfPossible("com.android.launcher3.taskbar.TaskbarStashController");

		// A15+ native flag to force the taskbar on phones
		if (FlagsClass.getClazz() != null) {
			FlagsClass.before("enableTaskbarOnPhones").run(param -> {
				if (mSettings.taskbarMode == TaskbarSettings.TASKBAR_ON) param.setResult(true);
			});
		}

		// Scale the whole taskbar layer through the Canvas. This avoids the double
		// scaling and edge clipping that View.setScaleX/Y caused.
		if (TaskbarDragLayerClass.getClazz() != null) {
			TaskbarDragLayerClass
					.before("dispatchDraw")
					.run(param -> {
						if (mSettings.taskbarMode == TaskbarSettings.TASKBAR_DEFAULT) return;

						View view = (View) param.thisObject;
						if (!isTransientTaskbar(view.getContext())) return;

						float scale = mSettings.taskbarScale;
						if (scale != 1.0f) {
							Canvas canvas = (Canvas) param.args[0];
							canvas.save();
							canvas.scale(scale, scale, view.getWidth() / 2f, view.getHeight());
						}
					});

			TaskbarDragLayerClass
					.after("dispatchDraw")
					.run(param -> {
						if (mSettings.taskbarMode == TaskbarSettings.TASKBAR_DEFAULT) return;

						View view = (View) param.thisObject;
						if (!isTransientTaskbar(view.getContext())) return;

						float scale = mSettings.taskbarScale;
						if (scale != 1.0f) {
							Canvas canvas = (Canvas) param.args[0];
							canvas.restore();
						}
					});
		}

		// With the layer scaled, physical touches no longer match the visual location:
		// apply the inverse matrix to touch events.
		if (BaseDragLayerClass.getClazz() != null) {
			BaseDragLayerClass
					.before("dispatchTouchEvent")
					.run(param -> {
						if (mSettings.taskbarMode == TaskbarSettings.TASKBAR_DEFAULT) return;

						View view = (View) param.thisObject;
						if (!isTransientTaskbar(view.getContext())) return;

						float scale = mSettings.taskbarScale;
						if (scale != 1.0f) {
							if (view.getClass().getName().contains("TaskbarDragLayer")) {
								MotionEvent event = (MotionEvent) param.args[0];
								Matrix m = new Matrix();
								m.setScale(1f / scale, 1f / scale, view.getWidth() / 2f, view.getHeight());
								event.transform(m);
							}
						}
					});
		}

		// A visually smaller taskbar must also report a smaller inset so apps can use the space.
		if (TaskbarStashControllerClass.getClazz() != null) {
			TaskbarStashControllerClass
					.after("getContentHeightToReportToApps")
					.run(param -> {
						if (mSettings.taskbarMode == TaskbarSettings.TASKBAR_DEFAULT) return;

						Object activity = getObjectField(param.thisObject, "mActivity");
						if (!isTransientTaskbar(activity)) return;

						float scale = mSettings.taskbarScale;
						if (scale != 1.0f) {
							int result = (int) param.getResult();
							param.setResult(Math.round(result * scale));
						}
					});
		}
	}
}
