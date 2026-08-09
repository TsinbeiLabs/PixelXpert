package com.tsinbei.pixelxpert.ui.fragments;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.drawable.Drawable;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.preference.Preference;

import com.tsinbei.pixelxpert.R;
import com.tsinbei.pixelxpert.ui.preferences.AppListPreference;
import com.tsinbei.pixelxpert.utils.ControlledPreferenceFragmentCompat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class PackageManagerFragment extends ControlledPreferenceFragmentCompat {
	private static final String INSTALLER_PREF = "PM_InstallerRedirectTarget";
	private static final String UNINSTALLER_PREF = "PM_UninstallerRedirectTarget";

	@Override
	public String getTitle() {
		return getString(R.string.pm_header);
	}

	@Override
	public int getLayoutResource() {
		return R.xml.packagemanger_prefs;
	}

	@Override
	public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
		super.onCreatePreferences(savedInstanceState, rootKey);
		populateHandlers(INSTALLER_PREF, new Intent(Intent.ACTION_VIEW)
				.setDataAndType(Uri.parse("content://pixelxpert/installer.apk"), "application/vnd.android.package-archive"));
		populateHandlers(UNINSTALLER_PREF, new Intent(Intent.ACTION_DELETE, Uri.parse("package:com.example.app")));
	}

	@Override
	public void updateScreen(String key) {
		super.updateScreen(key);
		if (mPreferences == null) return;

		boolean redirectEnabled = mPreferences.getBoolean("PM_InstallerRedirectEnabled", false);
		boolean uninstallEnabled = mPreferences.getBoolean("PM_InterceptUninstall", false);
		boolean followInstaller = mPreferences.getBoolean("PM_UninstallFollowInstaller", true);

		setVisible(INSTALLER_PREF, redirectEnabled);
		setVisible("PM_ForceExplicitInstaller", redirectEnabled);
		setVisible("PM_InterceptSessionInstall", redirectEnabled);
		setVisible("PM_FixSessionPermissions", redirectEnabled);
		setVisible("PM_InterceptUninstall", redirectEnabled);
		setVisible("PM_UninstallFollowInstaller", redirectEnabled && uninstallEnabled);
		setVisible(UNINSTALLER_PREF, redirectEnabled && uninstallEnabled && !followInstaller);
	}

	private void setVisible(String key, boolean visible) {
		Preference preference = findPreference(key);
		if (preference != null) preference.setVisible(visible);
	}

	private void populateHandlers(String preferenceKey, Intent intent) {
		AppListPreference preference = findPreference(preferenceKey);
		if (preference == null) return;

		List<ResolveInfo> handlers = requireContext().getPackageManager().queryIntentActivities(
				intent, android.content.pm.PackageManager.MATCH_ALL);
		handlers.removeIf(info -> info.activityInfo == null || !info.activityInfo.exported);
		handlers.sort(Comparator.comparing(info -> String.valueOf(info.loadLabel(requireContext().getPackageManager())),
				String.CASE_INSENSITIVE_ORDER));

		List<CharSequence> entries = new ArrayList<>();
		List<CharSequence> values = new ArrayList<>();
		List<Drawable> icons = new ArrayList<>();
		entries.add(getString(R.string.pm_installer_system_default));
		values.add("");
		icons.add(requireContext().getDrawable(android.R.drawable.sym_def_app_icon));
		for (ResolveInfo handler : handlers) {
			ActivityInfo activity = handler.activityInfo;
			CharSequence appLabel = activity.applicationInfo.loadLabel(requireContext().getPackageManager());
			CharSequence activityLabel = handler.loadLabel(requireContext().getPackageManager());
			String label = appLabel.toString();
			if (!activityLabel.toString().equals(label)) label += " - " + activityLabel;
			entries.add(label + "\n" + activity.packageName);
			values.add(activity.packageName + "/" + activity.name);
			icons.add(activity.applicationInfo.loadIcon(requireContext().getPackageManager()));
		}

		preference.setEntries(entries.toArray(new CharSequence[0]));
		preference.setEntryValues(values.toArray(new CharSequence[0]));
		preference.setEntryIcons(icons.toArray(new Drawable[0]));
		preference.setSummaryProvider(androidx.preference.ListPreference.SimpleSummaryProvider.getInstance());
	}
}
