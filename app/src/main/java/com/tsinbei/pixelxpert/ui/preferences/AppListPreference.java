package com.tsinbei.pixelxpert.ui.preferences;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceViewHolder;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.tsinbei.pixelxpert.R;
import com.tsinbei.pixelxpert.databinding.ViewAppChoiceBinding;

/** A list preference whose entries and selected value can display application icons. */
public class AppListPreference extends MaterialListPreference {
	private Drawable[] entryIcons;

	public AppListPreference(@NonNull Context context, @Nullable AttributeSet attrs) {
		super(context, attrs);
	}

	public void setEntryIcons(Drawable[] entryIcons) {
		this.entryIcons = entryIcons;
		updateSelectedIcon();
	}

	@Override
	public void setValue(String value) {
		super.setValue(value);
		updateSelectedIcon();
	}

	@Override
	protected void onClick() {
		CharSequence[] entries = getEntries();
		CharSequence[] values = getEntryValues();
		if (entries == null || values == null) return;

		int checkedItem = findIndexOfValue(getValue());
		RecyclerView list = new RecyclerView(getContext());
		int padding = (int) (8 * getContext().getResources().getDisplayMetrics().density);
		list.setPadding(0, padding, 0, padding);
		list.setClipToPadding(false);
		list.setLayoutManager(new LinearLayoutManager(getContext()));
		androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(getContext(), R.style.MaterialComponents_MaterialAlertDialog)
				.setTitle(getDialogTitle())
				.setView(list)
				.setNegativeButton(android.R.string.cancel, null)
				.create();
		list.setAdapter(new AppChoiceAdapter(entries, values, checkedItem, index -> {
			if (callChangeListener(values[index].toString())) setValue(values[index].toString());
			dialog.dismiss();
		}));
		dialog.show();
	}

	@Override
	public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
		super.onBindViewHolder(holder);
		updateSelectedIcon();
	}

	private void updateSelectedIcon() {
		int index = findIndexOfValue(getValue());
		setIcon(entryIcons != null && index >= 0 && index < entryIcons.length ? entryIcons[index] : null);
	}

	private class AppChoiceAdapter extends RecyclerView.Adapter<AppChoiceAdapter.ViewHolder> {
		private final CharSequence[] entries;
		private final CharSequence[] values;
		private final int checkedItem;
		private final SelectionListener listener;

		AppChoiceAdapter(CharSequence[] entries, CharSequence[] values, int checkedItem, SelectionListener listener) {
			this.entries = entries; this.values = values; this.checkedItem = checkedItem; this.listener = listener;
		}
		@NonNull @Override public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			return new ViewHolder(ViewAppChoiceBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
		}
		@Override public void onBindViewHolder(@NonNull ViewHolder holder, int position) { holder.bind(position); }
		@Override public int getItemCount() { return entries.length; }
		class ViewHolder extends RecyclerView.ViewHolder {
			private final ViewAppChoiceBinding binding;
			ViewHolder(ViewAppChoiceBinding binding) { super(binding.getRoot()); this.binding = binding; }
			void bind(int position) {
				String[] text = entries[position].toString().split("\\n", 2);
				binding.name.setText(text[0]);
				binding.packageName.setText(text.length > 1 ? text[1] : values[position]);
				binding.icon.setImageDrawable(entryIcons != null && position < entryIcons.length ? entryIcons[position] : null);
				binding.selected.setChecked(position == checkedItem);
				binding.getRoot().setOnClickListener(view -> listener.onSelected(position));
				binding.selected.setOnClickListener(view -> listener.onSelected(position));
			}
		}
	}

	private interface SelectionListener { void onSelected(int index); }
}
