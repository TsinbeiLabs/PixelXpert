package com.tsinbei.pixelxpert.ui.fragments;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.tsinbei.pixelxpert.R;
import com.tsinbei.pixelxpert.databinding.FragmentHiddenAppsBinding;
import com.tsinbei.pixelxpert.databinding.ViewHiddenAppBinding;
import com.tsinbei.pixelxpert.utils.PXPreferences;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HiddenAppsFragment extends BaseFragment {
	private static final String HIDDEN_APPS_KEY = "LauncherHiddenApps";
	private FragmentHiddenAppsBinding binding;
	private final List<AppEntry> apps = new ArrayList<>();
	private AppAdapter adapter;
	private int filterId = R.id.filter_all;
	private int filterRequestId;
	private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();
	private final Handler mainHandler = new Handler(Looper.getMainLooper());

	@Override
	public String getTitle() {
		return getString(R.string.launcher_hidden_apps);
	}

	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		binding = FragmentHiddenAppsBinding.inflate(inflater, container, false);
		return binding.getRoot();
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		adapter = new AppAdapter();
		binding.appList.setLayoutManager(new LinearLayoutManager(requireContext()));
		binding.appList.setAdapter(adapter);
		int initialTopPadding = binding.getRoot().getPaddingTop();
		ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (root, windowInsets) -> {
			Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
			root.setPadding(root.getPaddingLeft(), initialTopPadding + insets.top, root.getPaddingRight(), root.getPaddingBottom());
			return windowInsets;
		});
		ViewCompat.requestApplyInsets(binding.getRoot());
		binding.filterGroup.check(R.id.filter_all);
		binding.filterGroup.addOnButtonCheckedListener((group, checkedId, checked) -> {
			if (!checked) return;
			filterId = checkedId;
			refreshApps();
		});
		binding.search.addTextChangedListener(new TextWatcher() {
			@Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
			@Override public void onTextChanged(CharSequence value, int start, int before, int count) { refreshApps(); }
			@Override public void afterTextChanged(Editable value) { }
		});
		loadApps();
	}

	private void loadApps() {
		android.content.pm.PackageManager packageManager = requireContext().getPackageManager();
		setLoading(true);
		backgroundExecutor.execute(() -> {
			Set<String> selected = new HashSet<>(PXPreferences.getPrefs().getStringSet(HIDDEN_APPS_KEY, new HashSet<>()));
			List<ResolveInfo> launchers = packageManager.queryIntentActivities(
					new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
			List<AppEntry> loadedApps = new ArrayList<>();
			Set<String> seen = new HashSet<>();
			for (ResolveInfo resolveInfo : launchers) {
				if (resolveInfo.activityInfo == null) continue;
				ApplicationInfo info = resolveInfo.activityInfo.applicationInfo;
				if (!seen.add(info.packageName)) continue;
				loadedApps.add(new AppEntry(info.packageName, String.valueOf(info.loadLabel(packageManager)),
						info.loadIcon(packageManager), (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0, selected.contains(info.packageName)));
			}
			loadedApps.sort(Comparator.comparing(entry -> entry.label, String.CASE_INSENSITIVE_ORDER));
			mainHandler.post(() -> {
				if (binding == null) return;
				apps.clear();
				apps.addAll(loadedApps);
				setLoading(false);
				refreshApps();
			});
		});
	}

	private void setLoading(boolean loading) {
		if (binding == null) return;
		binding.loadingIndicator.setVisibility(loading ? View.VISIBLE : View.GONE);
		binding.appList.setVisibility(loading ? View.INVISIBLE : View.VISIBLE);
		if (loading) binding.emptyMessage.setVisibility(View.GONE);
	}

	private void refreshApps() {
		if (binding == null || adapter == null) return;
		int requestId = ++filterRequestId;
		int selectedFilter = filterId;
		String query = String.valueOf(binding.search.getText()).trim().toLowerCase(Locale.ROOT);
		List<AppEntry> appSnapshot = new ArrayList<>(apps);
		backgroundExecutor.execute(() -> {
			List<AppEntry> visibleApps = new ArrayList<>();
			for (AppEntry app : appSnapshot) {
				boolean typeMatches = selectedFilter == R.id.filter_all || (selectedFilter == R.id.filter_system ? app.system : !app.system);
				if (typeMatches && (query.isEmpty() || app.label.toLowerCase(Locale.ROOT).contains(query) || app.packageName.toLowerCase(Locale.ROOT).contains(query))) visibleApps.add(app);
			}
			mainHandler.post(() -> {
				if (binding != null && requestId == filterRequestId) {
					adapter.submit(visibleApps);
					binding.emptyMessage.setVisibility(visibleApps.isEmpty() ? View.VISIBLE : View.GONE);
				}
			});
		});
	}

	private class AppAdapter extends RecyclerView.Adapter<AppAdapter.ViewHolder> {
		private final List<AppEntry> visibleApps = new ArrayList<>();

		void submit(List<AppEntry> filteredApps) {
			visibleApps.clear();
			visibleApps.addAll(filteredApps);
			notifyDataSetChanged();
		}

		@NonNull @Override public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			return new ViewHolder(ViewHiddenAppBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
		}
		@Override public void onBindViewHolder(@NonNull ViewHolder holder, int position) { holder.bind(visibleApps.get(position)); }
		@Override public int getItemCount() { return visibleApps.size(); }

		class ViewHolder extends RecyclerView.ViewHolder {
			private final ViewHiddenAppBinding itemBinding;
			ViewHolder(ViewHiddenAppBinding binding) { super(binding.getRoot()); itemBinding = binding; }
			void bind(AppEntry app) {
				itemBinding.icon.setImageDrawable(app.icon);
				itemBinding.name.setText(app.label);
				itemBinding.packageName.setText(app.packageName);
				itemBinding.selected.setChecked(app.selected);
				itemBinding.getRoot().setOnClickListener(view -> toggle(app));
				itemBinding.selected.setOnClickListener(view -> toggle(app));
			}
			private void toggle(AppEntry app) {
				app.selected = !app.selected;
				Set<String> selected = new HashSet<>();
				for (AppEntry entry : apps) if (entry.selected) selected.add(entry.packageName);
				PXPreferences.getPrefs().edit().putStringSet(HIDDEN_APPS_KEY, selected).apply();
				refreshApps();
			}
		}
	}

	@Override
	public void onDestroyView() {
		binding = null;
		super.onDestroyView();
	}

	@Override
	public void onDestroy() {
		backgroundExecutor.shutdownNow();
		super.onDestroy();
	}

	private static class AppEntry {
		final String packageName;
		final String label;
		final android.graphics.drawable.Drawable icon;
		final boolean system;
		boolean selected;
		AppEntry(String packageName, String label, android.graphics.drawable.Drawable icon, boolean system, boolean selected) {
			this.packageName = packageName; this.label = label; this.icon = icon; this.system = system; this.selected = selected;
		}
	}
}
