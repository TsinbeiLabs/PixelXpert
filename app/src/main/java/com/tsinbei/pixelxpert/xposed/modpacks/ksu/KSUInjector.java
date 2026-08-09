package com.tsinbei.pixelxpert.xposed.modpacks.ksu;

import static android.content.Context.RECEIVER_EXPORTED;


import static de.robv.android.xposed.XposedHelpers.callMethod;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;

import com.topjohnwu.superuser.Shell;

import org.objenesis.ObjenesisHelper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.lang.reflect.Constructor;

import io.github.libxposed.api.XposedModuleInterface;
import com.tsinbei.pixelxpert.BuildConfig;
import com.tsinbei.pixelxpert.Constants;
import com.tsinbei.pixelxpert.xposed.XposedModPack;
import com.tsinbei.pixelxpert.xposed.annotations.KSUModPack;
import com.tsinbei.pixelxpert.xposed.annotations.KSUNextModPack;
import com.tsinbei.pixelxpert.xposed.annotations.ReSukiSUModPack;
import com.tsinbei.pixelxpert.xposed.annotations.SukiSUModPack;
import com.tsinbei.pixelxpert.xposed.annotations.SukiSUPrModPack;
import com.tsinbei.pixelxpert.xposed.utils.SystemUtils;
import com.tsinbei.pixelxpert.xposed.utils.reflection.ReflectedClass;

/**
 * @noinspection RedundantThrows
 */
@KSUModPack
@KSUNextModPack
@ReSukiSUModPack
@SukiSUModPack
@SukiSUPrModPack
public class KSUInjector extends XposedModPack {
	private ReflectedClass NativesClass;
	private ReflectedClass ProfileClass;

	public KSUInjector(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {}

	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) throws Throwable {
		String packageName = PRParam.getPackageName(); // Can be KSU or KSU Next
		ReflectedClass MainActivityClass = ReflectedClass.of(packageName + ".ui.MainActivity");
		NativesClass = ReflectedClass.of(packageName + ".Natives");
		ProfileClass = ReflectedClass.of(packageName + ".Natives$Profile");

		BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				grantRootToPX(intent);
			}
		};

		//In case ksu is running already, it won't understand the onCreate intent we send. broadcast it is then
		mContext.registerReceiver(broadcastReceiver, new IntentFilter(Constants.PX_ROOT_EXTRA), RECEIVER_EXPORTED);

		MainActivityClass
				.after("onCreate")
				.run(param -> {
					Intent launchIntent = ((Activity) param.thisObject).getIntent();
					if (launchIntent.hasExtra(Constants.PX_ROOT_EXTRA)) {
						grantRootToPX(launchIntent);
					}
				});
	}

	private void grantRootToPX(Intent launchIntent) {
		new Thread(() -> {
			try {
				Object nativeObject = ObjenesisHelper.newInstance(NativesClass.getClazz());
				int[] rootUIDs = (int[]) callMethod(nativeObject, "getAllowList");

				PackageManager packageManager = mContext.getPackageManager();
				int ownUID = packageManager.getPackageUid(BuildConfig.APPLICATION_ID, PackageManager.GET_ACTIVITIES);

				boolean haveRoot = Arrays.stream(rootUIDs).anyMatch(uid -> uid == ownUID);

				if (!haveRoot) {
					Object ownRootProfile = createRootProfile(ownUID);

					callMethod(nativeObject, "setAppProfile", ownRootProfile);

					restartPX(launchIntent.hasExtra("launchApp"));
				}
				Thread.sleep(2000);
				SystemUtils.killSelf();
			} catch (Throwable ignored) {
			}
		}).start();
	}

	private Object createRootProfile(int ownUid) throws Throwable {
		for (Constructor<?> constructor : ProfileClass.getClazz().getConstructors()) {
			Class<?>[] parameterTypes = constructor.getParameterTypes();
			if (parameterTypes.length != 14 && parameterTypes.length != 15) continue;
			if (parameterTypes[0] != String.class || parameterTypes[1] != Integer.TYPE
					|| (parameterTypes.length == 14 && parameterTypes[13] != String.class)
					|| (parameterTypes.length == 15 && parameterTypes[14] != Long.TYPE)) continue;
			Object[] arguments = {
					BuildConfig.APPLICATION_ID, ownUid, true, true, null, 0, 0,
					new ArrayList<>(), new ArrayList<>(), "u:r:su:s0", 0, true, true, ""
			};
			if (parameterTypes.length == 15 && parameterTypes[14] == Long.TYPE) {
				arguments = Arrays.copyOf(arguments, 15);
				arguments[14] = 1L;
			}
			if (arguments.length == parameterTypes.length) return constructor.newInstance(arguments);
		}
		throw new NoSuchMethodException("Unsupported KernelSU Profile constructor");
	}

	private void restartPX(boolean launch) throws InterruptedException {
		Shell.cmd("killall " + BuildConfig.APPLICATION_ID).exec();

		if (launch) {
			Thread.sleep(1000);
			//noinspection DataFlowIssue
			mContext.startActivity(
					mContext
							.getPackageManager()
							.getLaunchIntentForPackage(BuildConfig.APPLICATION_ID)
							.putExtra("FromKSU", 1));
		}
	}
}
