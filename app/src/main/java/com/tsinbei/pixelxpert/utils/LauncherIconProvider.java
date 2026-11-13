package com.tsinbei.pixelxpert.utils;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;

/** Current-user Launcher access to imported icons and restoration metadata. */
public class LauncherIconProvider extends ContentProvider {
	@Override public boolean onCreate() { return true; }
	private void authorize() {
		int caller = Binder.getCallingUid();
		if (caller == android.os.Process.myUid()) return;
		try {
			if (caller == getContext().getPackageManager().getApplicationInfo("com.google.android.apps.nexuslauncher", 0).uid) return;
		} catch (android.content.pm.PackageManager.NameNotFoundException ignored) { }
		throw new SecurityException("Launcher icon provider is restricted to Pixel Launcher");
	}
	@Override public Bundle call(String method, String arg, Bundle extras) {
		authorize();
		if ("remember".equals(method)) {
			ArrayList<String> packages = extras == null ? null : extras.getStringArrayList("packages");
			if (packages == null || packages.size() > 4000 || packages.stream().anyMatch(pkg -> !LauncherIconLibrary.validPackage(pkg))) throw new IllegalArgumentException("Invalid restoration targets");
			java.util.Set<String> restore = new java.util.HashSet<>(PXPreferences.getPrefs().getStringSet(LauncherIconLibrary.RESTORE_KEY, java.util.Set.of()));
			if (restore.addAll(packages) && !PXPreferences.getPrefs().edit().putStringSet(LauncherIconLibrary.RESTORE_KEY, restore).commit()) throw new IllegalStateException("Cannot save restoration targets");
			return Bundle.EMPTY;
		}
		if (!"catalog".equals(method) || !LauncherIconLibrary.validGeneration(arg)) throw new IllegalArgumentException("Invalid icon catalog");
		File directory = new File(LauncherIconLibrary.directory(getContext()), arg);
		File[] files = directory.listFiles(); ArrayList<String> packages = new ArrayList<>();
		if (files != null) for (File file : files) {
			String name = file.getName();
			if (name.endsWith(".0.png")) {
				String pkg = name.substring(0, name.length() - 6);
				if (LauncherIconLibrary.validPackage(pkg) && new File(directory, pkg + ".1.png").isFile()) packages.add(pkg);
			}
		}
		Bundle result = new Bundle(); result.putStringArrayList("packages", packages); return result;
	}
	@Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
		authorize(); List<String> parts = uri.getPathSegments();
		if (!"r".equals(mode) || parts.size() != 3 || !LauncherIconLibrary.validGeneration(parts.get(0))
				|| !LauncherIconLibrary.validPackage(parts.get(1)) || !List.of("0", "1").contains(parts.get(2))) throw new FileNotFoundException("Invalid icon path");
		File file = new File(new File(LauncherIconLibrary.directory(getContext()), parts.get(0)), parts.get(1) + "." + parts.get(2) + ".png");
		return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
	}
	@Override public String getType(Uri uri) { return "image/png"; }
	@Override public Cursor query(Uri uri, String[] columns, String selection, String[] args, String sort) { throw new UnsupportedOperationException(); }
	@Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
	@Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
	@Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
