package com.tsinbei.pixelxpert.utils;

import static android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS;

import android.content.Context;
import android.content.Intent;
import android.os.FileUtils;
import android.util.Log;

import com.topjohnwu.superuser.Shell;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

import com.tsinbei.pixelxpert.PixelXpert;
import com.tsinbei.pixelxpert.Constants;

public class AppUtils {
	private static final String MODULE_APK_PATH = "system/priv-app/TsinbeiPixelXpert/PixelXpert.apk";

	public static void restart(String what) {
		switch (what.toLowerCase())
		{
			case "systemui":
				Shell.cmd("killall com.android.systemui").exec();
				break;
			case "system":
				Shell.cmd("am start -a android.intent.action.REBOOT").exec();
				break;
			case "zygote":
			case "android":
				Shell.cmd("kill $(pidof zygote)").submit();
				Shell.cmd("kill $(pidof zygote64)").submit();
				break;
			default:
				Shell.cmd(String.format("killall %s", what)).exec();
		}
	}

	public static void restartSelf(String reason) {
		Intent intent = PixelXpert.get().getBaseContext().getPackageManager()
				                .getLaunchIntentForPackage(PixelXpert.get().getBaseContext().getPackageName());

		if (intent != null) {
			if(reason != null)
			{
				intent.putExtra(Constants.LAUNCH_REASON_EXTRA, reason);
			}
			intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
			PixelXpert.get().startActivity(intent);
		}

		android.os.Process.killProcess(android.os.Process.myPid());
		System.exit(0);
	}


	public static void runKSURootActivity(Context context, boolean launchApp)
	{
		try {
			String managerPackage = Arrays.asList(
					Constants.RESUKISU_PACKAGE,
					Constants.SUKISU_PACKAGE,
					Constants.SUKISU_PR_PACKAGE,
					Constants.KSU_NEXT_PACKAGE,
					Constants.KSU_PACKAGE
			).stream().filter(packageName -> {
				try
				{
					context.getPackageManager().getPackageInfo(packageName, 0);
					return true;
				}
				catch (Throwable ignored)
				{
					return false;
				}
			}).findFirst().orElse(Constants.KSU_PACKAGE);

			//we first send a broadcast. if app is running it will get it
			Intent broadcastIntent = new Intent(Constants.PX_ROOT_EXTRA);
			if (launchApp) {
				broadcastIntent.putExtra("launchApp", 1);
			}

			broadcastIntent.setPackage(managerPackage);
			context.sendBroadcast(broadcastIntent);

			//if app isn't running, it won't see the broadcast. but it will see the intent instead
			Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(managerPackage);
			//noinspection DataFlowIssue
			launchIntent.putExtra(Constants.PX_ROOT_EXTRA, 1);
			launchIntent.setFlags(FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
			if (launchApp) {
				launchIntent.putExtra("launchApp", 1);
			}

			context.startActivity(launchIntent);
		}
		catch (Throwable ignored){}
	}

	public static boolean isLikelyPixelBuild()
	{
		try
		{
			Process process = Runtime.getRuntime().exec("getprop ro.build.id");
			process.waitFor();
			byte[] buffer = new byte[process.getInputStream().available()];
			//noinspection ResultOfMethodCallIgnored
			process.getInputStream().read(buffer);
			String result = new String(buffer, StandardCharsets.US_ASCII).replace("\n", "");
			return Pattern.matches("^[TUAB][A-Z]([A-Z0-9]){2}\\.[0-9]{6}\\.[0-9]{3}(\\.[A-Z0-9]{2})?$", result); //Pixel standard build number of A13/14 + new weird build numbers of 'A,B,...' prefix
		}
		catch (Throwable ignored)
		{
			return false;
		}
	}

	public static boolean installDoubleZip(String DoubleZipped) //installs the zip magisk module. even if it's zipped inside another zip
	{
		File tempFile = null;
		File unzippedFile = null;
		File apkFile = null;
		try {
			//copy it to somewhere under our control
			tempFile = File.createTempFile("doubleZ", ".zip");
			Shell.cmd(String.format("cp '%s' '%s'", DoubleZipped, tempFile.getAbsolutePath())).exec();

			//unzip once, IF double zipped
			try (ZipFile unzipper = new ZipFile(tempFile)) {
				if (unzipper.stream().count() == 1) {
					unzippedFile = File.createTempFile("singleZ", ".zip");
					try (FileOutputStream unzipOutputStream = new FileOutputStream(unzippedFile)) {
						FileUtils.copy(unzipper.getInputStream(unzipper.entries().nextElement()), unzipOutputStream);
					}
				} else {
					unzippedFile = tempFile;
				}
			}

			if (!Shell.cmd(String.format("if command -v magisk >/dev/null 2>&1; then magisk --install-module '%s'; " +
					"elif command -v ksud >/dev/null 2>&1; then ksud module install '%s'; else exit 1; fi",
					unzippedFile.getAbsolutePath(), unzippedFile.getAbsolutePath())).exec().isSuccess()) {
				return false;
			}

			try (ZipFile moduleZip = new ZipFile(unzippedFile)) {
				if (moduleZip.getEntry(MODULE_APK_PATH) == null) {
					throw new IllegalStateException("Module APK is missing");
				}
				apkFile = File.createTempFile("PixelXpert-update", ".apk");
				try (FileOutputStream apkOutputStream = new FileOutputStream(apkFile)) {
					FileUtils.copy(moduleZip.getInputStream(moduleZip.getEntry(MODULE_APK_PATH)), apkOutputStream);
				}
			}

			String installPath = "/data/local/tmp/PixelXpert-update.apk";
			// PackageInstaller cannot read the root-only module directory or our private cache.
			// Stage the APK in a system-readable directory before asking Package Manager to replace it.
			if (!Shell.cmd(String.format("cp '%s' '%s' && chmod 0644 '%s' && pm install -r --user 0 '%s'; result=$?; rm -f '%s'; exit $result",
					apkFile.getAbsolutePath(), installPath, installPath, installPath, installPath)).exec().isSuccess()) {
				return false;
			}

			return true;
		} catch (Exception e) {
			Log.e("PixelXpert Installer", "PixelXpert zip install error: ", e);
			return false;
		} finally {
			if (apkFile != null) apkFile.delete();
			if (unzippedFile != null && !unzippedFile.equals(tempFile)) unzippedFile.delete();
			if (tempFile != null) tempFile.delete();
		}
	}
}
