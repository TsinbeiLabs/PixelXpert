package com.tsinbei.pixelxpert.xposed;

import static android.content.Context.CONTEXT_IGNORE_SECURITY;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.getObjectField;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.setObjectField;
import static com.tsinbei.pixelxpert.BuildConfig.APPLICATION_ID;
import static com.tsinbei.pixelxpert.xposed.XPrefs.Xprefs;
import static com.tsinbei.pixelxpert.xposed.utils.BootLoopProtector.isBootLooped;

import android.annotation.SuppressLint;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Resources;
import android.os.IBinder;
import android.os.RemoteException;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import io.github.libxposed.api.XposedInterface;
import com.tsinbei.pixelxpert.BuildConfig;
import com.tsinbei.pixelxpert.Constants;
import com.tsinbei.pixelxpert.IPixelXpertProxy;
import com.tsinbei.pixelxpert.R;
import com.tsinbei.pixelxpert.service.PixelXpertProxy;
import com.tsinbei.pixelxpert.xposed.utils.SystemUtils;
import com.tsinbei.pixelxpert.xposed.utils.reflection.ReflectedClass;
import com.tsinbei.pixelxpert.xposed.utils.toolkit.Logger;
import com.tsinbei.pixelxpert.xposed.modpacks.allApps.HookTester;

public class XPLauncher extends XposedModule implements ServiceConnection {
	public static String processName = "";
	public static boolean isSystemServer = false;

	public static ArrayList<XposedModPack> runningMods = new ArrayList<>();
	public Context mContext = null;
	@SuppressLint("StaticFieldLeak")
	static XPLauncher instance;

	private CountDownLatch rootProxyCountdown = new CountDownLatch(1);
	private static IPixelXpertProxy rootProxyIPC;
	private static final Queue<ProxyRunnable> proxyQueue = new LinkedList<>();
	private static boolean TELECOM_SERVER_LOADED = false;
	private static final int PREFS_LOAD_MAX_ATTEMPTS = 30;
	private static final int PREFS_LOAD_RETRY_DELAY_MILLIS = 1000;
	private boolean hookTesterLoaded = false;
	public static Resources moduleResources;

	public XPLauncher()
	{
		instance = this;
		Logger.setXposedInterface(this);
	}

	@Override
	public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
		super.onModuleLoaded(param);

		processName = param.getProcessName();
		isSystemServer = param.isSystemServer();
	}

	@Override
	public void onSystemServerStarting(@NonNull XposedModuleInterface.SystemServerStartingParam SSSP)
	{
		ReflectedClass.setFrameworkClassloader(SSSP.getClassLoader());
	}

	@Override
	public boolean onHotReloading(@NonNull XposedModuleInterface.HotReloadingParam param) {
		return true;
	}

	@Override
	public void onHotReloaded(@NonNull XposedModuleInterface.HotReloadedParam param) {
		param.getOldHookHandles().forEach(XposedInterface.HookHandle::unhook);
	}

	private static void hook17BetaAudioManagerSRWorkaround(PackageReadyParam PRParam) {
		ReflectedClass.of("android.media.AudioManager", PRParam.getClassLoader())
				.before("requestAudioFocus")
				.run(instance,param -> {
					if(getObjectField(param.thisObject, "mApplicationContext") == null) {
						setObjectField(param.thisObject, "mApplicationContext", getObjectField(param.thisObject, "mOriginalContext"));
					}
				});
	}

	@Override
	public void onPackageReady(@NonNull PackageReadyParam PRParam){
		ReflectedClass.setDefaultXposedInterface(this);

		hook17BetaAudioManagerSRWorkaround(PRParam);

		if (isSystemServer && !PRParam.getPackageName().equals(Constants.TELECOM_SERVER_PACKAGE)) {
			// PackageReady can arrive after PhoneWindowManager.init on newer systems.
			// Initialize here when the system context is already available, while keeping
			// the policy hook below as a fallback for earlier boot stages.
			initializeSystemServer(PRParam);

			ReflectedClass PhoneWindowManagerClass = ReflectedClass.of("com.android.server.policy.PhoneWindowManager");

			PhoneWindowManagerClass
					.before("init")
					.run(instance,param -> {
						try {
							initializeSystemServer(PRParam, (Context) param.args[0]);
						} catch (Throwable t) {
							Logger.log(t);
						}
					});
		}

		if(!isSystemServer || PRParam.getPackageName().equals(Constants.TELECOM_SERVER_PACKAGE)) {
			ReflectedClass.of(Instrumentation.class)
					.after("newApplication")
					.run(this, param -> {
				try {
					if (mContext == null || (PRParam.getPackageName().equals(Constants.TELECOM_SERVER_PACKAGE) && !TELECOM_SERVER_LOADED)) {
						if (PRParam.getPackageName().equals(Constants.TELECOM_SERVER_PACKAGE))
							TELECOM_SERVER_LOADED = true;

						mContext = (Context) param.args[param.args.length - 1];

						moduleResources = mContext.createPackageContext(APPLICATION_ID, CONTEXT_IGNORE_SECURITY)
								                  .getResources();

						XPrefs.init(mContext);

						waitForXprefsLoad(PRParam);
					}
				} catch (Throwable t) {
					Logger.log(t);
				}
			});
		}
	}

	private void initializeSystemServer(PackageReadyParam PRParam) {
		try {
			Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
			Object activityThread = activityThreadClass.getDeclaredMethod("currentActivityThread").invoke(null);
			if (activityThread == null) return;
			Context systemContext = (Context) activityThreadClass.getDeclaredMethod("getSystemContext").invoke(activityThread);
			initializeSystemServer(PRParam, systemContext);
		} catch (Throwable ignored) {
			// PhoneWindowManager.init will initialize the module if system context is not ready yet.
		}
	}

	private synchronized void initializeSystemServer(PackageReadyParam PRParam, Context context) throws Exception {
		if (mContext != null || context == null) return;

		mContext = context;
		moduleResources = mContext.createPackageContext(APPLICATION_ID, CONTEXT_IGNORE_SECURITY)
				.getResources();
		XPrefs.init(mContext);
		loadHookTester(PRParam);
		CompletableFuture.runAsync(() -> waitForXprefsLoad(PRParam));
	}

	private void loadHookTester(PackageReadyParam PRParam) {
		if (hookTesterLoaded) return;
		try {
			XposedModPack hookTester = new HookTester(mContext);
			hookTester.onPackageLoaded(PRParam);
			runningMods.add(hookTester);
			hookTesterLoaded = true;
		} catch (Throwable throwable) {
			Logger.log("Start Error Dump - Occurred in " + HookTester.class.getName());
			Logger.log(throwable);
		}
	}

	private void waitForXprefsLoad(PackageReadyParam PRParam) {
		Throwable lastError = null;
		for (int attempt = 0; attempt < PREFS_LOAD_MAX_ATTEMPTS; attempt++) {
			try {
				Xprefs.getBoolean("LoadTestBooleanValue", false);
				loadPrefsAndModPacks(PRParam);
				return;
			} catch (Throwable throwable) {
				lastError = throwable;
				SystemUtils.threadSleep(PREFS_LOAD_RETRY_DELAY_MILLIS);
			}
		}

		Logger.log("PixelXpert preferences were unavailable after " + PREFS_LOAD_MAX_ATTEMPTS
				+ " attempts on " + PRParam.getPackageName());
		if (lastError != null) Logger.log(lastError);
	}

	private void loadPrefsAndModPacks(PackageReadyParam PRParam) {
		Logger.log(String.format("Loading PixelXpert version: %s on %s", BuildConfig.VERSION_NAME, PRParam.getPackageName()));
		try {
			Logger.log("PixelXpert Records: " + Xprefs.getAll().size());
		} catch (Throwable ignored) {
		}

		onXPrefsReady(PRParam);
	}

	private void onXPrefsReady(PackageReadyParam PRParam) {
		if (isBootLooped(PRParam.getPackageName())) {
			Logger.log(String.format("PixelXpert: Possible bootloop in %s. Will not load for now", PRParam.getPackageName()));
			return;
		}

		new SystemUtils(mContext);
		XPrefs.setPackagePrefs(PRParam.getPackageName());

		loadModPacks(PRParam);

		XPrefs.onContentProviderLoaded();
	}

	private void loadModPacks(PackageReadyParam PRParam) {
		ReflectedClass.setDefaultClassloader(PRParam.getClassLoader());

		if (Arrays.asList(moduleResources.getStringArray(R.array.root_requirement)).contains(PRParam.getPackageName())) {
			forceConnectRootService();
		}

		ModPacks.getModPacks()
				.forEach(modPackData -> {
					String partOfProcessName = modPackData.targetsMainProcess ? "" : modPackData.childProcessName;

					if((modPackData.targetPackage.equals(PRParam.getPackageName()) || modPackData.targetPackage.isEmpty() /*common mod packs*/ || (modPackData.targetPackage.equals(Constants.SYSTEM_FRAMEWORK_PACKAGE) && isSystemServer))
							   && processName.contains(partOfProcessName))
					{
						loadModPack(modPackData.clazz.asSubclass(XposedModPack.class), PRParam);
					}
				});
	}

	private void loadModPack(Class<? extends XposedModPack> thisClass, PackageReadyParam PRParam) {
		if (thisClass == HookTester.class && hookTesterLoaded) return;
		try {
			XposedModPack instance = thisClass.getConstructor(Context.class).newInstance(mContext);
			try {
				instance.onPreferenceUpdated();
			} catch (Throwable ignored) {
			}

			instance.onPackageLoaded(PRParam);
			runningMods.add(instance);
		} catch (Throwable T) {
			Logger.log("Start Error Dump - Occurred in " + thisClass.getName());
			Logger.log(T);
		}
	}

	private void forceConnectRootService() {
		new Thread(() -> {
			while (SystemUtils.UserManager() == null
					       || !SystemUtils.UserManager().isUserUnlocked()) //device is still CE encrypted
			{
				SystemUtils.threadSleep(2000);
			}
			SystemUtils.threadSleep(5000); //wait for the unlocked account to settle down a bit

			while (rootProxyIPC == null) {
				connectRootService();
				SystemUtils.threadSleep(5000);
			}
		}).start();
	}

	private void connectRootService() {
		try {
			Intent intent = new Intent();
			intent.setComponent(new ComponentName(APPLICATION_ID, PixelXpertProxy.class.getName()));
			mContext.bindService(intent, instance, Context.BIND_AUTO_CREATE | Context.BIND_ADJUST_WITH_ACTIVITY);
		} catch (Throwable t) {
			Logger.log(t);
		}
	}

	@Override
	public void onServiceConnected(ComponentName name, IBinder service) {
		rootProxyIPC = IPixelXpertProxy.Stub.asInterface(service);
		rootProxyCountdown.countDown();

		synchronized (proxyQueue) {
			while (!proxyQueue.isEmpty()) {
				try {
					Objects.requireNonNull(proxyQueue.poll()).run(rootProxyIPC);
				} catch (Throwable ignored) {
				}
			}
		}
	}

	@Override
	public void onServiceDisconnected(ComponentName name) {
		rootProxyIPC = null;

		forceConnectRootService();
	}

	public static IPixelXpertProxy getRootProviderProxy() {
		if (rootProxyIPC == null) {
			instance.rootProxyCountdown = new CountDownLatch(1);
			instance.forceConnectRootService();
			try {
				//noinspection ResultOfMethodCallIgnored
				instance.rootProxyCountdown.await(5, TimeUnit.SECONDS);
			} catch (Throwable ignored) {
			}
		}
		return rootProxyIPC;
	}

	public static void enqueueProxyCommand(ProxyRunnable runnable) {
		if (rootProxyIPC != null) {
			try {
				runnable.run(rootProxyIPC);
			} catch (RemoteException ignored) {
			}
		} else {
			synchronized (proxyQueue) {
				proxyQueue.add(runnable);
			}
			instance.forceConnectRootService();
		}
	}

	public interface ProxyRunnable {
		void run(IPixelXpertProxy proxy) throws RemoteException;
	}
}
