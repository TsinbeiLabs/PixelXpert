package com.tsinbei.pixelxpert.xposed.modpacks.systemui;

import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.callMethod;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.getObjectField;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.setObjectField;
import static com.tsinbei.pixelxpert.xposed.XPrefs.Xprefs;


import android.content.Context;

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
		// Compose always re-invokes DigitButton with the original digit (verified on A17: the
		// hook receives 1..9,0 every time, never an already-scrambled value), so remapping
		// unconditionally is idempotent for the fixed shuffle of the current bouncer session.
		PinBouncerKtClass
				.before(Pattern.compile("DigitButton-.*"))
				.run(param -> {
					if (!shufflePinEnabled) return;
					if (param.args == null || param.args.length <= 2 || !(param.args[0] instanceof Integer)) return;

					// DigitButton is shared between the keyguard bouncer pad (PinBouncerKt) and the
					// device-credential pad an app lock or BiometricPrompt fallback shows
					// (CredentialPinPadKt). Only shuffle the bouncer's own pad.
					Object onClick = param.args[2];
					if (onClick == null || !onClick.getClass().getName().contains("PinBouncerKt")) return;

					int original = (int) param.args[0];
					int index = (original == 0) ? 9 : (original - 1);
					synchronized (composeDigits) {
						if (index >= 0 && index < composeDigits.size()) {
							param.args[0] = composeDigits.get(index);
						}
					}
				});

		// Do NOT reshuffle on PinBouncerViewModel.onActivated: it fires after the pad has
		// already been composed, so the reshuffle only shows up once a touch forces a
		// recomposition (digits visibly change on first tap). The shuffle set in
		// onPreferenceUpdated is stable for the whole SystemUI session, so the display
		// and the submitted digit always agree.
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
