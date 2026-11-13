package com.tsinbei.pixelxpert.ui.fragments;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.tsinbei.pixelxpert.R;
import com.tsinbei.pixelxpert.utils.ControlledPreferenceFragmentCompat;
import com.tsinbei.pixelxpert.utils.InstalledAppsHelper;
import com.tsinbei.pixelxpert.utils.LauncherIconLibrary;
import com.tsinbei.pixelxpert.utils.AospPerfectIconPack;
import com.tsinbei.pixelxpert.utils.PXPreferences;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LauncherIconsFragment extends ControlledPreferenceFragmentCompat {
	private final ExecutorService worker = Executors.newSingleThreadExecutor();
	private final Handler main = new Handler(Looper.getMainLooper());
	private volatile LauncherIconLibrary importing;
	private final ActivityResultLauncher<String[]> picker = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
		if (uri == null) return;
		Context context = requireContext().getApplicationContext();
		findPreference("LauncherIconsImport").setEnabled(false);
		findPreference("LauncherIconsClear").setEnabled(false);
		worker.execute(() -> {
			try {
				LauncherIconLibrary library = new LauncherIconLibrary(context, uri);
				importing = library;
				List<String> sources = library.sources();
				main.post(() -> {
					if (!isAdded()) { library.close(); return; }
					if (sources.size() == 1) install(context, library, sources.get(0));
					else new MaterialAlertDialogBuilder(requireContext())
							.setTitle(R.string.launcher_icons_choose_source)
							.setItems(sources.toArray(new String[0]), (dialog, which) -> install(context, library, sources.get(which)))
							.setOnCancelListener(dialog -> { library.close(); importing = null; finish(null); }).show();
				});
			} catch (Exception failure) { finish(failure.getMessage()); }
		});
	});

	@Override public String getTitle() { return getString(R.string.launcher_icons_title); }
	@Override public int getLayoutResource() { return R.xml.launcher_icons_prefs; }
	@Override public void onCreatePreferences(Bundle state, String root) {
		super.onCreatePreferences(state, root);
		InstalledAppsHelper.populateMultiSelect(findPreference("LauncherIconOriginals"), getContext());
		findPreference(AospPerfectIconPack.SOURCE_KEY).setOnPreferenceClickListener(preference -> {
			String[] labels = {getString(R.string.launcher_icons_source_imported), "AOSP Perfect Icons"};
			int current = AospPerfectIconPack.PACKAGE.equals(PXPreferences.getPrefs().getString(AospPerfectIconPack.SOURCE_KEY, AospPerfectIconPack.IMPORTED)) ? 1 : 0;
			new MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.launcher_icons_source)
					.setSingleChoiceItems(labels, current, (dialog, which) -> {
						dialog.dismiss();
						if (which == 0) {
							PXPreferences.getPrefs().edit().putString(AospPerfectIconPack.SOURCE_KEY, AospPerfectIconPack.IMPORTED).apply(); updateSource();
						} else {
							Context context = requireContext().getApplicationContext(); preference.setEnabled(false);
							worker.execute(() -> {
								try {
									AospPerfectIconPack.Catalog catalog = AospPerfectIconPack.load(context);
									java.util.Set<String> restore = new java.util.HashSet<>(PXPreferences.getPrefs().getStringSet(LauncherIconLibrary.RESTORE_KEY, java.util.Set.of()));
									restore.addAll(catalog.affectedPackages());
									if (!PXPreferences.getPrefs().edit().putStringSet(LauncherIconLibrary.RESTORE_KEY, restore).putString(AospPerfectIconPack.SOURCE_KEY, AospPerfectIconPack.PACKAGE).commit()) throw new IllegalStateException("Cannot save icon source");
									finish(null);
								} catch (Exception failure) { finish(context.getString(R.string.launcher_icons_pack_unavailable) + "\n" + failure.getMessage()); }
							});
						}
					}).show();
			return true;
		});
		findPreference("LauncherIconsImport").setOnPreferenceClickListener(preference -> {
			picker.launch(new String[]{"application/zip", "application/octet-stream", "application/x-zip-compressed"}); return true;
		});
		findPreference("LauncherIconsClear").setOnPreferenceClickListener(preference -> {
			Context context = requireContext().getApplicationContext();
			findPreference("LauncherIconsImport").setEnabled(false);
			findPreference("LauncherIconsClear").setEnabled(false);
			worker.execute(() -> { LauncherIconLibrary.clear(context); finish(null); });
			return true;
		});
	}
	@Override public void onResume() { super.onResume(); updateSource(); }
	private void updateSource() {
		if (!isAdded()) return;
		boolean pack = AospPerfectIconPack.PACKAGE.equals(PXPreferences.getPrefs().getString(AospPerfectIconPack.SOURCE_KEY, AospPerfectIconPack.IMPORTED));
		findPreference(AospPerfectIconPack.SOURCE_KEY).setSummary(pack ? "AOSP Perfect Icons" : getString(R.string.launcher_icons_source_imported));
	}
	private void install(Context context, LauncherIconLibrary library, String root) {
		worker.execute(() -> {
			try (library) {
				int count = library.install(context, root);
				finish(context.getString(R.string.launcher_icons_imported, count));
			} catch (Exception failure) { finish(failure.getMessage()); }
			finally { importing = null; }
		});
	}
	private void finish(String message) {
		main.post(() -> {
			if (!isAdded()) return;
			findPreference("LauncherIconsImport").setEnabled(true);
			findPreference("LauncherIconsClear").setEnabled(true);
			findPreference(AospPerfectIconPack.SOURCE_KEY).setEnabled(true);
			updateSource();
			if (message != null) Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
		});
	}
	@Override public void onDestroy() {
		worker.shutdownNow();
		LauncherIconLibrary library = importing;
		if (library != null) library.close();
		super.onDestroy();
	}
}
