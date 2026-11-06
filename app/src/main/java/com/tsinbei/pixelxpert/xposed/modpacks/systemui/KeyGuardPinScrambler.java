package com.tsinbei.pixelxpert.xposed.modpacks.systemui;

import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.callMethod;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.findFieldIfExists;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.getObjectField;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.setObjectField;
import static com.tsinbei.pixelxpert.xposed.XPrefs.Xprefs;


import android.content.Context;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
import com.tsinbei.pixelxpert.xposed.utils.toolkit.Logger;

@SuppressWarnings("RedundantThrows")
@SystemUIModPack
public class KeyGuardPinScrambler extends XposedModPack {
	private static volatile boolean shufflePinEnabled = false;
	private static volatile int[] composeDigits = shuffledDigits();

	public KeyGuardPinScrambler(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {
		boolean enabled = Xprefs.getBoolean("shufflePinEnabled", false);
		if (enabled && !shufflePinEnabled) {
			composeDigits = shuffledDigits();
		}
		shufflePinEnabled = enabled;
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

		// Parent composition supplies a slot digit. The restart scope captures the mapped
		// digit and calls us with it again on press/release. Those are different inputs:
		// mapping a restart would apply the permutation twice and change the visible key.
		for (Method method : PinBouncerKtClass.findMethods(Pattern.compile("DigitButton-.*"))) {
			Class<?>[] types = method.getParameterTypes();
			int composerIndex = -1;
			for (int i = 0; i < types.length; i++) {
				if (types[i].getName().equals("androidx.compose.runtime.Composer")) {
					composerIndex = i;
					break;
				}
			}
			if (composerIndex < 0 || composerIndex + 1 >= types.length
					|| types[composerIndex + 1] != int.class) {
				throw new IllegalStateException("DigitButton has no Compose change mask: " + method);
			}
			// Resolve against the actual target Method at registration, not the framework's
			// runtime hook executable (which may be a generated trampoline).
			final int changedIndex = composerIndex + 1;
			Logger.log("PinShuffle: " + method.getName() + " changedIndex=" + changedIndex);
			PinBouncerKtClass.before(method).run(param -> {
				if (!shufflePinEnabled) return;
				if (param.args == null || param.args.length <= 2 || !(param.args[0] instanceof Integer)) return;

				// DigitButton is shared between the keyguard bouncer pad (PinBouncerKt) and the
				// device-credential pad an app lock or BiometricPrompt fallback shows
				// (CredentialPinPadKt). Only shuffle the bouncer's own pad.
				Object onClick = param.args[2];
				if (onClick == null || !onClick.getClass().getName().contains("PinBouncerKt")) return;

				int changed = (int) param.args[changedIndex];
				if ((changed & 1) != 0) return;

				int original = (int) param.args[0];
				// R8 specializes the zero-slot Function1 to ignore its argument and submit
				// literal 0. In the merged class, discriminator 0 is the general branch
				// that submits Integer.intValue(); use it for the shuffled zero slot too.
				if (original == 0) {
					Field classId = findFieldIfExists(onClick.getClass(), "$r8$classId");
					if (classId != null) {
						if (classId.getType() == byte.class) {
							classId.setByte(onClick, (byte) 0);
						} else if (classId.getType() == int.class) {
							classId.setInt(onClick, 0);
						}
					}
				}
				int index = (original == 0) ? 9 : (original - 1);
				int[] digits = composeDigits;
				if (index >= 0 && index < digits.length) {
					param.args[0] = digits[index];
					// The caller's mask describes the original digit. Let Composer compare
					// the injected value so remembered click/content lambdas stay in sync.
					param.args[changedIndex] = changed & ~0x0E;
				}
			});
		}
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
