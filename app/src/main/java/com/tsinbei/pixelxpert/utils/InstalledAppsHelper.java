package com.tsinbei.pixelxpert.utils;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import androidx.preference.MultiSelectListPreference;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Populates a multi-select list preference with the installed launchable apps. */
public class InstalledAppsHelper {
	private InstalledAppsHelper() {
	}

	public static void populateMultiSelect(MultiSelectListPreference preference, Context context) {
		if (preference == null || context == null) return;

		PackageManager packageManager = context.getPackageManager();
		List<ResolveInfo> launchers = packageManager.queryIntentActivities(
				new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);

		List<String> labels = new ArrayList<>();
		List<String> packages = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (ResolveInfo resolveInfo : launchers) {
			if (resolveInfo.activityInfo == null) continue;
			ApplicationInfo info = resolveInfo.activityInfo.applicationInfo;
			if (info == null || !seen.add(info.packageName)) continue;
			labels.add(String.valueOf(info.loadLabel(packageManager)));
			packages.add(info.packageName);
		}

		// Sort by label, keeping the value aligned with each entry
		List<Integer> order = new ArrayList<>();
		for (int i = 0; i < labels.size(); i++) order.add(i);
		order.sort(Comparator.comparing(i -> labels.get(i), String.CASE_INSENSITIVE_ORDER));

		CharSequence[] entries = new CharSequence[labels.size()];
		CharSequence[] values = new CharSequence[labels.size()];
		for (int pos = 0; pos < order.size(); pos++) {
			int src = order.get(pos);
			entries[pos] = labels.get(src);
			values[pos] = packages.get(src);
		}

		preference.setEntries(entries);
		preference.setEntryValues(values);
	}
}
