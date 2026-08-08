package com.tsinbei.pixelxpert.xposed.modpacks.systemui;

import static de.robv.android.xposed.XposedHelpers.callMethod;
import static de.robv.android.xposed.XposedHelpers.getIntField;
import static de.robv.android.xposed.XposedHelpers.getObjectField;
import static de.robv.android.xposed.XposedHelpers.setObjectField;
import static com.tsinbei.pixelxpert.xposed.XPrefs.Xprefs;





import android.content.Context;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
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
	private static final Map<Object, int[]> composeDigitMaps = Collections.synchronizedMap(new WeakHashMap<>());
	private static final ThreadLocal<ComposePinPadState> composePinPadState = new ThreadLocal<>();

	public KeyGuardPinScrambler(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {
		shufflePinEnabled = Xprefs.getBoolean("shufflePinEnabled", false);
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

		// Android 16 QPR moved the PIN bouncer from Views to Compose. Map by button slot,
		// not by the incoming digit, because Compose can feed our previous value back during recomposition.
		PinBouncerKtClass
				.before(Pattern.compile("PinPad-.*"))
				.run(param -> {
					if (!shufflePinEnabled) return;

					Object viewModel = param.args[0];
					composePinPadState.set(new ComposePinPadState(
							composeDigitMaps.computeIfAbsent(viewModel, ignored -> shuffledDigits())));
				});

		PinBouncerKtClass
				.after(Pattern.compile("PinPad-.*"))
				.run(param -> composePinPadState.remove());

		PinBouncerKtClass
				.before(Pattern.compile("DigitButton-.*"))
				.run(param -> {
					if (!shufflePinEnabled) return;

					ComposePinPadState state = composePinPadState.get();
					if (state != null && state.nextSlot < state.digitMap.length) {
						param.args[0] = state.digitMap[state.nextSlot++];
					}
				});
	}

	private static final class ComposePinPadState {
		private final int[] digitMap;
		private int nextSlot;

		private ComposePinPadState(int[] digitMap) {
			this.digitMap = digitMap;
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
