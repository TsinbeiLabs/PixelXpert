package com.tsinbei.pixelxpert.xposed.modpacks.documentsui;

import static com.tsinbei.pixelxpert.Constants.DOCUMENTS_UI_PACKAGE;
import static com.tsinbei.pixelxpert.xposed.XPrefs.Xprefs;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ListView;
import android.widget.Toast;

import com.tsinbei.pixelxpert.xposed.XposedModPack;
import com.tsinbei.pixelxpert.xposed.annotations.DocumentsUIModPack;
import com.tsinbei.pixelxpert.xposed.utils.reflection.ReflectedClass;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedModuleInterface;

/**
 * Remembers DocumentsUI sorting. Derived from hxreborn/remember-my-sort at
 * de76517c015ddd7524b9e24af2371527ce93509f and modified for PixelXpert on 2026-08-08.
 */
@DocumentsUIModPack
public class RememberDocumentsUISort extends XposedModPack {
	private static final String PREF_KEY = "DocumentsUIRememberSort";
	private static final String GLOBAL_FILE = "pixelxpert_sort_global";
	private static final String FOLDER_FILE = "pixelxpert_sort_folders";
	private static final int MAX_FOLDER_ENTRIES = 256;

	private static volatile boolean enabled;
	private static volatile FolderContext currentFolder;

	private final Object storeLock = new Object();
	private final Map<String, SortPreference> folderPreferences =
			new LinkedHashMap<>(MAX_FOLDER_ENTRIES, 0.75f, true);
	private final Map<Object, FolderContext> modelFolders =
			Collections.synchronizedMap(new WeakHashMap<>());
	private SortPreference globalPreference;
	private boolean storesLoaded;

	private final Handler mainHandler = new Handler(Looper.getMainLooper());
	private Runnable pendingLongPress;
	private WeakReference<ListView> pressedList;
	private Window wrappedWindow;
	private Window.Callback originalWindowCallback;
	private float downX;
	private float downY;
	private int touchSlop;
	private boolean longPressConsumed;
	private String dialogFolderKey;
	private WeakReference<Object> sortDialogFragment;
	private Method dialogApplySort;
	private boolean perFolderSortPending;

	public RememberDocumentsUISort(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... key) {
		if (Xprefs != null) enabled = Xprefs.getBoolean(PREF_KEY, false);
	}

	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam param) {
		if (!DOCUMENTS_UI_PACKAGE.equals(Application.getProcessName())) return;

		ClassLoader classLoader = param.getClassLoader();
		hookSortCursor(classLoader);
		hookFolderLoaders(classLoader);
		hookSortDialog(classLoader);
	}

	private void hookSortCursor(ClassLoader classLoader) {
		try {
			Class<?> sortModel = Class.forName("com.android.documentsui.sorting.SortModel", false, classLoader);
			Class<?> lookup = Class.forName("com.android.documentsui.base.Lookup", false, classLoader);
			Method method;
			try {
				method = sortModel.getDeclaredMethod("sortCursor", android.database.Cursor.class, lookup, boolean.class);
			} catch (NoSuchMethodException ignored) {
				method = sortModel.getDeclaredMethod("sortCursor", android.database.Cursor.class, lookup);
			}
			ReflectedClass.of(sortModel).before(method).run(param -> applySavedSort(param.thisObject));
			log("DocumentsUI sort hook installed: " + method);
		} catch (Throwable throwable) {
			log("DocumentsUI sort hook unavailable", throwable);
		}
	}

	private void applySavedSort(Object sortModel) {
		if (!enabled || sortModel == null) return;
		try {
			ensureStoresLoaded();
			if (isUserSpecified(sortModel)) {
				saveUserSpecifiedSort(sortModel);
				return;
			}
			FolderContext folder = modelFolders.get(sortModel);
			String folderKey = folder == null ? null : folder.key();
			SortPreference preference = folderKey == null ? null : getFolderPreference(folderKey);
			if (preference == null) preference = globalPreference;
			if (preference != null) applySort(sortModel, preference);
		} catch (Throwable throwable) {
			log("DocumentsUI sort operation failed", throwable);
		}
	}

	private void saveUserSpecifiedSort(Object sortModel) throws Exception {
		SortPreference preference = readCurrentSort(sortModel);
		if (preference == null) return;

		findField(sortModel.getClass(), "mIsUserSpecified").setBoolean(sortModel, false);
		if (perFolderSortPending && dialogFolderKey != null) {
			perFolderSortPending = false;
			if (saveFolderPreference(dialogFolderKey, preference)) {
				showToast("Sort saved for this folder");
			}
			return;
		}

		perFolderSortPending = false;
		String folderKey = folderKeyForModel(sortModel);
		boolean clearedOverride = folderKey != null && deleteFolderPreference(folderKey);
		if (saveGlobalPreference(preference) || clearedOverride) {
			showToast(clearedOverride ? "Global sort saved; folder override cleared" : "Global sort saved");
		}
	}

	private static boolean isUserSpecified(Object sortModel) {
		try {
			return findField(sortModel.getClass(), "mIsUserSpecified").getBoolean(sortModel);
		} catch (Throwable ignored) {
			return false;
		}
	}

	private static SortPreference readCurrentSort(Object sortModel) throws Exception {
		Method idMethod = sortModel.getClass().getMethod("getSortedDimensionId");
		Method directionMethod = sortModel.getClass().getMethod("getCurrentSortDirection");
		int dimensionId = (int) idMethod.invoke(sortModel);
		int direction = (int) directionMethod.invoke(sortModel);
		return dimensionId == 0 || direction == 0 ? null : new SortPreference(dimensionId, direction);
	}

	private static boolean applySort(Object sortModel, SortPreference preference) throws Exception {
		Method getDimension = sortModel.getClass().getMethod("getDimensionById", int.class);
		Object targetDimension = getDimension.invoke(sortModel, preference.dimensionId);
		if (targetDimension == null) return false;
		Method getSortCapability = targetDimension.getClass().getMethod("getSortCapability");
		int capability = (int) getSortCapability.invoke(targetDimension);
		if ((capability & preference.direction) == 0) return false;

		Field dimensionsField = findField(sortModel.getClass(), "mDimensions");
		Object dimensionsObject = dimensionsField.get(sortModel);
		if (dimensionsObject instanceof SparseArray<?> dimensions) {
			for (int index = 0; index < dimensions.size(); index++) {
				Object dimension = dimensions.valueAt(index);
				if (dimension != null && dimension != targetDimension) {
					findField(dimension.getClass(), "mSortDirection").setInt(dimension, 0);
				}
			}
		}

		findField(targetDimension.getClass(), "mSortDirection").setInt(targetDimension, preference.direction);
		findField(sortModel.getClass(), "mSortedDimension").set(sortModel, targetDimension);
		return true;
	}

	private void hookFolderLoaders(ClassLoader classLoader) {
		hookLoader(classLoader, "com.android.documentsui.DirectoryLoader", "mDoc", "mRoot");
		hookLoader(classLoader, "com.android.documentsui.loaders.FolderLoader", "listedDir", "root");
		for (String className : new String[]{
				"com.android.documentsui.RecentsLoader",
				"com.android.documentsui.loaders.SearchLoader",
				"com.android.documentsui.loaders.SingleProviderSearchLoader",
				"com.android.documentsui.loaders.ContinuousSearchLoader"
		}) {
			try {
				Class<?> loader = Class.forName(className, false, classLoader);
				ReflectedClass.of(loader).before("loadInBackground").run(param -> {
					currentFolder = null;
					prepareLoader(param.thisObject, null);
				});
			} catch (Throwable ignored) { }
		}
	}

	private void hookLoader(ClassLoader classLoader, String className, String documentField, String rootField) {
		try {
			Class<?> loader = Class.forName(className, false, classLoader);
			ReflectedClass.of(loader).before("loadInBackground").run(param -> {
				if (!enabled) return;
				try {
					Object document = getFieldValue(param.thisObject, documentField,
							documentField.startsWith("m") ? null : "mListedDir");
					Object root = getFieldValue(param.thisObject, rootField,
							rootField.startsWith("m") ? null : "mRoot");
					FolderContext folder = createFolderContext(document, root);
					currentFolder = folder;
					prepareLoader(param.thisObject, folder);
				} catch (Throwable throwable) {
					currentFolder = null;
					log("DocumentsUI folder context unavailable for " + className, throwable);
				}
			});
		} catch (Throwable ignored) {
			log("DocumentsUI optional loader missing: " + className);
		}
	}

	private void prepareLoader(Object loader, FolderContext folder) {
		if (!enabled || loader == null) return;
		try {
			Object sortModel = findSortModel(loader);
			if (sortModel == null) return;
			modelFolders.put(sortModel, folder);
			applySavedSort(sortModel);
		} catch (Throwable throwable) {
			log("DocumentsUI loader sort preparation failed", throwable);
		}
	}

	private static Object findSortModel(Object loader) {
		Object model = getOptionalFieldValue(loader, "mModel");
		if (model == null) model = getOptionalFieldValue(loader, "sortModel");
		if (model != null) return model;
		Object state = getOptionalFieldValue(loader, "state");
		if (state == null) state = getOptionalFieldValue(loader, "mState");
		return getOptionalFieldValue(state, "sortModel");
	}

	private static FolderContext createFolderContext(Object document, Object root) throws Exception {
		if (document == null && root == null) return null;
		Object source = document != null ? document : root;
		int userId = extractUserId(getOptionalFieldValue(source, "userId"));
		String authority = stringValue(getOptionalFieldValue(source, "authority"));
		String documentId = stringValue(getOptionalFieldValue(source, "documentId"));
		String rootId = root == null ? "" : stringValue(getOptionalFieldValue(root, "rootId"));
		return new FolderContext(userId, authority, rootId, documentId);
	}

	private void hookSortDialog(ClassLoader classLoader) {
		for (String className : new String[]{
				"com.android.documentsui.sorting.SortListFragment",
				"com.google.android.documentsui.sorting.SortListFragment"
		}) {
			try {
				Class<?> fragment = Class.forName(className, false, classLoader);
				Method onStart = fragment.getMethod("onStart");
				Method onStop = fragment.getMethod("onStop");
				Method applySort = fragment.getDeclaredMethod("applySort", int.class);
				applySort.setAccessible(true);
				ReflectedClass.of(fragment).after(onStart).run(param -> wrapSortDialog(param.thisObject));
				ReflectedClass.of(fragment).after(onStop).run(param -> unwrapSortDialog());
				dialogApplySort = applySort;
				return;
			} catch (Throwable ignored) { }
		}
	}

	private void wrapSortDialog(Object fragment) {
		if (!enabled || fragment == null) return;
		try {
			unwrapSortDialog();
			Object dialog = fragment.getClass().getMethod("getDialog").invoke(fragment);
			if (dialog == null) return;
			Window window = (Window) dialog.getClass().getMethod("getWindow").invoke(dialog);
			if (window == null || window.getCallback() == null) return;

			wrappedWindow = window;
			originalWindowCallback = window.getCallback();
			sortDialogFragment = new WeakReference<>(fragment);
			Object sortModel = getOptionalFieldValue(fragment, "mModel");
			FolderContext folder = sortModel == null ? currentFolder : modelFolders.get(sortModel);
			dialogFolderKey = folder == null ? null : folder.key();
			touchSlop = ViewConfiguration.get(window.getContext()).getScaledTouchSlop();
			Window.Callback proxy = (Window.Callback) Proxy.newProxyInstance(
					Window.Callback.class.getClassLoader(),
					new Class[]{Window.Callback.class},
					(proxyObject, method, args) -> {
						if ("dispatchTouchEvent".equals(method.getName()) && args != null && args.length > 0) {
							MotionEvent event = (MotionEvent) args[0];
							if (handleTouchEvent(window.getDecorView(), event)) return true;
						}
						return args == null
								? method.invoke(originalWindowCallback)
								: method.invoke(originalWindowCallback, args);
					});
			window.setCallback(proxy);
		} catch (Throwable throwable) {
			log("DocumentsUI sort dialog hook unavailable", throwable);
		}
	}

	private String folderKeyForModel(Object sortModel) {
		FolderContext folder = modelFolders.get(sortModel);
		return folder == null ? null : folder.key();
	}

	private boolean handleTouchEvent(View decorView, MotionEvent event) {
		if (!enabled || event == null) return false;
		switch (event.getActionMasked()) {
			case MotionEvent.ACTION_DOWN -> {
				ListView listView = findListView(decorView);
				if (listView == null || pointToPosition(listView, event.getRawX(), event.getRawY()) < 0) return false;
				downX = event.getRawX();
				downY = event.getRawY();
				pressedList = new WeakReference<>(listView);
				longPressConsumed = false;
				cancelLongPress();
				pendingLongPress = () -> performLongPress(downX, downY);
				mainHandler.postDelayed(pendingLongPress, ViewConfiguration.getLongPressTimeout());
			}
			case MotionEvent.ACTION_MOVE -> {
				if (Math.abs(event.getRawX() - downX) > touchSlop || Math.abs(event.getRawY() - downY) > touchSlop) {
					cancelLongPress();
				}
			}
			case MotionEvent.ACTION_UP -> {
				cancelLongPress();
				pressedList = null;
				if (longPressConsumed) {
					longPressConsumed = false;
					return true;
				}
			}
			case MotionEvent.ACTION_CANCEL -> {
				cancelLongPress();
				pressedList = null;
				longPressConsumed = false;
			}
		}
		return false;
	}

	private void performLongPress(float rawX, float rawY) {
		ListView listView = pressedList == null ? null : pressedList.get();
		if (listView == null) return;
		int position = pointToPosition(listView, rawX, rawY);
		if (position < 0) return;

		if (dialogFolderKey == null || dialogApplySort == null) return;
		Object fragment = sortDialogFragment == null ? null : sortDialogFragment.get();
		if (fragment == null) return;
		perFolderSortPending = true;
		longPressConsumed = true;
		listView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
		try {
			dialogApplySort.invoke(fragment, position);
		} catch (Throwable throwable) {
			perFolderSortPending = false;
			longPressConsumed = false;
			log("DocumentsUI folder sort selection failed", throwable);
		}
		pressedList = null;
	}

	private void unwrapSortDialog() {
		cancelLongPress();
		if (wrappedWindow != null && originalWindowCallback != null) {
			try {
				wrappedWindow.setCallback(originalWindowCallback);
			} catch (Throwable ignored) { }
		}
		wrappedWindow = null;
		originalWindowCallback = null;
		dialogFolderKey = null;
		sortDialogFragment = null;
		pressedList = null;
		longPressConsumed = false;
	}

	private void cancelLongPress() {
		if (pendingLongPress != null) {
			mainHandler.removeCallbacks(pendingLongPress);
			pendingLongPress = null;
		}
	}

	private void ensureStoresLoaded() {
		synchronized (storeLock) {
			if (storesLoaded) return;
			globalPreference = readGlobalPreference();
			readFolderPreferences();
			storesLoaded = true;
		}
	}

	private SortPreference readGlobalPreference() {
		File file = new File(mContext.getFilesDir(), GLOBAL_FILE);
		if (!file.exists()) return null;
		try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
			String[] values = reader.readLine().split(":");
			return new SortPreference(Integer.parseInt(values[0]), Integer.parseInt(values[values.length - 1]));
		} catch (Throwable ignored) {
			return null;
		}
	}

	private boolean saveGlobalPreference(SortPreference preference) {
		synchronized (storeLock) {
			if (preference.equals(globalPreference)) return false;
			try (BufferedWriter writer = new BufferedWriter(new FileWriter(new File(mContext.getFilesDir(), GLOBAL_FILE)))) {
				writer.write(preference.dimensionId + ":" + preference.direction);
			} catch (Throwable throwable) {
				log("DocumentsUI global sort could not be saved", throwable);
				return false;
			}
			globalPreference = preference;
			return true;
		}
	}

	private void readFolderPreferences() {
		File file = new File(mContext.getFilesDir(), FOLDER_FILE);
		if (!file.exists()) return;
		try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
			String line;
			while ((line = reader.readLine()) != null) {
				try {
					JSONObject json = new JSONObject(line);
					int dimensionId = json.has("id") ? json.getInt("id") : json.getInt("pos");
					folderPreferences.put(json.getString("key"), new SortPreference(dimensionId, json.getInt("dir")));
				} catch (Throwable ignored) { }
			}
		} catch (Throwable throwable) {
			log("DocumentsUI folder sorts could not be read", throwable);
		}
	}

	private SortPreference getFolderPreference(String key) {
		synchronized (storeLock) {
			return folderPreferences.get(key);
		}
	}

	private boolean saveFolderPreference(String key, SortPreference preference) {
		synchronized (storeLock) {
			SortPreference previous = folderPreferences.get(key);
			if (preference.equals(previous)) return false;
			folderPreferences.put(key, preference);
			while (folderPreferences.size() > MAX_FOLDER_ENTRIES) {
				folderPreferences.remove(folderPreferences.keySet().iterator().next());
			}
			if (writeFolderPreferences()) return true;
			if (previous == null) folderPreferences.remove(key);
			else folderPreferences.put(key, previous);
			return false;
		}
	}

	private boolean deleteFolderPreference(String key) {
		synchronized (storeLock) {
			SortPreference previous = folderPreferences.remove(key);
			if (previous == null) return false;
			if (writeFolderPreferences()) return true;
			folderPreferences.put(key, previous);
			return false;
		}
	}

	private boolean writeFolderPreferences() {
		File target = new File(mContext.getFilesDir(), FOLDER_FILE);
		File temporary = new File(mContext.getFilesDir(), FOLDER_FILE + ".tmp");
		try (BufferedWriter writer = new BufferedWriter(new FileWriter(temporary))) {
			for (Map.Entry<String, SortPreference> entry : folderPreferences.entrySet()) {
				JSONObject json = new JSONObject();
				json.put("key", entry.getKey());
				json.put("id", entry.getValue().dimensionId);
				json.put("dir", entry.getValue().direction);
				writer.write(json.toString());
				writer.newLine();
			}
		} catch (Throwable throwable) {
			log("DocumentsUI folder sorts could not be saved", throwable);
			return false;
		}
		if (!temporary.renameTo(target)) {
			try {
				java.nio.file.Files.copy(temporary.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				//noinspection ResultOfMethodCallIgnored
				temporary.delete();
			} catch (Throwable throwable) {
				log("DocumentsUI folder sort replacement failed", throwable);
				return false;
			}
		}
		return true;
	}

	private void showToast(String text) {
		mainHandler.post(() -> Toast.makeText(mContext, text, Toast.LENGTH_SHORT).show());
	}

	private static int pointToPosition(ListView listView, float rawX, float rawY) {
		int[] location = new int[2];
		listView.getLocationOnScreen(location);
		return listView.pointToPosition((int) rawX - location[0], (int) rawY - location[1]);
	}

	private static ListView findListView(View view) {
		if (view instanceof ListView listView) return listView;
		if (!(view instanceof ViewGroup group)) return null;
		for (int index = 0; index < group.getChildCount(); index++) {
			ListView result = findListView(group.getChildAt(index));
			if (result != null) return result;
		}
		return null;
	}

	private static Object getFieldValue(Object target, String primary, String fallback) throws Exception {
		try {
			return findField(target.getClass(), primary).get(target);
		} catch (NoSuchFieldException exception) {
			if (fallback == null) throw exception;
			return findField(target.getClass(), fallback).get(target);
		}
	}

	private static Object getOptionalFieldValue(Object target, String name) {
		if (target == null) return null;
		try {
			return findField(target.getClass(), name).get(target);
		} catch (Throwable ignored) {
			return null;
		}
	}

	private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
		Class<?> current = type;
		while (current != null) {
			try {
				Field field = current.getDeclaredField(name);
				field.setAccessible(true);
				return field;
			} catch (NoSuchFieldException ignored) {
				current = current.getSuperclass();
			}
		}
		throw new NoSuchFieldException(name);
	}

	private static int extractUserId(Object userId) {
		if (userId == null) return 0;
		try {
			return (int) userId.getClass().getMethod("getIdentifier").invoke(userId);
		} catch (Throwable ignored) {
			return 0;
		}
	}

	private static String stringValue(Object value) {
		return value instanceof String ? (String) value : "";
	}

	private record SortPreference(int dimensionId, int direction) { }
	private record FolderContext(int userId, String authority, String rootId, String documentId) {
		String key() {
			return userId + ":" + authority + ":" + rootId + ":" + documentId;
		}
	}
}
