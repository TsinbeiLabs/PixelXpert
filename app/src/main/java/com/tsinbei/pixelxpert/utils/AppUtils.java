package com.tsinbei.pixelxpert.utils;

import static android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS;

import android.content.Context;
import android.content.Intent;
import android.os.FileUtils;
import android.util.Log;

import com.topjohnwu.superuser.Shell;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.tsinbei.pixelxpert.PixelXpert;
import com.tsinbei.pixelxpert.Constants;

public class AppUtils {
	//user-app module layout: the APK sits at the zip root
	private static final String MODULE_APK_PATH = "PixelXpert.apk";
	//older releases mounted the APK as a priv-app inside the module
	private static final String LEGACY_MODULE_APK_PATH = "system/priv-app/TsinbeiPixelXpert/PixelXpert.apk";

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
			return Pattern.matches("^[TUABCZ][A-Z]([A-Z0-9]){2}\\.[0-9]{6}\\.[0-9]{3}(\\.[A-Z0-9]{2})?$", result); //Pixel standard build number of A13/14 + new weird build numbers of 'A,B,C,...' prefix (C=A17, Z=canary)
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
		File installerFile = null;
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

			try (ZipFile moduleZip = new ZipFile(unzippedFile)) {
				ZipEntry apkEntry = moduleZip.getEntry(MODULE_APK_PATH);
				if (apkEntry == null) {
					apkEntry = moduleZip.getEntry(LEGACY_MODULE_APK_PATH); //older priv-app layout
				}
				if (apkEntry == null) {
					throw new IllegalStateException("Module APK is missing");
				}
				apkFile = File.createTempFile("PixelXpert-update", ".apk");
				try (FileOutputStream apkOutputStream = new FileOutputStream(apkFile)) {
					FileUtils.copy(moduleZip.getInputStream(apkEntry), apkOutputStream);
				}
			}

			// The current app must not own the install chain. pm install replaces this
			// package and kills its process before the module installer can finish.
			// Stage all inputs in a root-readable location, then hand the complete
			// transaction to a detached root shell before returning to the UI.
			String token = "px-update-" + System.currentTimeMillis();
			String root = "/data/local/tmp/" + token;
			String modulePath = root + ".zip", apkPath = root + ".apk", scriptPath = root + ".sh";
			installerFile = File.createTempFile(token, ".sh");
			writeInstaller(installerFile, modulePath, apkPath, scriptPath);
			String stage = "cp " + quote(unzippedFile.getAbsolutePath()) + " " + quote(modulePath)
					+ " && cp " + quote(apkFile.getAbsolutePath()) + " " + quote(apkPath)
					+ " && cp " + quote(installerFile.getAbsolutePath()) + " " + quote(scriptPath)
					+ " && chmod 0700 " + quote(scriptPath) + " && nohup sh " + quote(scriptPath) + " >/dev/null 2>&1 </dev/null &";
			return Shell.cmd(stage).exec().isSuccess();
		} catch (Exception e) {
			Log.e("PixelXpert Installer", "PixelXpert zip install error: ", e);
			return false;
		} finally {
			if (apkFile != null) apkFile.delete();
			if (installerFile != null) installerFile.delete();
			if (unzippedFile != null && !unzippedFile.equals(tempFile)) unzippedFile.delete();
			if (tempFile != null) tempFile.delete();
		}
	}

	private static void writeInstaller(File file, String modulePath, String apkPath, String scriptPath) throws IOException {
		StringBuilder script = new StringBuilder("#!/system/bin/sh\n")
				.append("MODULE=").append(quote(modulePath)).append("\n")
				.append("APK=").append(quote(apkPath)).append("\n")
				.append("SCRIPT=").append(quote(scriptPath)).append("\n")
				.append("ok=0\n")
				.append("if command -v magisk >/dev/null 2>&1; then magisk --install-module \"$MODULE\" && ok=1; ")
				.append("elif command -v ksud >/dev/null 2>&1; then ksud module install \"$MODULE\" && ok=1; fi\n")
				.append("if [ \"$ok\" = 1 ]; then cp \"$APK\" /data/local/tmp/PixelXpert-update.apk && chmod 0644 /data/local/tmp/PixelXpert-update.apk && pm install -r -d --user 0 /data/local/tmp/PixelXpert-update.apk; fi\n")
				.append("rm -f \"$MODULE\" \"$APK\" \"$SCRIPT\" /data/local/tmp/PixelXpert-update.apk\n");
		try (FileOutputStream output = new FileOutputStream(file)) {
			output.write(script.toString().getBytes(StandardCharsets.UTF_8));
		}
	}

	private static String quote(String value) {
		return "'" + value.replace("'", "'\\''") + "'";
	}
}
