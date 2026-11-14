package com.tsinbei.pixelxpert.xposed.modpacks.systemui;

import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.callMethod;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.getObjectField;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.setObjectField;
import static com.tsinbei.pixelxpert.xposed.XPrefs.Xprefs;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.service.quicksettings.Tile;
import android.view.HapticFeedbackConstants;
import android.view.View;

import io.github.libxposed.api.XposedModuleInterface;
import com.tsinbei.pixelxpert.BuildConfig;
import com.tsinbei.pixelxpert.R;
import com.tsinbei.pixelxpert.xposed.XPLauncher;
import com.tsinbei.pixelxpert.service.tileServices.HighBrightnessFlashlightTileService;
import com.tsinbei.pixelxpert.xposed.XposedModPack;
import com.tsinbei.pixelxpert.xposed.annotations.SystemUIModPack;
import com.tsinbei.pixelxpert.xposed.utils.HighBrightnessTorchController;
import com.tsinbei.pixelxpert.xposed.utils.SystemUtils;
import com.tsinbei.pixelxpert.xposed.utils.reflection.ReflectedClass;
import com.tsinbei.pixelxpert.xposed.utils.toolkit.Logger;

/**
 * High Brightness Flashlight QS tile: cycles the torch beyond standard system limits
 * using the Pixel experimental Camera2 API. Adapted from PixelLight by Andrew Gunnerson.
 */
@SystemUIModPack
public class HighBrightnessFlashlightTile extends XposedModPack {
	private static final String TAG = "HighBrightnessFlashlightTile";

	private Object mTile = null;

	public HighBrightnessFlashlightTile(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {
	}

	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) throws Throwable {
		ReflectedClass CustomTileClass = ReflectedClass.ofIfPossible("com.android.systemui.qs.external.CustomTile");
		ReflectedClass QSFactoryImplClass = ReflectedClass.ofIfPossible("com.android.systemui.qs.tileimpl.QSFactoryImpl");
		ReflectedClass QSTileImplClass = ReflectedClass.ofIfPossible("com.android.systemui.qs.tileimpl.QSTileImpl");
		if (CustomTileClass.getClazz() == null || QSFactoryImplClass.getClazz() == null || QSTileImplClass.getClazz() == null) return;

		QSFactoryImplClass
				.after("createTile")
				.run(param -> {
					String arg = (String) param.args[0];
					if (arg != null && arg.contains(HighBrightnessFlashlightTileService.class.getSimpleName())) {
						Object result = param.getResult();
						if (result != null) {
							mTile = result;
							updateTile();
						}
					}
				});

		CustomTileClass
				.before("handleClick")
				.run(param -> {
					if (param.thisObject == mTile) {
						Object arg = param.args != null && param.args.length > 0 ? param.args[0] : null;
						handleTileClick(arg);
						param.setResult(null);
					}
				});

		QSTileImplClass
				.before("handleLongClick")
				.run(param -> {
					if (param.thisObject == mTile) {
						Object arg = param.args != null && param.args.length > 0 ? param.args[0] : null;
						handleTileLongClick(arg);
						param.setResult(null);
					}
				});

		CustomTileClass
				.after("handleUpdateState")
				.run(param -> {
					if (param.thisObject == mTile) {
						Object state = param.args[0];

						boolean isSupported = HighBrightnessTorchController.getInstance() != null && HighBrightnessTorchController.getInstance().isSupported();
						boolean isOn = HighBrightnessTorchController.getInstance() != null && HighBrightnessTorchController.getInstance().isOn();

						try {
							setObjectField(state, "value", isOn);
						} catch (Throwable ignored) {
							//some SysUI versions don't use QSTile.BooleanState for non-toggleable TileServices
						}

						if (!isSupported) {
							setObjectField(state, "state", Tile.STATE_UNAVAILABLE);
							setObjectField(state, "secondaryLabel", "Not Supported");
						} else {
							setObjectField(state, "state", isOn ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
							if (isOn) {
								int level = HighBrightnessTorchController.getInstance().getCurrentBrightness();
								int max = HighBrightnessTorchController.getInstance().getMaxBrightness();
								setObjectField(state, "secondaryLabel", "Level " + level + "/" + max);
							} else {
								setObjectField(state, "secondaryLabel", null);
							}
						}
					}
				});

		CustomTileClass
				.before("getLongClickIntent")
				.run(param -> {
					if (param.thisObject == mTile) {
						Intent intent = new Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS);
						intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
						param.setResult(intent);
					}
				});
	}

	private void handleTileClick(Object arg) {
		triggerHapticFeedback(arg);
		HighBrightnessTorchController controller = HighBrightnessTorchController.getInstance();
		if (controller == null || !controller.isSupported()) return;

		if (controller.isOn()) {
			int current = controller.getCurrentBrightness();
			int max = controller.getMaxBrightness();
			if (current >= max) {
				Xprefs.edit().putInt("high_brightness_flashlight_level", 16).apply();
				controller.closeCamera();
			} else {
				int next = Math.min(current + 16, max);
				controller.setBrightness(next);
			}
		} else {
			int last = Xprefs.getInt("high_brightness_flashlight_level", 16);
			controller.setBrightness(last);
		}
		updateTile();
	}

	private void handleTileLongClick(Object arg) {
		triggerHapticFeedback(arg);
		HighBrightnessTorchController controller = HighBrightnessTorchController.getInstance();
		if (controller == null || !controller.isSupported()) return;

		if (controller.isOn()) {
			controller.closeCamera();
		} else {
			controller.setBrightness(controller.getMaxBrightness());
		}
		updateTile();
	}

	private void triggerHapticFeedback(Object arg) {
		boolean performed = false;
		try {
			View view = null;
			if (arg instanceof View) {
				view = (View) arg;
			} else if (arg != null) {
				try {
					Object v = callMethod(arg, "asView");
					if (v instanceof View) {
						view = (View) v;
					}
				} catch (Throwable ignored) {
				}
			}

			if (view != null) {
				performed = view.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
				if (!performed) {
					performed = view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
				}
			}
		} catch (Throwable ignored) {
		}

		if (!performed) {
			try {
				SystemUtils.vibrate(VibrationEffect.EFFECT_CLICK, VibrationAttributes.USAGE_TOUCH);
			} catch (Throwable ignored) {
			}
		}
	}

	private void updateTile() {
		if (this.mTile == null) return;
		try {
			Tile tile = (Tile) getObjectField(this.mTile, "mTile");
			if (tile == null) return;

			HighBrightnessTorchController controller = HighBrightnessTorchController.getInstance();
			boolean isSupported = controller != null && controller.isSupported();
			boolean isOn = controller != null && controller.isOn();

			tile.setIcon(Icon.createWithResource(BuildConfig.APPLICATION_ID, R.drawable.ic_qs_flashlight));

			if (!isSupported) {
				tile.setState(Tile.STATE_UNAVAILABLE);
				tile.setSubtitle("Not Supported");
			} else {
				tile.setState(isOn ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
				if (isOn) {
					int level = controller.getCurrentBrightness();
					int max = controller.getMaxBrightness();
					tile.setSubtitle("Level " + level + "/" + max);
				} else {
					tile.setSubtitle(null);
				}
			}

			String label = XPLauncher.moduleResources.getString(R.string.high_brightness_flashlight_tile_title);
			tile.setContentDescription(label);

			callMethod(this.mTile, "refreshState", new Object[]{null});
		} catch (Throwable t) {
			Logger.log(TAG + ": failed to updateTile: " + t);
		}
	}
}
