package com.tsinbei.pixelxpert.xposed.modpacks.systemui;

import static com.tsinbei.pixelxpert.xposed.XPrefs.Xprefs;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import io.github.libxposed.api.XposedModuleInterface;
import com.tsinbei.pixelxpert.R;
import com.tsinbei.pixelxpert.xposed.XPLauncher;
import com.tsinbei.pixelxpert.xposed.XposedModPack;
import com.tsinbei.pixelxpert.xposed.annotations.SystemUIModPack;
import com.tsinbei.pixelxpert.xposed.utils.SystemUtils;
import com.tsinbei.pixelxpert.xposed.utils.reflection.ReflectedClass;
import com.tsinbei.pixelxpert.xposed.utils.toolkit.Logger;

/**
 * Sensitive lockscreen action protection adapted from TouchMeNot by Dhananjaya K.
 * Rewritten for PixelXpert's preferences, reflection helpers, and Pixel SystemUI.
 */
@SystemUIModPack
public class LockScreenActionBlocker extends XposedModPack {
	private static final VibrationEffect REJECT_VIBRATION =
			VibrationEffect.createWaveform(new long[]{0, 40, 50, 40}, -1);

	private static boolean blockInternet;
	private static boolean blockAirplane;
	private static boolean blockBluetooth;
	private static boolean blockHotspot;
	private static boolean blockDnd;
	private static boolean blockRingerMode;

	private final Set<Method> hookedMethods = new HashSet<>();

	public LockScreenActionBlocker(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {
		blockInternet = Xprefs.getBoolean("LockScreenBlockInternet", false);
		blockAirplane = Xprefs.getBoolean("LockScreenBlockAirplane", false);
		blockBluetooth = Xprefs.getBoolean("LockScreenBlockBluetooth", false);
		blockHotspot = Xprefs.getBoolean("LockScreenBlockHotspot", false);
		blockDnd = Xprefs.getBoolean("LockScreenBlockDnd", false);
		blockRingerMode = Xprefs.getBoolean("LockScreenBlockRingerMode", false);
	}

	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) {
		hookTile("com.android.systemui.qs.tiles.InternetTile", Action.INTERNET);
		hookTile("com.android.systemui.qs.tiles.InternetTileNewImpl", Action.INTERNET);
		hookTile("com.android.systemui.qs.tiles.CellularTile", Action.INTERNET);
		hookTile("com.android.systemui.qs.tiles.MobileDataTile", Action.INTERNET);
		hookTile("com.android.systemui.qs.tiles.WifiTile", Action.INTERNET);
		hookTile("com.android.systemui.qs.tiles.AirplaneModeTile", Action.AIRPLANE);
		hookTile("com.android.systemui.qs.tiles.BluetoothTile", Action.BLUETOOTH);
		hookTile("com.android.systemui.qs.tiles.HotspotTile", Action.HOTSPOT);
		hookTile("com.android.systemui.qs.tiles.DndTile", Action.DND);
		hookModernTile("com.android.systemui.qs.tiles.impl.cellular.domain.interactor.CellularTileUserActionInteractor", Action.INTERNET);
		hookModernTile("com.android.systemui.qs.tiles.impl.internet.domain.interactor.InternetTileUserActionInteractor", Action.INTERNET);
		hookModernTile("com.android.systemui.qs.tiles.impl.airplane.domain.interactor.AirplaneModeTileUserActionInteractor", Action.AIRPLANE);
		hookModernTile("com.android.systemui.qs.tiles.impl.bluetooth.domain.interactor.BluetoothTileUserActionInteractor", Action.BLUETOOTH);
		hookModernTile("com.android.systemui.qs.tiles.impl.hotspot.domain.interactor.HotspotTileUserActionInteractor", Action.HOTSPOT);
		hookModernTile("com.android.systemui.qs.tiles.impl.dnd.domain.interactor.DndTileUserActionInteractor", Action.DND);
		hookRingerMode();
	}

	private void hookTile(String className, Action action) {
		try {
			ReflectedClass tileClass = ReflectedClass.ofIfPossible(className);
			if (tileClass.getClazz() == null) return;

			for (Method method : tileClass.getClazz().getDeclaredMethods()) {
				String name = method.getName();
				if (name.equals("click")
						|| name.equals("handleClick")
						|| name.equals("handleSecondaryClick")
						|| name.equals("handleLongClick")
						|| name.equals("handleClickWithSatelliteCheck")) {
					hookAction(method, action);
				}
			}
		} catch (Throwable t) {
			Logger.log("LockScreenActionBlocker: failed to hook " + className, t);
		}
	}

	private void hookModernTile(String className, Action action) {
		try {
			ReflectedClass tileClass = ReflectedClass.ofIfPossible(className);
			if (tileClass.getClazz() == null) return;

			for (Method method : tileClass.getClazz().getDeclaredMethods()) {
				if (method.getName().equals("handleInput")) hookAction(method, action);
			}
		} catch (Throwable t) {
			Logger.log("LockScreenActionBlocker: failed to hook " + className, t);
		}
	}

	private void hookAction(Method method, Action action) {
		if (method.getReturnType().isPrimitive() && method.getReturnType() != Void.TYPE) return;
		if (!hookedMethods.add(method)) return;

		ReflectedClass.deoptimize(method);
		ReflectedClass.of(method.getDeclaringClass()).before(method).run(param -> {
			if (action.isBlocked() && isDeviceLocked()) {
				rejectAction();
				param.setResult(null);
			}
		});
	}

	private void hookRingerMode() {
		try {
			ReflectedClass controller = ReflectedClass.ofIfPossible(
					"com.android.systemui.volume.VolumeDialogControllerImpl");
			if (controller.getClazz() == null) return;

			for (Method method : controller.getClazz().getDeclaredMethods()) {
				if (!method.getName().equals("setRingerMode") || method.getReturnType() != Void.TYPE) continue;
				ReflectedClass.deoptimize(method);
				controller.before(method).run(param -> {
					if (blockRingerMode && isDeviceLocked()) {
						rejectAction();
						param.setResult(null);
					}
				});
			}
		} catch (Throwable t) {
			Logger.log("LockScreenActionBlocker: failed to hook ringer mode", t);
		}
	}

	private boolean isDeviceLocked() {
		KeyguardManager keyguardManager = mContext.getSystemService(KeyguardManager.class);
		return keyguardManager != null && keyguardManager.isKeyguardLocked();
	}

	private void rejectAction() {
		new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(
				mContext,
				XPLauncher.moduleResources.getString(R.string.lockscreen_unlock_to_use),
				Toast.LENGTH_SHORT).show());
		SystemUtils.vibrate(REJECT_VIBRATION, null);
	}

	private enum Action {
		INTERNET,
		AIRPLANE,
		BLUETOOTH,
		HOTSPOT,
		DND;

		boolean isBlocked() {
			return switch (this) {
				case INTERNET -> blockInternet;
				case AIRPLANE -> blockAirplane;
				case BLUETOOTH -> blockBluetooth;
				case HOTSPOT -> blockHotspot;
				case DND -> blockDnd;
			};
		}
	}
}
