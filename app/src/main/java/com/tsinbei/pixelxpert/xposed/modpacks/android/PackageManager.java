package com.tsinbei.pixelxpert.xposed.modpacks.android;

import static de.robv.android.xposed.XposedHelpers.callMethod;
import static de.robv.android.xposed.XposedHelpers.getObjectField;
import static com.tsinbei.pixelxpert.xposed.XPrefs.Xprefs;

import android.content.Context;
import android.content.Intent;
import android.os.Binder;

import java.lang.reflect.Method;
import java.util.Arrays;

import io.github.libxposed.api.XposedModuleInterface;
import com.tsinbei.pixelxpert.BuildConfig;
import com.tsinbei.pixelxpert.Constants;
import com.tsinbei.pixelxpert.xposed.XposedModPack;
import com.tsinbei.pixelxpert.xposed.annotations.FrameworkModPack;
import com.tsinbei.pixelxpert.xposed.utils.reflection.ReflectedClass;
import com.tsinbei.pixelxpert.xposed.utils.toolkit.Logger;

@FrameworkModPack
public class PackageManager extends XposedModPack {
	private static final String ALLOW_SIGNATURE_PREF = "PM_AllowMismatchedSignature";
	private static final String ALLOW_DOWNGRADE_PREF = "PM_AllowDowngrade";
	private static final String ALLOW_EXACT_SIGNATURE_PREF = "PM_AllowExactSignatureMismatch";
	private static final String ALLOW_SHARED_UID_PREF = "PM_AllowSharedUidSignatureMismatch";

	private static final int PERMISSION = 4;
	private static final int AUTH = 16;
	private static final int PERMISSION_GRANTED = 0;

	private static boolean allowMismatchedSignature;
	private static boolean allowDowngrade;
	private static boolean allowExactSignatureMismatch;
	private static boolean allowSharedUidSignatureMismatch;

	public PackageManager(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {
		allowMismatchedSignature = Xprefs.getBoolean(ALLOW_SIGNATURE_PREF, false);
		allowDowngrade = Xprefs.getBoolean(ALLOW_DOWNGRADE_PREF, false);
		allowExactSignatureMismatch = Xprefs.getBoolean(ALLOW_EXACT_SIGNATURE_PREF, false);
		allowSharedUidSignatureMismatch = Xprefs.getBoolean(ALLOW_SHARED_UID_PREF, false);
	}

	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) {
		hookActivityManager();
		hookDowngradeChecks();
		hookPackageSignatureChecks();
		hookUpgradeKeySets();
		hookSigningCapabilities();
		hookPermissionSignatureCheck();
		hookSharedUidChecks();
		deoptimizePackageInstallCallers();
	}

	private void hookActivityManager() {
		try {
			ReflectedClass activityManager = ReflectedClass.of("com.android.server.am.ActivityManagerService");
			activityManager.before("checkBroadcastFromSystem").run(param -> {
				String action = ((Intent) param.args[0]).getAction();
				if (action != null && action.startsWith(BuildConfig.APPLICATION_ID + ".ACTION")) {
					param.setResult(null);
				}
			});

			activityManager.before("checkCallingPermission").run(param -> {
				try {
					if ("android.permission.FORCE_STOP_PACKAGES".equals(param.args[0])
							&& Constants.LAUNCHER_PACKAGE.equals(callMethod(
							getObjectField(param.thisObject, "mInternal"),
							"getPackageNameByPid", Binder.getCallingPid()))) {
						param.setResult(PERMISSION_GRANTED);
					}
				} catch (Throwable ignored) {
				}
			});
		} catch (Throwable t) {
			Logger.log("PackageManager: failed to hook ActivityManager", t);
		}
	}

	private void hookDowngradeChecks() {
		try {
			Class<?> utils = ReflectedClass.of("com.android.server.pm.PackageManagerServiceUtils").getClazz();
			Arrays.stream(utils.getDeclaredMethods())
					.filter(method -> method.getName().equals("checkDowngrade"))
					.filter(method -> method.getReturnType() == Void.TYPE)
					.filter(method -> {
						Class<?>[] parameters = method.getParameterTypes();
						return parameters.length > 0
								&& parameters[parameters.length - 1].getName()
								.equals("android.content.pm.PackageInfoLite");
					})
					.forEach(method -> {
						ReflectedClass.deoptimize(method);
						ReflectedClass.of(utils).before(method).run(param -> {
							if (allowDowngrade) param.setResult(null);
						});
					});
		} catch (Throwable t) {
			Logger.log("PackageManager: failed to hook downgrade checks", t);
		}
	}

	private void hookPackageSignatureChecks() {
		try {
			Class<?> utils = ReflectedClass.of("com.android.server.pm.PackageManagerServiceUtils").getClazz();
			Arrays.stream(utils.getDeclaredMethods())
					.filter(method -> method.getName().equals("verifySignatures"))
					.filter(method -> method.getReturnType() == Boolean.TYPE)
					.forEach(method -> {
						ReflectedClass.deoptimize(method);
						ReflectedClass.of(utils).before(method).run(param -> {
							if (!allowMismatchedSignature || !isExistingPackage(param.args)) return;
							if (hasSharedUser(param.args) && !allowSharedUidSignatureMismatch) return;
							// AOSP returns true only for legacy compat migration, not normal success.
							param.setResult(false);
						});
					});
		} catch (Throwable t) {
			Logger.log("PackageManager: failed to hook package signature verification", t);
		}
	}

	private void hookUpgradeKeySets() {
		try {
			Class<?> keySetManager = ReflectedClass.of("com.android.server.pm.KeySetManagerService").getClazz();
			Arrays.stream(keySetManager.getDeclaredMethods())
					.filter(method -> method.getName().equals("shouldCheckUpgradeKeySetLocked"))
					.filter(method -> method.getReturnType() == Boolean.TYPE)
					.forEach(method -> {
						ReflectedClass.deoptimize(method);
						ReflectedClass.of(keySetManager).after(method).run(param -> {
							if (allowMismatchedSignature && Boolean.TRUE.equals(param.getResult())) {
								// Fall back to verifySignatures instead of accepting an unrelated keyset.
								param.setResult(false);
							}
						});
					});

			Arrays.stream(keySetManager.getDeclaredMethods())
					.filter(method -> method.getName().equals("checkUpgradeKeySetLocked"))
					.filter(method -> method.getReturnType() == Boolean.TYPE)
					.forEach(method -> {
						ReflectedClass.deoptimize(method);
						ReflectedClass.of(keySetManager).after(method).run(param -> {
							if (allowMismatchedSignature && Boolean.FALSE.equals(param.getResult())) {
								param.setResult(true);
							}
						});
					});
		} catch (Throwable t) {
			Logger.log("PackageManager: failed to hook upgrade keysets", t);
		}
	}

	private void hookSigningCapabilities() {
		try {
			Class<?> signingDetails = ReflectedClass.of("android.content.pm.SigningDetails").getClazz();
			for (String methodName : new String[]{"checkCapability", "checkCapabilityRecover"}) {
				Arrays.stream(signingDetails.getDeclaredMethods())
						.filter(method -> method.getName().equals(methodName))
						.filter(method -> method.getReturnType() == Boolean.TYPE)
						.filter(method -> method.getParameterCount() == 2)
						.filter(method -> method.getParameterTypes()[0] == signingDetails)
						.filter(method -> method.getParameterTypes()[1] == Integer.TYPE)
						.forEach(method -> {
							ReflectedClass.deoptimize(method);
							ReflectedClass.of(signingDetails).before(method).run(param -> {
								if (!allowMismatchedSignature || !isPackageInstallCall()) return;
								int capability = (int) param.args[1];
								if (capability == PERMISSION || capability == AUTH) return;
								if (capability == 2 && !allowSharedUidSignatureMismatch) return;
								param.setResult(true);
							});
						});
			}

			Arrays.stream(signingDetails.getDeclaredMethods())
					.filter(method -> method.getName().equals("signaturesMatchExactly"))
					.filter(method -> method.getReturnType() == Boolean.TYPE)
					.filter(method -> method.getParameterCount() == 1)
					.filter(method -> method.getParameterTypes()[0] == signingDetails)
					.forEach(method -> {
						ReflectedClass.deoptimize(method);
						ReflectedClass.of(signingDetails).before(method).run(param -> {
							if (allowExactSignatureMismatch && isPackageInstallCall()) {
								param.setResult(true);
							}
						});
					});
		} catch (Throwable t) {
			Logger.log("PackageManager: failed to hook signing capabilities", t);
		}
	}

	private void hookPermissionSignatureCheck() {
		try {
			Class<?> installHelper = ReflectedClass.of("com.android.server.pm.InstallPackageHelper").getClazz();
			Arrays.stream(installHelper.getDeclaredMethods())
					.filter(method -> method.getName().equals("doesSignatureMatchForPermissions"))
					.filter(method -> method.getReturnType() == Boolean.TYPE)
					.forEach(method -> {
						ReflectedClass.deoptimize(method);
						ReflectedClass.of(installHelper).after(method).run(param -> {
							if (!allowMismatchedSignature || !Boolean.FALSE.equals(param.getResult())) return;
							try {
								if (callMethod(param.args[1], "getPackageName").equals(param.args[0])) {
									param.setResult(true);
								}
							} catch (Throwable ignored) {
							}
						});
					});
		} catch (Throwable t) {
			Logger.log("PackageManager: failed to hook permission signature checks", t);
		}
	}

	private void hookSharedUidChecks() {
		try {
			Class<?> utils = ReflectedClass.of("com.android.server.pm.PackageManagerServiceUtils").getClazz();
			Arrays.stream(utils.getDeclaredMethods())
					.filter(method -> method.getName().equals("canJoinSharedUserId"))
					.filter(method -> method.getReturnType() == Boolean.TYPE)
					.forEach(method -> {
						ReflectedClass.deoptimize(method);
						ReflectedClass.of(utils).before(method).run(param -> {
							if (allowMismatchedSignature && allowSharedUidSignatureMismatch
									&& isPackageInstallCall()) {
								param.setResult(true);
							}
						});
					});

			Class<?> signingDetails = ReflectedClass.of("android.content.pm.SigningDetails").getClazz();
			Method hasCommonAncestor = signingDetails.getDeclaredMethod("hasCommonAncestor", signingDetails);
			ReflectedClass.deoptimize(hasCommonAncestor);
			ReflectedClass.of(signingDetails).before(hasCommonAncestor).run(param -> {
				if (allowMismatchedSignature && allowSharedUidSignatureMismatch
						&& isPackageInstallCall()) {
					param.setResult(true);
				}
			});
		} catch (Throwable t) {
			Logger.log("PackageManager: failed to hook shared UID checks", t);
		}
	}

	private void deoptimizePackageInstallCallers() {
		for (String className : new String[]{
				"com.android.server.pm.ReconcilePackageUtils",
				"com.android.server.pm.InstallPackageHelper"
		}) {
			try {
				Class<?> clazz = ReflectedClass.of(className).getClazz();
				Arrays.stream(clazz.getDeclaredMethods())
						.filter(method -> method.getName().equals("reconcilePackages")
								|| method.getName().equals("reconcileInstallPackages")
								|| method.getName().equals("preparePackage"))
						.forEach(ReflectedClass::deoptimize);
			} catch (Throwable t) {
				Logger.log("PackageManager: failed to deoptimize " + className, t);
			}
		}
	}

	private boolean isExistingPackage(Object[] args) {
		try {
			Object signingDetails = callMethod(args[0], "getSigningDetails");
			return callMethod(signingDetails, "getSignatures") != null;
		} catch (Throwable ignored) {
			return false;
		}
	}

	private boolean hasSharedUser(Object[] args) {
		return Arrays.stream(args)
				.filter(arg -> arg != null)
				.anyMatch(arg -> arg.getClass().getName().equals("com.android.server.pm.SharedUserSetting"));
	}

	private boolean isPackageInstallCall() {
		return Arrays.stream(Thread.currentThread().getStackTrace())
				.anyMatch(frame -> frame.getClassName().startsWith("com.android.server.pm.")
						&& (frame.getMethodName().equals("verifySignatures")
						|| frame.getMethodName().equals("reconcilePackages")
						|| frame.getMethodName().equals("reconcileInstallPackages")
						|| frame.getMethodName().equals("preparePackage")
						|| frame.getMethodName().equals("preparePackageLI")
						|| frame.getMethodName().equals("doesSignatureMatchForPermissions")));
	}
}
