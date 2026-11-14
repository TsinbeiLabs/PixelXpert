package com.tsinbei.pixelxpert.xposed.modpacks.android;

import static com.tsinbei.pixelxpert.xposed.XPrefs.Xprefs;

import android.content.Context;
import android.os.Build;

import java.util.Arrays;

import io.github.libxposed.api.XposedModuleInterface;
import com.tsinbei.pixelxpert.R;
import com.tsinbei.pixelxpert.xposed.XPLauncher;
import com.tsinbei.pixelxpert.xposed.XposedModPack;
import com.tsinbei.pixelxpert.xposed.annotations.FrameworkModPack;

@FrameworkModPack
public class TargetOptimizer extends XposedModPack {
	public static final String OPTIMIZED_BUILD_KEY = "optimized_build";
	public static final String SYSTEM_RESTART_PENDING_KEY = "system_restart_pending";

	public TargetOptimizer(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {}

	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) throws Throwable {
		String optimizedBuild = Xprefs.getString(OPTIMIZED_BUILD_KEY, "");

		if(!Build.ID.equals(optimizedBuild))
		{
			try {
				String[] targetPacks = XPLauncher.moduleResources.getStringArray(R.array.module_scope);

				Arrays.asList(targetPacks).forEach(target ->
						XPLauncher.enqueueProxyCommand(proxy ->
								proxy.runRootCommand(String.format("cmd package compile -m speed -f %s", target))));

				Xprefs.edit()
						.putBoolean(SYSTEM_RESTART_PENDING_KEY, true)
						.putString(OPTIMIZED_BUILD_KEY, Build.ID)
						.commit();
			}
			catch (Throwable ignored)
			{}
		}
	}
}
