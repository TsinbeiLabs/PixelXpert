package com.tsinbei.pixelxpert.xposed.modpacks.systemui;

import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.callMethod;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.getBooleanField;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.getObjectField;
import static com.tsinbei.pixelxpert.xposed.XPrefs.Xprefs;




import android.content.Context;
import android.view.View;

import io.github.libxposed.api.XposedModuleInterface;
import com.tsinbei.pixelxpert.xposed.XposedModPack;
import com.tsinbei.pixelxpert.xposed.annotations.SystemUIModPack;
import com.tsinbei.pixelxpert.xposed.utils.reflection.ReflectedClass;

@SuppressWarnings("RedundantThrows")
@SystemUIModPack
public class EasyUnlock extends XposedModPack {
	private int expectedPassLen = -1;
	private boolean easyUnlockEnabled = false;

	private int lastPassLen = 0;
	private static boolean WakeUpToSecurityInput = false;

	public EasyUnlock(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {
		easyUnlockEnabled = Xprefs.getBoolean("easyUnlockEnabled", false);
		expectedPassLen = Xprefs.getInt("expectedPassLen", -1);
		WakeUpToSecurityInput = Xprefs.getBoolean("WakeUpToSecurityInput", false);
	}

	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) throws Throwable {
		ReflectedClass StatusBarKeyguardViewManagerClass = ReflectedClass.ofIfPossible("com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager");
		ReflectedClass LockPatternUtilsClass = ReflectedClass.ofIfPossible("com.android.internal.widget.LockPatternUtils");

		StatusBarKeyguardViewManagerClass
				.before("onDozingChanged")
				.run(param -> {
					//noinspection ConstantValue
					if(WakeUpToSecurityInput && param.args[0].equals(false) && (!getBooleanField(getObjectField(param.thisObject, "mKeyguardStateController"), "mCanDismissLockScreen")))//waking up
					{
						callMethod(param.thisObject, "showPrimaryBouncer", /*reason*/"PXAsked", true);
					}
				});

		// Android 14+ natively supports auto-confirming the PIN once fully typed.
		boolean autoPinConfirmHooked = false;
		if (LockPatternUtilsClass.getClazz() != null) {
			try {
				autoPinConfirmHooked = !LockPatternUtilsClass
						.after("isAutoPinConfirmEnabled")
						.run(param -> {
							if (easyUnlockEnabled) {
								try {
									// During FBE (direct boot) the CE storage is locked, so the PIN length
									// can't be verified. Forcing true then would make SystemUI auto-submit
									// a single digit and fail. Mimic stock behavior: require Enter on first boot.
									int userId = (int) param.args[0];
									int pinLength = (int) callMethod(param.thisObject, "getPinLength", userId);
									param.setResult(pinLength > 0);
								} catch (Throwable e) {
									param.setResult(false);
								}
							}
						}).isEmpty();
			} catch (Throwable ignored) {
			}
		}
		if (!autoPinConfirmHooked) {
			legacyEasyUnlock(); //fallback for older Android versions without isAutoPinConfirmEnabled
		}
	}

	private void legacyEasyUnlock() {
		ReflectedClass KeyguardAbsKeyInputViewControllerClass = ReflectedClass.ofIfPossible("com.android.keyguard.KeyguardAbsKeyInputViewController");
		ReflectedClass LockscreenCredentialClass = ReflectedClass.ofIfPossible("com.android.internal.widget.LockscreenCredential");
		if (KeyguardAbsKeyInputViewControllerClass.getClazz() == null || LockscreenCredentialClass.getClazz() == null) return;

		KeyguardAbsKeyInputViewControllerClass
				.after("onUserInput")
				.run(param -> {
					if (!easyUnlockEnabled) return;

					int passwordLen = (int) callMethod(getObjectField(getObjectField(param.thisObject, "mPasswordEntry"), "mText"), "length");

					if (passwordLen == expectedPassLen && passwordLen > lastPassLen) {
						new Thread(() -> {
							try { //don't crash systemUI if failed
								int userId;
								try { //14 QPR3 beta 2.1
									userId = (int) callMethod(
											getObjectField(
													getObjectField(param.thisObject, "mKeyguardUpdateMonitor"),
													"mSelectedUserInteractor")
											, "getSelectedUserId");
								}
								catch (Throwable ignored)
								{ //14 QPR3 beta 2 and older
									userId = (int) getObjectField(getObjectField(param.thisObject, "mKeyguardUpdateMonitor"), "sCurrentUser");
								}

								String methodName = param.thisObject.getClass().getName().contains("Password") ? "createPassword" : "createPin";

								Object password = LockscreenCredentialClass.callStaticMethod(methodName, getObjectField(getObjectField(param.thisObject, "mPasswordEntry"), "mText").toString());

								Object verificationResult = callMethod(
										getObjectField(param.thisObject, "mLockPatternUtils"),
										"checkCredential",
										password,
										userId,
										null /* callback */);

								boolean accepted;

								try{ //16qpr3
									accepted = (boolean) callMethod(verificationResult, "isMatched");
								}
								catch (Throwable ignored) //older
								{
									accepted = (boolean) verificationResult;
								}

								if (accepted) {
									View mView = (View) getObjectField(param.thisObject, "mView");
									int finalUserId = userId;
									mView.post(() -> {
										try { //13 QPR3
											callMethod(callMethod(param.thisObject, "getKeyguardSecurityCallback"), "dismiss", finalUserId, getObjectField(param.thisObject, "mSecurityMode"));
										} catch (Throwable ignored) {}
									});
								}
							} catch (Throwable ignored){}
						}).start();
					}
					lastPassLen = passwordLen;
				});

		KeyguardAbsKeyInputViewControllerClass
				.after("onPasswordChecked")
				.run(param -> {
					if (!easyUnlockEnabled) return;

					boolean successful = (boolean) param.args[1];

					if (successful) {
						expectedPassLen = lastPassLen;
						Xprefs.edit().putInt("expectedPassLen", expectedPassLen).apply();
					}
				});
	}
}