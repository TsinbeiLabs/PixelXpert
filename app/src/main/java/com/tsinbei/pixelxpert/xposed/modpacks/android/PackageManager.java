package com.tsinbei.pixelxpert.xposed.modpacks.android;

import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.callMethod;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.getObjectField;
import static com.tsinbei.pixelxpert.xposed.XPrefs.Xprefs;

import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;

import java.io.File;
import java.lang.reflect.Field;
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
	private static final String ACTION_INSTALL_PACKAGE = "android.intent.action.INSTALL_PACKAGE";
	private static final String ACTION_UNINSTALL_PACKAGE = "android.intent.action.UNINSTALL_PACKAGE";
	private static final String ALLOW_SIGNATURE_PREF = "PM_AllowMismatchedSignature";
	private static final String ALLOW_DOWNGRADE_PREF = "PM_AllowDowngrade";
	private static final String ALLOW_EXACT_SIGNATURE_PREF = "PM_AllowExactSignatureMismatch";
	private static final String ALLOW_SHARED_UID_PREF = "PM_AllowSharedUidSignatureMismatch";
	private static final String INSTALLER_REDIRECT_PREF = "PM_InstallerRedirectEnabled";
	private static final String INSTALLER_TARGET_PREF = "PM_InstallerRedirectTarget";
	private static final String FORCE_EXPLICIT_PREF = "PM_ForceExplicitInstaller";
	private static final String INTERCEPT_UNINSTALL_PREF = "PM_InterceptUninstall";
	private static final String FOLLOW_INSTALLER_PREF = "PM_UninstallFollowInstaller";
	private static final String UNINSTALLER_TARGET_PREF = "PM_UninstallerRedirectTarget";
	private static final String INTERCEPT_SESSION_PREF = "PM_InterceptSessionInstall";
	private static final String FIX_SESSION_PERMISSIONS_PREF = "PM_FixSessionPermissions";
	private static final String ACTION_CONFIRM_INSTALL = "android.content.pm.action.CONFIRM_INSTALL";
	private static final String ACTION_CONFIRM_PERMISSIONS = "android.content.pm.action.CONFIRM_PERMISSIONS";

	private static final int PERMISSION = 4;
	private static final int AUTH = 16;
	private static final int PERMISSION_GRANTED = 0;

	private static boolean allowMismatchedSignature;
	private static boolean allowDowngrade;
	private static boolean allowExactSignatureMismatch;
	private static boolean allowSharedUidSignatureMismatch;
	private static boolean installerRedirectEnabled;
	private static boolean forceExplicitInstaller;
	private static boolean interceptUninstall;
	private static boolean followInstaller;
	private static boolean interceptSessionInstall;
	private static boolean fixSessionPermissions;
	private static String installerTarget;
	private static String uninstallerTarget;
	private static final ThreadLocal<Boolean> resolvingSessionPath = ThreadLocal.withInitial(() -> false);

	public PackageManager(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {
		allowMismatchedSignature = Xprefs.getBoolean(ALLOW_SIGNATURE_PREF, false);
		allowDowngrade = Xprefs.getBoolean(ALLOW_DOWNGRADE_PREF, false);
		allowExactSignatureMismatch = Xprefs.getBoolean(ALLOW_EXACT_SIGNATURE_PREF, false);
		allowSharedUidSignatureMismatch = Xprefs.getBoolean(ALLOW_SHARED_UID_PREF, false);
		installerRedirectEnabled = Xprefs.getBoolean(INSTALLER_REDIRECT_PREF, false);
		installerTarget = Xprefs.getString(INSTALLER_TARGET_PREF, "");
		forceExplicitInstaller = Xprefs.getBoolean(FORCE_EXPLICIT_PREF, false);
		interceptUninstall = Xprefs.getBoolean(INTERCEPT_UNINSTALL_PREF, false);
		followInstaller = Xprefs.getBoolean(FOLLOW_INSTALLER_PREF, true);
		uninstallerTarget = Xprefs.getString(UNINSTALLER_TARGET_PREF, "");
		interceptSessionInstall = Xprefs.getBoolean(INTERCEPT_SESSION_PREF, false);
		fixSessionPermissions = Xprefs.getBoolean(FIX_SESSION_PERMISSIONS_PREF, false);
	}

	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) {
		hookActivityManager();
		hookInstallerRedirection();
		hookSessionPathAccess();
		hookDowngradeChecks();
		hookPackageSignatureChecks();
		hookUpgradeKeySets();
		hookSigningCapabilities();
		hookPermissionSignatureCheck();
		hookSharedUidChecks();
		deoptimizePackageInstallCallers();
	}

	private void hookInstallerRedirection() {
		try {
			Class<?> activityStarter = ReflectedClass.of("com.android.server.wm.ActivityStarter").getClazz();
			Method execute = activityStarter.getDeclaredMethod("execute");
			ReflectedClass.of(activityStarter).before(execute).run(param -> {
				if (!installerRedirectEnabled) return;
				try {
					Object request = findField(param.thisObject.getClass(), "mRequest").get(param.thisObject);
					Field intentField = findField(request.getClass(), "intent");
					Intent intent = (Intent) intentField.get(request);
					if (redirectInstallerIntent(intent)) intentField.set(request, intent);
				} catch (Throwable t) {
					Logger.log("PackageManager: failed to redirect installer intent", t);
				}
			});
		} catch (Throwable t) {
			Logger.log("PackageManager: failed to hook installer redirection", t);
		}
	}

	private boolean redirectInstallerIntent(Intent intent) {
		if (intent == null || !isInstallerIntent(intent)) return false;
		boolean uninstall = Intent.ACTION_DELETE.equals(intent.getAction())
				|| ACTION_UNINSTALL_PACKAGE.equals(intent.getAction());
		boolean session = ACTION_CONFIRM_INSTALL.equals(intent.getAction())
				|| ACTION_CONFIRM_PERMISSIONS.equals(intent.getAction());
		if (uninstall && !interceptUninstall) return false;
		if (session && !interceptSessionInstall) return false;
		if (intent.getComponent() != null && !forceExplicitInstaller) return false;

		String target = uninstall && !followInstaller ? uninstallerTarget : installerTarget;
		if (target == null || target.isBlank()) {
			if (!uninstall) return false;
			intent.setComponent(null);
			intent.setPackage(null);
			return true;
		}

		ComponentName component = ComponentName.unflattenFromString(target);
		if (component == null) return false;
		if (forceExplicitInstaller) {
			if (component.equals(intent.getComponent())) return false;
			intent.setPackage(null);
			intent.setComponent(component);
		} else {
			if (component.getPackageName().equals(intent.getPackage()) && intent.getComponent() == null) return false;
			intent.setComponent(null);
			intent.setPackage(component.getPackageName());
		}
		if (ACTION_INSTALL_PACKAGE.equals(intent.getAction())) intent.setAction(Intent.ACTION_VIEW);
		intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
				| Intent.FLAG_GRANT_READ_URI_PERMISSION);
		return true;
	}

	private boolean isInstallerIntent(Intent intent) {
		String action = intent.getAction();
		if (!Intent.ACTION_VIEW.equals(action)
				&& !ACTION_INSTALL_PACKAGE.equals(action)
				&& !Intent.ACTION_DELETE.equals(action)
				&& !ACTION_UNINSTALL_PACKAGE.equals(action)
				&& !ACTION_CONFIRM_INSTALL.equals(action)
				&& !ACTION_CONFIRM_PERMISSIONS.equals(action)) return false;
		if (Intent.ACTION_DELETE.equals(action) || ACTION_UNINSTALL_PACKAGE.equals(action)
				|| ACTION_CONFIRM_INSTALL.equals(action) || ACTION_CONFIRM_PERMISSIONS.equals(action)
				|| ACTION_INSTALL_PACKAGE.equals(action)) return true;
		if ("application/vnd.android.package-archive".equals(intent.getType())) return true;
		Uri data = intent.getData();
		if (data == null || !("content".equals(data.getScheme()) || "file".equals(data.getScheme()))) return false;
		String path = data.toString().toLowerCase();
		return path.endsWith(".apk") || path.endsWith(".apks") || path.endsWith(".apk.1");
	}

	private void hookSessionPathAccess() {
		if (Build.VERSION.SDK_INT < 34) return;
		try {
			ReflectedClass.of("com.android.server.pm.PackageInstallerSession")
					.before("generateInfoInternal").run(param -> {
						if (fixSessionPermissions) resolvingSessionPath.set(true);
					});
			ReflectedClass.of("com.android.server.pm.PackageInstallerSession")
					.after("generateInfoInternal").run(param -> {
						if (!resolvingSessionPath.get()) return;
						resolvingSessionPath.set(false);
						try {
							Object info = param.getResult();
							Field pathField = findField(info.getClass(), "resolvedBaseCodePath");
							String path = (String) pathField.get(info);
							if (path == null || path.isEmpty()) {
								File baseFile = (File) findField(param.thisObject.getClass(), "mResolvedBaseFile").get(param.thisObject);
								if (baseFile != null) pathField.set(info, baseFile.getAbsolutePath());
							}
						} catch (Throwable ignored) {
						}
					});
			ReflectedClass.of("android.app.ContextImpl").after("checkCallingOrSelfPermission").run(param -> {
				if (resolvingSessionPath.get()
						&& "android.permission.READ_INSTALLED_SESSION_PATHS".equals(param.args[0])) {
					param.setResult(PERMISSION_GRANTED);
				}
			});
		} catch (Throwable t) {
			Logger.log("PackageManager: failed to hook session path access", t);
		}
	}

	private static Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
		for (Class<?> current = clazz; current != null; current = current.getSuperclass()) {
			try {
				Field field = current.getDeclaredField(name);
				field.setAccessible(true);
				return field;
			} catch (NoSuchFieldException ignored) {
			}
		}
		throw new NoSuchFieldException(name);
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
			ThreadLocal<Boolean> bypassUpgradeKeySet = ThreadLocal.withInitial(() -> false);
			Arrays.stream(keySetManager.getDeclaredMethods())
					.filter(method -> method.getName().equals("shouldCheckUpgradeKeySetLocked"))
					.filter(method -> method.getReturnType() == Boolean.TYPE)
					.forEach(method -> {
						ReflectedClass.deoptimize(method);
						ReflectedClass.of(keySetManager).before(method).run(param -> {
							boolean bypass = allowMismatchedSignature && isPackageInstallCall();
							bypassUpgradeKeySet.set(bypass);
							if (bypass) param.setResult(true);
						});
					});

			Arrays.stream(keySetManager.getDeclaredMethods())
					.filter(method -> method.getName().equals("checkUpgradeKeySetLocked"))
					.filter(method -> method.getReturnType() == Boolean.TYPE)
					.forEach(method -> {
						ReflectedClass.deoptimize(method);
						ReflectedClass.of(keySetManager).before(method).run(param -> {
							if (allowMismatchedSignature && Boolean.TRUE.equals(bypassUpgradeKeySet.get())) {
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
		if (allowMismatchedSignature && allowSharedUidSignatureMismatch) {
			try {
				Class<?> reconcilePackageUtils = ReflectedClass.of("com.android.server.pm.ReconcilePackageUtils").getClazz();
				Field allowNonPreloads = reconcilePackageUtils.getDeclaredField("ALLOW_NON_PRELOADS_SYSTEM_SHAREDUIDS");
				allowNonPreloads.setAccessible(true);
				allowNonPreloads.setBoolean(null, true);
			} catch (NoSuchFieldException ignored) {
			} catch (Throwable t) {
				Logger.log("PackageManager: failed to allow system shared UID installs", t);
			}
		}
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
