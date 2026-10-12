package com.tsinbei.pixelxpert.xposed.modpacks.systemui;

import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.callMethod;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.findFieldIfExists;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.getObjectField;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.setObjectField;
import static com.tsinbei.pixelxpert.xposed.XPrefs.Xprefs;


import android.content.Context;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import io.github.libxposed.api.XposedModuleInterface;
import com.tsinbei.pixelxpert.xposed.XposedModPack;
import com.tsinbei.pixelxpert.xposed.annotations.SystemUIModPack;
import com.tsinbei.pixelxpert.xposed.utils.reflection.ReflectedClass;
import com.tsinbei.pixelxpert.xposed.utils.reflection.ReflectedClass.ReflectionConsumer;

@SuppressWarnings("RedundantThrows")
@SystemUIModPack
public class KeyGuardPinScrambler extends XposedModPack {
	private static boolean shufflePinEnabled = false;
	private static final List<Integer> composeDigits = new ArrayList<>(Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 0));

	public KeyGuardPinScrambler(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {
		shufflePinEnabled = Xprefs.getBoolean("shufflePinEnabled", false);
		if (shufflePinEnabled) {
			synchronized (composeDigits) {
				Collections.shuffle(composeDigits);
			}
		}
	}

	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) throws Throwable {
		ReflectedClass KeyguardPinBasedInputViewClass = ReflectedClass.ofIfPossible("com.android.keyguard.KeyguardPinBasedInputView");
		ReflectedClass PinBouncerKtClass = ReflectedClass.ofIfPossible("com.android.systemui.bouncer.ui.composable.PinBouncerKt");
		ReflectedClass PinBouncerViewModelClass = ReflectedClass.ofIfPossible("com.android.systemui.bouncer.ui.viewmodel.PinBouncerViewModel");

		ReflectionConsumer pinShuffleHook = param -> {
			if (!shufflePinEnabled) return;

			int[] digits = shuffledDigits();

			Object[] mButtons = (Object[]) getObjectField(param.thisObject, "mButtons");

			for(int index = 0; index < mButtons.length && index < digits.length; index++)
			{
				Object button = mButtons[index];
				setObjectField(button, "mDigit", digits[index]);

				callMethod(
						getObjectField(button, "mDigitText"),
						"setText",
						Integer.toString(digits[index]));
			}
		};

		KeyguardPinBasedInputViewClass.after("onFinishInflate").run(pinShuffleHook);
		KeyguardPinBasedInputViewClass.after("resetPasswordText").run(pinShuffleHook);

		// Android 16 QPR moved the PIN bouncer from Views to Compose (PinBouncerKt).
		// Digits are remapped at the DigitButton level, so what's displayed is what gets submitted.
		PinBouncerKtClass
				.before(Pattern.compile("DigitButton-.*"))
				.run(param -> {
					if (!shufflePinEnabled) return;
					if (param.args == null || param.args.length == 0 || !(param.args[0] instanceof Integer)) return;

					int original = (int) param.args[0];

					// R8 can merge the onClick lambda of button 0 into a class that hardcodes
					// submitting 0 ($r8$classId == 1), ignoring the remapped digit. Reset it so
					// the lambda honors the digit we inject.
					if (original == 0 && param.args.length > 2 && param.args[2] != null) {
						try {
							Field classIdField = findFieldIfExists(param.args[2].getClass(), "$r8$classId");
							if (classIdField != null) {
								if (classIdField.getType() == byte.class) {
									classIdField.setByte(param.args[2], (byte) 0);
								} else if (classIdField.getType() == int.class) {
									classIdField.setInt(param.args[2], 0);
								}
							}
						} catch (Throwable ignored) {}
					}

					// Compose passes a $changed bitmask as the trailing argument. Bit 0 marks a
					// self-recomposition (e.g. touch ripple): skip it so digits don't shift under
					// the user's finger. Clear the digit's static bits (0x0E) otherwise, so Compose
					// registers the injected digit as a change and recreates the click lambda.
					if (param.args.length >= 10 && param.args[param.args.length - 1] instanceof Integer) {
						int changed = (int) param.args[param.args.length - 1];
						if ((changed & 1) != 0) return;
						param.args[param.args.length - 1] = changed & ~0x0E;
					}

					int index = (original == 0) ? 9 : (original - 1);
					synchronized (composeDigits) {
						if (index >= 0 && index < composeDigits.size()) {
							param.args[0] = composeDigits.get(index);
						}
					}
				});

		// Reshuffle only when the bouncer activates, so digits never rearrange while the
		// user is retrying a failed PIN.
		PinBouncerViewModelClass
				.before("onActivated")
				.run(param -> {
					if (!shufflePinEnabled) return;
					synchronized (composeDigits) {
						Collections.shuffle(composeDigits);
					}
				});
	}

	private static int[] shuffledDigits() {
		List<Integer> digits = new ArrayList<>(Arrays.asList(0, 1, 2, 3, 4, 5, 6, 7, 8, 9));
		Collections.shuffle(digits);

		int[] result = new int[digits.size()];
		for (int i = 0; i < digits.size(); i++) {
			result[i] = digits.get(i);
		}
		return result;
	}
}
