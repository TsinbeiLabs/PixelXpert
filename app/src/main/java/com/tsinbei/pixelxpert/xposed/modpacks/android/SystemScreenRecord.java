package com.tsinbei.pixelxpert.xposed.modpacks.android;


import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.callMethod;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.getObjectField;
import static com.tsinbei.pixelxpert.xposed.XPrefs.Xprefs;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;

import java.util.ArrayDeque;

import io.github.libxposed.api.XposedModuleInterface;
import com.tsinbei.pixelxpert.Constants;
import com.tsinbei.pixelxpert.xposed.XposedModPack;
import com.tsinbei.pixelxpert.xposed.annotations.FrameworkModPack;
import com.tsinbei.pixelxpert.xposed.utils.reflection.ReflectedClass;

@SuppressWarnings("RedundantThrows")
@FrameworkModPack
public class SystemScreenRecord extends XposedModPack {
	private static boolean InsecureScreenRecord = false;
	private static volatile boolean systemUiProjectionActive = false;
	private static final ThreadLocal<ArrayDeque<SecureFlagState>> secureFlagStates =
			ThreadLocal.withInitial(ArrayDeque::new);

	public SystemScreenRecord(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {
		InsecureScreenRecord = Xprefs.getBoolean("InsecureScreenRecord", false);
		if (!InsecureScreenRecord && systemUiProjectionActive) {
			systemUiProjectionActive = false;
			refreshSecureSurfaceState();
		}
	}

	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) throws Throwable {
		ReflectedClass MediaProjectionManagerServiceClass = ReflectedClass.of(
				"com.android.server.media.projection.MediaProjectionManagerService");
		ReflectedClass WindowStateClass = ReflectedClass.of("com.android.server.wm.WindowState");

		MediaProjectionManagerServiceClass
				.after("startProjectionLocked")
				.run(param -> scheduleProjectionStateUpdate(param.thisObject));

		MediaProjectionManagerServiceClass
				.after("stopProjectionLocked")
				.run(param -> scheduleProjectionStateUpdate(param.thisObject));

		WindowStateClass
				.before("isSecureLocked")
				.run(param -> {
					WindowManager.LayoutParams attrs = null;
					int originalFlags = 0;
					boolean modified = false;
					try {
						attrs = (WindowManager.LayoutParams) getObjectField(param.thisObject, "mAttrs");
						originalFlags = attrs.flags;
						if (InsecureScreenRecord && systemUiProjectionActive
								&& (originalFlags & WindowManager.LayoutParams.FLAG_SECURE) != 0) {
							attrs.flags = originalFlags & ~WindowManager.LayoutParams.FLAG_SECURE;
							modified = true;
						}
					} finally {
						secureFlagStates.get().addLast(new SecureFlagState(attrs, originalFlags, modified));
					}
				});

		WindowStateClass
				.after("isSecureLocked")
				.run(param -> {
					ArrayDeque<SecureFlagState> states = secureFlagStates.get();
					if (states.isEmpty()) return;

					SecureFlagState state = states.removeLast();
					if (state.modified) state.attrs.flags = state.originalFlags;
					if (states.isEmpty()) secureFlagStates.remove();
				});
	}

	private void scheduleProjectionStateUpdate(Object service) {
		Handler handler;
		try {
			handler = (Handler) getObjectField(service, "mHandler");
		} catch (Throwable ignored) {
			handler = new Handler(Looper.getMainLooper());
		}

		handler.post(() -> {
			boolean active = false;
			if (InsecureScreenRecord) {
				try {
					Object projection = getObjectField(service, "mProjectionGrant");
					active = projection != null && Constants.SYSTEM_UI_PACKAGE.equals(
							getObjectField(projection, "packageName"));
				} catch (Throwable ignored) {
				}
			}

			if (systemUiProjectionActive != active) {
				systemUiProjectionActive = active;
				refreshSecureSurfaceState();
			}
		});
	}

	private void refreshSecureSurfaceState() {
		try {
			Class<?> windowManagerInternalClass = ReflectedClass.of(
					"com.android.server.wm.WindowManagerInternal").getClazz();
			Object windowManagerInternal = ReflectedClass.of("com.android.server.LocalServices")
					.callStaticMethod("getService", windowManagerInternalClass);
			callMethod(windowManagerInternal, "refreshScreenCaptureDisabled");
		} catch (Throwable ignored) {
		}
	}

	private record SecureFlagState(
			WindowManager.LayoutParams attrs, int originalFlags, boolean modified) {
	}
}
