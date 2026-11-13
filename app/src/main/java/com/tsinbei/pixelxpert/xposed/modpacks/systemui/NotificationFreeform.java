/* Adapted from NativeComplements NotificationWindow (Apache-2.0).
 * Changes: native AOSP ActivityOptions instead of its custom window engine,
 * shared preferences, and foreground selection excluding freeform tasks. */
package com.tsinbei.pixelxpert.xposed.modpacks.systemui;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Process;
import android.service.notification.StatusBarNotification;
import android.view.Display;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface;
import com.tsinbei.pixelxpert.xposed.XposedModPack;
import com.tsinbei.pixelxpert.xposed.annotations.SystemUIModPack;
import com.tsinbei.pixelxpert.xposed.utils.FreeformBounds;
import com.tsinbei.pixelxpert.xposed.utils.reflection.ReflectedClass;
import static com.tsinbei.pixelxpert.xposed.XPrefs.Xprefs;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.callMethod;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.getObjectField;

@SystemUIModPack
public class NotificationFreeform extends XposedModPack {
	private volatile boolean enabled;
	private final ThreadLocal<Click> current = new ThreadLocal<>();
	private record Click(StatusBarNotification notice, PendingIntent pending) { }
	public NotificationFreeform(Context context) { super(context); }
	@Override public void onPreferenceUpdated(String... keys) {
		enabled = Xprefs.getBoolean("NotificationFreeform", false);
	}

	@Override public void onPackageLoaded(XposedModuleInterface.PackageReadyParam param) {
		List<XposedInterface.HookHandle> hooks = new ArrayList<>();
		try {
			String base = "com.android.systemui.statusbar.phone.StatusBarNotificationActivityStarter";
			Class<?> runner = ReflectedClass.of(base + "$$ExternalSyntheticLambda7").getClazz();
			Class<?> sender = ReflectedClass.of(base + "$$ExternalSyntheticLambda10").getClazz();
			Class<?> entry = ReflectedClass.of("com.android.systemui.statusbar.notification.collection.NotificationEntry").getClazz();
			Field runEntry = field(runner, "f$1", entry), runPending = field(runner, "f$3", PendingIntent.class);
			Field animated = field(runner, "f$5", boolean.class), sbn = field(entry, "mSbn", StatusBarNotification.class);
			Field startEntry = field(sender, "f$5", entry), startPending = field(sender, "f$3", PendingIntent.class);
			Field fill = field(sender, "f$4", Intent.class), display = field(sender, "f$2", int.class);
			Method run = runner.getDeclaredMethod("run");
			Method start = sender.getDeclaredMethod("startPendingIntent", ReflectedClass.of("android.view.RemoteAnimationAdapter").getClazz());
			if (run.getReturnType() != void.class || start.getReturnType() != int.class) throw new NoSuchMethodException("notification routing");
			hooks.add(ReflectedClass.getDefaultXposedInterface().hook(run).intercept(chain -> {
				Object owner = chain.getThisObject();
				Click previous = current.get(), selected = null;
				boolean wasAnimated = false;
				try {
					StatusBarNotification notice = (StatusBarNotification) sbn.get(runEntry.get(owner));
					PendingIntent pending = (PendingIntent) runPending.get(owner);
					if (eligible(notice, pending)) {
						selected = new Click(notice, pending);
						wasAnimated = animated.getBoolean(owner);
						animated.setBoolean(owner, false);
						current.set(selected);
					}
				} catch (Exception failure) { log("NotificationFreeform: native click retained", failure); }
				try { return chain.proceed(); }
				finally {
					if (selected != null) {
						try { animated.setBoolean(owner, wasAnimated); }
						finally { if (previous == null) current.remove(); else current.set(previous); }
					}
				}
			}));
			hooks.add(ReflectedClass.getDefaultXposedInterface().hook(start).intercept(chain -> {
				Click click = current.get();
				Object owner = chain.getThisObject();
				ActivityOptions options = null;
				Intent fillIntent = null;
				try {
					if (click != null && enabled && display.getInt(owner) == Display.DEFAULT_DISPLAY
							&& click.pending.equals(startPending.get(owner))
							&& click.notice.equals(sbn.get(startEntry.get(owner))) && eligible(click.notice, click.pending)) {
						options = ActivityOptions.makeBasic();
						callMethod(options, "setLaunchWindowingMode", 5);
						options.setLaunchDisplayId(Display.DEFAULT_DISPLAY);
						options.setLaunchBounds(FreeformBounds.forDisplay(mContext));
						options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
						fillIntent = (Intent) fill.get(owner);
					}
				} catch (Exception failure) {
					log("NotificationFreeform: native launch retained", failure);
					return chain.proceed();
				}
				if (options == null) return chain.proceed();
				try {
					click.pending.send(mContext, 0, fillIntent, null, null, null, options.toBundle());
					return 0;
				} catch (PendingIntent.CanceledException | RuntimeException failure) {
					log("NotificationFreeform: launch failed", failure);
					return -96; // ActivityManager.START_CANCELED; a send is never retried.
				}
			}));
			ReflectedClass.deoptimize(run);
		} catch (Throwable failure) {
			for (XposedInterface.HookHandle hook : hooks) hook.unhook();
			log("NotificationFreeform: target unavailable", failure);
		}
	}

	private static Field field(Class<?> type, String name, Class<?> expected) throws ReflectiveOperationException {
		Field field = type.getDeclaredField(name);
		if (field.getType() != expected) throw new NoSuchFieldException(name);
		field.setAccessible(true);
		return field;
	}

	private boolean eligible(StatusBarNotification notice, PendingIntent pending) {
		if (!enabled || notice == null || pending == null || !pending.isActivity()
				|| !Process.myUserHandle().equals(notice.getUser())
				|| !Process.myUserHandle().equals(pending.getCreatorUserHandle())) return false;
		Notification notification = notice.getNotification();
		if (java.util.Set.of("android", "com.android.systemui", com.tsinbei.pixelxpert.BuildConfig.APPLICATION_ID).contains(notice.getPackageName())) return false;
		if (!pending.equals(notification.contentIntent) || (notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0
				|| notification.fullScreenIntent != null || Notification.CATEGORY_CALL.equals(notification.category)
				|| Notification.CATEGORY_ALARM.equals(notification.category)) return false;
		String foreground = foregroundPackage();
		return foreground != null && !foreground.equals(notice.getPackageName());
	}

	private String foregroundPackage() {
		for (ActivityManager.RunningTaskInfo task : mContext.getSystemService(ActivityManager.class).getRunningTasks(10)) {
			Object configuration = getObjectField(getObjectField(task, "configuration"), "windowConfiguration");
			if ((int) callMethod(configuration, "getWindowingMode") != 1) continue;
			if (task.topActivity != null && (int) getObjectField(task, "displayId") == Display.DEFAULT_DISPLAY) return task.topActivity.getPackageName();
		}
		return null;
	}
}
