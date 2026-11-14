package com.tsinbei.pixelxpert.service;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.RemoteException;

import androidx.annotation.NonNull;

import com.topjohnwu.superuser.Shell;
import com.topjohnwu.superuser.ipc.RootService;
import com.topjohnwu.superuser.nio.FileSystemManager;

import java.util.List;

import com.tsinbei.pixelxpert.BuildConfig;
import com.tsinbei.pixelxpert.IRootProviderService;
import com.tsinbei.pixelxpert.Constants;

public class RootProvider extends RootService {
	/** @noinspection unused*/
	String TAG = getClass().getSimpleName();

	static final String LSPD_DB_PATH = "/data/adb/lspd/config/modules_config.db";
	static final String SQLITE_BIN = "/data/adb/modules/TsinbeiPixelXpert/sqlite3";

	@Override
	public IBinder onBind(@NonNull Intent intent) {
		return new RootServicesIPC();
	}

	/** @noinspection RedundantThrows*/
	class RootServicesIPC extends IRootProviderService.Stub
	{
		private boolean mLSPosedEnabled = false;


		@Override
		public boolean checkLSPosedDB(String packageName) {
			if(Constants.SYSTEM_FRAMEWORK_PACKAGE.equals(packageName))
				packageName = "system";

			try
			{
				if(!mLSPosedEnabled)
					refreshModuleState();

				if(!mLSPosedEnabled)
					return false;


				return "1".equals(
						runLSposedSQLiteQuery(
								String.format("select count(*) from scope where module_pkg_name = '%s' and user_id = 0 and app_pkg_name = '%s'", BuildConfig.APPLICATION_ID, packageName)
						).get(0));
			}
			catch (Throwable ignored) {
				return false;
			}
		}

		@Override
		public boolean isPackageInstalled(String packageName) throws RemoteException {
			PackageManager pm = getPackageManager();
			try {
				pm.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES);
				return pm.getApplicationInfo(packageName, 0).enabled;
			} catch (PackageManager.NameNotFoundException ignored) {
				return false;
			}
		}

		@Override
		public boolean isPackageRunning(String packageName) throws RemoteException {
			if (Constants.SYSTEM_FRAMEWORK_PACKAGE.equals(packageName)) {
				packageName = "system_server";
			}
			String escapedPackageName = packageName.replace("'", "'\\\"'\\\"'");
			return Shell.cmd("ps -A -o NAME | awk -v package='" + escapedPackageName
					+ "' '$1 == package || index($1, package \":\") == 1 { found = 1 } END { exit !found }'")
					.exec().isSuccess();
		}

		@Override
		public boolean activateInLSPosed(String packageName) throws RemoteException {
			if (Constants.SYSTEM_FRAMEWORK_PACKAGE.equals(packageName)) //new LSPosed versions renamed framework
				packageName = "system";

			if (checkLSPosedDB(packageName))
				return true;

			refreshModuleState();

			if (!mLSPosedEnabled) {
				enableModuleLSPosed();

				if (checkLSPosedDB(packageName))
					return true;
			}

			runLSposedSQLiteQuery(
					String.format("insert or ignore into scope (module_pkg_name, app_pkg_name, user_id) values ('%s', '%s', 0)", BuildConfig.APPLICATION_ID, packageName));

			return checkLSPosedDB(packageName);
		}
		private void enableModuleLSPosed() {
			runLSposedSQLiteQuery(String.format("insert into modules_state (module_pkg_name, user_id, enabled) values ('%s', 0, 1) on conflict(module_pkg_name, user_id) do update set enabled = 1", BuildConfig.APPLICATION_ID));
			mLSPosedEnabled = true;
		}

		private void refreshModuleState()
		{
			mLSPosedEnabled = "1".equals(
					runLSposedSQLiteQuery(
							String.format("select enabled from modules_state where module_pkg_name = '%s' and user_id = 0", BuildConfig.APPLICATION_ID)
					).get(0));
		}

		private List<String> runLSposedSQLiteQuery(String command)
		{
			return Shell.cmd(String.format("%s %s \"%s\"", SQLITE_BIN, LSPD_DB_PATH, command)).exec().getOut();
		}

		@Override
		public IBinder getFileSystemService(){
			return FileSystemManager.getService();
		}
	}
}
