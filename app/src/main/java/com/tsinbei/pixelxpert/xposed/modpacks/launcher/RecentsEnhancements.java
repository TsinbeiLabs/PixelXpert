package com.tsinbei.pixelxpert.xposed.modpacks.launcher;

import static com.tsinbei.pixelxpert.xposed.XPrefs.Xprefs;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.callMethod;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.callStaticMethod;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.getAdditionalInstanceField;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.getObjectField;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.getStaticObjectField;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.setAdditionalInstanceField;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Debug;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.format.Formatter;
import android.text.style.DynamicDrawableSpan;
import android.text.style.ImageSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.tsinbei.pixelxpert.xposed.XPLauncher;
import com.tsinbei.pixelxpert.xposed.XposedModPack;
import com.tsinbei.pixelxpert.xposed.annotations.LauncherModPack;
import com.tsinbei.pixelxpert.xposed.utils.reflection.HookHelper;
import com.tsinbei.pixelxpert.xposed.utils.reflection.ReflectedClass;

import io.github.libxposed.api.XposedModuleInterface;

/**
 * Recents enhancements adapted from PixelLauncherEnhanced's GPL-3.0 implementations:
 * memory usage readout under the overview actions, clear-all protection for locked
 * apps, and the swipe-up-to-freeform gesture.
 */
@LauncherModPack
public class RecentsEnhancements extends XposedModPack {
	// Memory info readout
	private boolean showMemInfo;
	private boolean showZram;
	private boolean showChip;
	private WeakReference<MemInfoView> memInfoViewRef;
	private static volatile HandlerThread workerThread;

	// App lock
	private boolean lockEnabled;
	private Set<String> lockedApps = Collections.emptySet();
	private final Handler mainHandler = new Handler(Looper.getMainLooper());
	private final ExecutorService lockExecutor = Executors.newSingleThreadExecutor();
	private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final RecentsLockGlyph badgeGlyph = new RecentsLockGlyph(Color.WHITE);
	private WeakReference<ViewGroup> recentsViewRef;
	private Class<?> taskViewClass;
	private Class<?> iconChipClass;
	private Class<?> activityManagerWrapperClass;

	// Freeform gesture
	private boolean freeformEnabled;
	private float offProgress = 3f;
	private int freeformMode = MODE_AOSP;

	private static final String PLAIN_CHIP_TITLE = "pxPlainChipTitle";
	private static final String LOCK_PLACEHOLDER = "\uFFFC";
	private static final float BADGE_RATIO = 0.42f;
	private static final int BADGE_ALPHA = 200;

	private static final int MODE_AOSP = 0;
	private static final int MODE_SUNSHINE = 1;
	private static final int MODE_LMAO = 2;
	private static final int MODE_YAMF = 3;
	private static final int MODE_REYAMF = 4;
	private static final int MODE_BUBBLE = 5;
	private static final int WINDOWING_MODE_FREEFORM = 5;

	private static final float TEXT_SIZE_SP = 12f;
	private static final int BOTTOM_GAP_DP = 6;
	private static final int CHIP_PADDING_HORIZONTAL_DP = 12;
	private static final int CHIP_PADDING_VERTICAL_DP = 4;
	private static final int CHIP_RADIUS_DP = 100;
	private static final int CHIP_ALPHA = 38;
	private static final long REFRESH_INTERVAL_MS = 3000L;
	private static final String MEMINFO_TEXT = "%1$s available | %2$s total";
	private static final String RUNNING_SERVICES_COMMAND = "am start -n com.android.settings/.SubSettings "
			+ "--es :settings:show_fragment com.android.settings.applications.RunningServices";
	private static final int[] KNOWN_RAM_SIZES = {1, 2, 3, 4, 6, 8, 10, 12, 16, 24, 32, 48, 64};

	public RecentsEnhancements(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... key) {
		if (Xprefs == null) return;
		showMemInfo = Xprefs.getBoolean("RecentMemInfo", false);
		showZram = Xprefs.getBoolean("RecentMemInfoZram", false);
		showChip = Xprefs.getBoolean("RecentMemInfoChip", false);
		lockEnabled = Xprefs.getBoolean("RecentAppLockEnabled", false);
		lockedApps = Xprefs.getStringSet("RecentLockedApps", Collections.emptySet());
		freeformEnabled = Xprefs.getBoolean("LauncherFreeformGesture", false);
		offProgress = Xprefs.getSliderFloat("LauncherFreeformProgress", 3f);
		try {
			freeformMode = Integer.parseInt(Xprefs.getString("LauncherFreeformMode", "0"));
		} catch (Throwable ignored) {
			freeformMode = MODE_AOSP;
		}

		// All prefs are consumed live by the hooks; no launcher restart is required
		if (key.length == 0 || key[0] == null) return;
		switch (key[0]) {
			case "RecentMemInfo":
			case "RecentMemInfoZram":
			case "RecentMemInfoChip": {
				MemInfoView view = memInfoViewRef == null ? null : memInfoViewRef.get();
				if (view != null) mainHandler.post(view::applyPrefs);
				break;
			}
			case "RecentAppLockEnabled":
			case "RecentLockedApps":
				mainHandler.post(this::refreshLockIndicators);
				break;
			default:
				break;
		}
	}

	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam param) {
		try {
			ReflectedClass wrapper = ReflectedClass.ofIfPossible("com.android.systemui.shared.system.ActivityManagerWrapper");
			activityManagerWrapperClass = wrapper.getClazz();
		} catch (Throwable ignored) { }
		hookMemInfo();
		hookAppLock();
		hookFreeform();
	}

	// ── Memory info readout ──────────────────────────────────────────────

	private void hookMemInfo() {
		try {
			ReflectedClass actionsView = ReflectedClass.ofIfPossible("com.android.quickstep.views.OverviewActionsView");
			if (actionsView.getClazz() == null) return;

			actionsView.after("onFinishInflate").run(param -> {
				try {
					if (!(param.thisObject instanceof FrameLayout)) return;
					FrameLayout container = (FrameLayout) param.thisObject;
					MemInfoView view = new MemInfoView(container.getContext(), container);
					container.addView(view, new FrameLayout.LayoutParams(
							ViewGroup.LayoutParams.WRAP_CONTENT,
							ViewGroup.LayoutParams.WRAP_CONTENT,
							Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM));
					memInfoViewRef = new WeakReference<>(view);
					view.applyPrefs();
				} catch (Throwable throwable) {
					log("RecentsEnhancements: meminfo view attach failed", throwable);
				}
			});

			actionsView.after("updateVerticalMargin").run(param -> {
				try {
					MemInfoView view = memInfoViewRef == null ? null : memInfoViewRef.get();
					if (view != null) view.updateVerticalMargin();
				} catch (Throwable ignored) { }
			});
		} catch (Throwable throwable) {
			log("RecentsEnhancements: meminfo hooks unavailable", throwable);
		}
	}

	private static Looper workerLooper() {
		HandlerThread thread = workerThread;
		if (thread != null) return thread.getLooper();
		synchronized (RecentsEnhancements.class) {
			if (workerThread == null) {
				workerThread = new HandlerThread("PXMemInfo");
				workerThread.start();
			}
			return workerThread.getLooper();
		}
	}

	@SuppressLint("ViewConstructor")
	private class MemInfoView extends TextView {
		private final View container;
		private final ActivityManager activityManager;
		private final ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
		private final String totalResult;
		private final int textColor;
		private final MemoryWorker worker = new MemoryWorker(this);
		private final ViewTreeObserver.OnPreDrawListener preDrawListener = () -> {
			syncWithActionButtons();
			return true;
		};
		private Handler handler;
		private boolean aggregatedVisible;

		MemInfoView(Context context, View container) {
			super(context);
			this.container = container;
			activityManager = context.getSystemService(ActivityManager.class);
			totalResult = formatTotalMemory();
			textColor = overviewButtonColor();
			setTextColor(textColor);
			setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SIZE_SP);
			setGravity(Gravity.CENTER);
			setVisibility(GONE);
			setOnClickListener(view -> openRunningServices());
		}

		void applyPrefs() {
			try {
				setVisibility(showMemInfo ? INVISIBLE : GONE);
				syncWithActionButtons();
				Drawable background = null;
				if (showChip) {
					background = actionButtonBackground();
					if (background == null) background = chipBackground();
				}
				setBackground(background);
				int horizontal = showChip ? dp(CHIP_PADDING_HORIZONTAL_DP) : 0;
				int vertical = showChip ? dp(CHIP_PADDING_VERTICAL_DP) : 0;
				setPadding(horizontal, vertical, horizontal, vertical);
				updateVerticalMargin();
				if (aggregatedVisible) restartMonitoring();
			} catch (Throwable throwable) {
				log("RecentsEnhancements: meminfo applyPrefs failed", throwable);
			}
		}

		void updateVerticalMargin() {
			try {
				if (!(getLayoutParams() instanceof FrameLayout.LayoutParams)) return;
				FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) getLayoutParams();
				int claimedSpace = 0;
				Object deviceProfile = fieldOf(container, "mDp");
				Object claimed = deviceProfile == null ? null : callOf(deviceProfile, "getOverviewActionsClaimedSpaceBelow");
				if (claimed instanceof Integer) {
					claimedSpace = (Integer) claimed;
				} else {
					WindowInsets insets = container.getRootWindowInsets();
					if (insets != null) claimedSpace = insets.getStableInsetBottom();
				}
				params.bottomMargin = claimedSpace + dp(BOTTOM_GAP_DP);
				params.gravity = Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
				setLayoutParams(params);
			} catch (Throwable ignored) { }
		}

		@Override
		public void onVisibilityAggregated(boolean isVisible) {
			super.onVisibilityAggregated(isVisible);
			aggregatedVisible = isVisible;
			if (isVisible && showMemInfo) startMonitoring();
			else stopMonitoring();
		}

		@Override
		protected void onAttachedToWindow() {
			super.onAttachedToWindow();
			getViewTreeObserver().addOnPreDrawListener(preDrawListener);
		}

		@Override
		protected void onDetachedFromWindow() {
			getViewTreeObserver().removeOnPreDrawListener(preDrawListener);
			stopMonitoring();
			super.onDetachedFromWindow();
		}

		private View actionButtons() {
			Object buttons = fieldOf(container, "mActionButtons");
			if (buttons instanceof View) return (View) buttons;
			Context context = container.getContext();
			int id = context.getResources().getIdentifier("action_buttons", "id", context.getPackageName());
			return id == 0 ? null : container.findViewById(id);
		}

		private void syncWithActionButtons() {
			if (!showMemInfo) return;
			View buttons = actionButtons();
			if (buttons == null) return;
			int target = buttons.getVisibility() == VISIBLE && buttons.getAlpha() > 0f ? VISIBLE : INVISIBLE;
			if (getVisibility() != target) setVisibility(target);
			if (getAlpha() != buttons.getAlpha()) setAlpha(buttons.getAlpha());
		}

		private void restartMonitoring() {
			stopMonitoring();
			if (showMemInfo) startMonitoring();
		}

		private void startMonitoring() {
			if (handler != null) return;
			handler = new Handler(workerLooper());
			handler.post(worker);
		}

		private void stopMonitoring() {
			if (handler != null) handler.removeCallbacks(worker);
			handler = null;
		}

		boolean isMonitoring() {
			return handler != null;
		}

		private String formatTotalMemory() {
			try {
				if (activityManager != null) activityManager.getMemoryInfo(memoryInfo);
				double totalGb = memoryInfo.totalMem / (1024.0 * 1024.0 * 1024.0);
				return roundToKnownRamSize(totalGb) + " GB";
			} catch (Throwable throwable) {
				return "";
			}
		}

		private int roundToKnownRamSize(double memoryGb) {
			if (memoryGb <= 0) return 1;
			for (int size : KNOWN_RAM_SIZES) {
				if (memoryGb <= size) return size;
			}
			return KNOWN_RAM_SIZES[KNOWN_RAM_SIZES.length - 1];
		}

		private long freeMemory() {
			Map<String, Long> meminfo = readMemInfo();
			long free = valueOf(meminfo, "MemFree");
			long cached = valueOf(meminfo, "Buffers")
					+ valueOf(meminfo, "Cached")
					+ (meminfo.containsKey("KReclaimable") ? valueOf(meminfo, "KReclaimable") : valueOf(meminfo, "SReclaimable"))
					- valueOf(meminfo, "Mapped");
			return (free + Math.max(cached, 0L)) * 1024L + totalBackgroundMemory();
		}

		private Map<String, Long> readMemInfo() {
			Map<String, Long> values = new HashMap<>();
			try (BufferedReader reader = new BufferedReader(new FileReader("/proc/meminfo"))) {
				String line;
				while ((line = reader.readLine()) != null) {
					String[] parts = line.trim().split("\\s+");
					if (parts.length < 2) continue;
					String name = parts[0].endsWith(":") ? parts[0].substring(0, parts[0].length() - 1) : parts[0];
					try {
						values.put(name, Long.parseLong(parts[1]));
					} catch (NumberFormatException ignored) { }
				}
			} catch (Throwable ignored) { }
			return values;
		}

		private long totalBackgroundMemory() {
			try {
				if (activityManager == null) return 0L;
				List<ActivityManager.RunningAppProcessInfo> processes = activityManager.getRunningAppProcesses();
				if (processes == null || processes.isEmpty()) return 0L;
				int[] pids = new int[processes.size()];
				for (int index = 0; index < processes.size(); index++) pids[index] = processes.get(index).pid;
				Debug.MemoryInfo[] infos = activityManager.getProcessMemoryInfo(pids);
				if (infos == null) return 0L;
				long total = 0L;
				for (int index = 0; index < processes.size() && index < infos.length; index++) {
					if (infos[index] != null && processes.get(index).importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND) {
						total += (long) infos[index].getTotalPss() * 1024L;
					}
				}
				return total;
			} catch (Throwable ignored) {
				return 0L;
			}
		}

		private long zramSize() {
			if (!showZram) return 0L;
			try {
				String diskSize = readFirstLine("/sys/block/zram0/disksize");
				if (diskSize != null) {
					long size = Long.parseLong(diskSize.trim());
					if (size > 0) return size;
				}
			} catch (Throwable ignored) { }
			try (BufferedReader reader = new BufferedReader(new FileReader("/proc/swaps"))) {
				String line;
				while ((line = reader.readLine()) != null) {
					if (!line.contains("zram0")) continue;
					String[] parts = line.trim().split("\\s+");
					if (parts.length > 2) return Long.parseLong(parts[2]) * 1024L;
				}
			} catch (Throwable ignored) { }
			return 0L;
		}

		String buildText() {
			String available = Formatter.formatShortFileSize(getContext(), freeMemory());
			long zram = zramSize();
			String total = zram > 0
					? totalResult + " + " + Formatter.formatShortFileSize(getContext(), zram)
					: totalResult;
			return String.format(Locale.getDefault(), MEMINFO_TEXT, available, total);
		}

		void postText(String text) {
			post(() -> setText(text));
		}

		void reschedule(Runnable task) {
			if (handler == null) return;
			handler.removeCallbacks(task);
			handler.postDelayed(task, REFRESH_INTERVAL_MS);
		}

		private void openRunningServices() {
			new Thread(() -> {
				try {
					XPLauncher.enqueueProxyCommand(proxy -> {
						String[] output = proxy.runRootCommand(RUNNING_SERVICES_COMMAND);
						String joined = output == null ? "" : String.join(" ", output);
						if (joined.toLowerCase(Locale.ROOT).contains("error") || joined.contains("Exception")) {
							post(MemInfoView.this::openAppList);
						}
					});
				} catch (Throwable throwable) {
					post(MemInfoView.this::openAppList);
				}
			}).start();
		}

		private void openAppList() {
			try {
				getContext().startActivity(new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)
						.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
			} catch (ActivityNotFoundException ignored) { }
		}

		@SuppressLint("DiscouragedApi")
		private int overviewButtonColor() {
			try {
				int id = getContext().getResources().getIdentifier("overview_button", "color", getContext().getPackageName());
				if (id != 0) return getContext().getColor(id);
			} catch (Throwable ignored) { }
			return getCurrentTextColor();
		}

		private Drawable actionButtonBackground() {
			View buttons = actionButtons();
			if (!(buttons instanceof ViewGroup)) return null;
			ViewGroup group = (ViewGroup) buttons;
			for (int index = 0; index < group.getChildCount(); index++) {
				View child = group.getChildAt(index);
				if (!(child instanceof Button) || child.getBackground() == null) continue;
				Drawable.ConstantState state = child.getBackground().getConstantState();
				return state == null ? null : state.newDrawable(getResources()).mutate();
			}
			return null;
		}

		private Drawable chipBackground() {
			GradientDrawable drawable = new GradientDrawable();
			drawable.setCornerRadius(dp(CHIP_RADIUS_DP));
			drawable.setColor((textColor & 0x00FFFFFF) | (CHIP_ALPHA << 24));
			return drawable;
		}

		private int dp(int value) {
			return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
		}
	}

	private static class MemoryWorker implements Runnable {
		private final WeakReference<MemInfoView> viewRef;

		MemoryWorker(MemInfoView view) {
			viewRef = new WeakReference<>(view);
		}

		@Override
		public void run() {
			MemInfoView view = viewRef.get();
			if (view == null || !view.isMonitoring()) return;
			try {
				view.postText(view.buildText());
			} catch (Throwable ignored) { }
			view.reschedule(this);
		}
	}

	private static String readFirstLine(String path) {
		try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
			return reader.readLine();
		} catch (Throwable ignored) {
			return null;
		}
	}

	private static long valueOf(Map<String, Long> values, String key) {
		Long value = values.get(key);
		return value == null ? 0L : value;
	}

	// ── App lock (clear-all protection) ──────────────────────────────────

	private void hookAppLock() {
		try {
			ReflectedClass taskView = ReflectedClass.ofIfPossible("com.android.quickstep.views.TaskView");
			if (taskView.getClazz() == null) return;
			taskViewClass = taskView.getClazz();
			ReflectedClass iconChip = ReflectedClass.ofIfPossible("com.android.quickstep.views.IconAppChipView");
			iconChipClass = iconChip.getClazz();

			ReflectedClass recentsView = ReflectedClass.ofIfPossible("com.android.quickstep.views.RecentsView");
			if (recentsView.getClazz() != null) {
				recentsView.afterConstruction().run(param -> {
					if (param.thisObject instanceof ViewGroup) recentsViewRef = new WeakReference<>((ViewGroup) param.thisObject);
				});
				recentsView.before("dismissAllTasks").run(this::onDismissAllTasks);
			}

			if (iconChipClass != null) {
				iconChip.after("setText").run(param -> {
					try {
						if (!(param.thisObject instanceof View)) return;
						View chip = (View) param.thisObject;
						setAdditionalInstanceField(chip, PLAIN_CHIP_TITLE, param.args.length > 0 ? param.args[0] : null);
						applyChipIndicator(chip);
					} catch (Throwable throwable) {
						log("RecentsEnhancements: chip indicator failed", throwable);
					}
				});
			}

			ReflectedClass iconView = ReflectedClass.ofIfPossible("com.android.quickstep.views.IconView");
			if (iconView.getClazz() != null) {
				iconView.after("onDraw").run(param -> {
					try {
						if (!lockEnabled) return;
						if (!(param.thisObject instanceof View)) return;
						View icon = (View) param.thisObject;
						if (insideChip(icon)) return;
						if (!isLockedTask(ownerTask(icon))) return;
						if (param.args.length > 0 && param.args[0] instanceof Canvas) {
							drawLockBadge((Canvas) param.args[0], icon);
						}
					} catch (Throwable ignored) { }
				});
			}
		} catch (Throwable throwable) {
			log("RecentsEnhancements: app lock hooks unavailable", throwable);
		}
	}

	private void onDismissAllTasks(HookHelper.RunParam param) {
		try {
			if (!lockEnabled) return;
			if (!(param.thisObject instanceof ViewGroup)) return;
			ViewGroup recentsView = (ViewGroup) param.thisObject;
			List<View> unlockedViews = new ArrayList<>();
			boolean hasLocked = false;
			for (int index = 0; index < recentsView.getChildCount(); index++) {
				View child = recentsView.getChildAt(index);
				if (taskViewClass == null || !taskViewClass.isInstance(child)) continue;
				if (isLockedView(child)) hasLocked = true;
				else unlockedViews.add(child);
			}
			if (!hasLocked) return;

			// Cancel the original clear-all and remove the unlocked tasks one by one
			param.setResult(null);

			List<Integer> taskIds = new ArrayList<>();
			for (View view : unlockedViews) {
				for (Object task : tasksOf(view)) {
					Integer taskId = taskIdOf(task);
					if (taskId != null) taskIds.add(taskId);
				}
			}
			if (taskIds.isEmpty() || activityManagerWrapperClass == null) return;

			Object activityManager;
			try {
				activityManager = callStaticMethod(activityManagerWrapperClass, "getInstance");
			} catch (Throwable ignored) {
				return;
			}
			if (activityManager == null) return;
			Object wrapper = activityManager;
			lockExecutor.execute(() -> {
				for (Integer taskId : taskIds) {
					try {
						callMethod(wrapper, "removeTask", taskId);
					} catch (Throwable throwable) {
						log("RecentsEnhancements: failed removing task " + taskId, throwable);
					}
				}
			});
		} catch (Throwable throwable) {
			log("RecentsEnhancements: dismissAllTasks guard failed", throwable);
		}
	}

	private void refreshLockIndicators() {
		try {
			ViewGroup recentsView = recentsViewRef == null ? null : recentsViewRef.get();
			if (recentsView == null || taskViewClass == null) return;
			for (int index = 0; index < recentsView.getChildCount(); index++) {
				View child = recentsView.getChildAt(index);
				if (!taskViewClass.isInstance(child)) continue;
				for (View icon : iconViewsOf(child)) {
					if (iconChipClass != null && iconChipClass.isInstance(icon)) applyChipIndicator(icon);
					else icon.invalidate();
				}
			}
		} catch (Throwable throwable) {
			log("RecentsEnhancements: lock indicator refresh failed", throwable);
		}
	}

	private void applyChipIndicator(View chip) {
		try {
			Object titleValue = fieldOf(chip, "appTitle");
			if (!(titleValue instanceof TextView)) return;
			TextView title = (TextView) titleValue;
			Object stored = getAdditionalInstanceField(chip, PLAIN_CHIP_TITLE);
			CharSequence plain;
			if (stored instanceof CharSequence) {
				plain = (CharSequence) stored;
			} else {
				CharSequence current = title.getText();
				if (current == null) return;
				plain = current.toString();
			}
			boolean locked = lockEnabled && isLockedTask(ownerTask(chip));
			if (!locked) {
				if (!plain.equals(title.getText())) title.setText(plain);
				return;
			}
			RecentsLockGlyph glyph = new RecentsLockGlyph(title.getCurrentTextColor());
			int size = Math.max(1, (int) title.getTextSize());
			glyph.setBounds(0, 0, size, size);
			SpannableStringBuilder builder = new SpannableStringBuilder()
					.append(LOCK_PLACEHOLDER, new ImageSpan(glyph, DynamicDrawableSpan.ALIGN_CENTER), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
					.append(" ")
					.append(plain);
			title.setText(builder);
		} catch (Throwable throwable) {
			log("RecentsEnhancements: chip indicator failed", throwable);
		}
	}

	private void drawLockBadge(Canvas canvas, View icon) {
		float size = Math.min(icon.getWidth(), icon.getHeight()) * BADGE_RATIO;
		if (size <= 0f) return;
		float cx = icon.getWidth() - size / 2f;
		float cy = icon.getHeight() - size / 2f;
		badgePaint.setStyle(Paint.Style.FILL);
		badgePaint.setColor(Color.BLACK);
		badgePaint.setAlpha(BADGE_ALPHA);
		canvas.drawCircle(cx, cy, size / 2f, badgePaint);
		int glyph = (int) (size * 0.62f);
		badgeGlyph.setBounds((int) (cx - glyph / 2f), (int) (cy - glyph / 2f), (int) (cx + glyph / 2f), (int) (cy + glyph / 2f));
		badgeGlyph.draw(canvas);
	}

	private boolean isLockedView(View taskView) {
		for (Object task : tasksOf(taskView)) {
			if (isLockedTask(task)) return true;
		}
		return false;
	}

	private boolean isLockedTask(Object task) {
		try {
			if (task == null) return false;
			Object key = fieldOf(task, "key");
			if (key == null) return false;
			String packageName = null;
			Object component = callOf(key, "getComponent");
			if (component instanceof ComponentName) packageName = ((ComponentName) component).getPackageName();
			if (packageName == null) {
				Object direct = callOf(key, "getPackageName");
				if (direct instanceof String) packageName = (String) direct;
			}
			if (packageName == null) return false;
			Object userId = fieldOf(key, "userId");
			return isLockedPackage(packageName, userId instanceof Integer ? (Integer) userId : 0);
		} catch (Throwable ignored) {
			return false;
		}
	}

	private boolean isLockedPackage(String packageName, int userId) {
		Set<String> apps = lockedApps;
		if (apps == null || apps.isEmpty()) return false;
		// Accept both plain package names and PLE-style "package:userId" entries
		return apps.contains(packageName) || apps.contains(packageName + ":" + userId);
	}

	private List<Object> tasksOf(View taskView) {
		List<Object> tasks = new ArrayList<>();
		Object containers = callOf(taskView, "getTaskContainers");
		if (containers instanceof List<?> list) {
			for (Object container : list) {
				Object task = callOf(container, "getTask");
				if (task == null) task = fieldOf(container, "task");
				if (task != null) tasks.add(task);
			}
		}
		if (!tasks.isEmpty()) return tasks;
		Object task = callOf(taskView, "getTask");
		if (task != null) tasks.add(task);
		Object secondary = fieldOf(taskView, "mSecondaryTask");
		if (secondary != null) tasks.add(secondary);
		return tasks;
	}

	private Integer taskIdOf(Object task) {
		Object key = fieldOf(task, "key");
		Object id = key == null ? null : fieldOf(key, "id");
		return id instanceof Integer ? (Integer) id : null;
	}

	private List<View> iconViewsOf(View taskView) {
		List<View> icons = new ArrayList<>();
		Object containers = callOf(taskView, "getTaskContainers");
		if (containers instanceof List<?> list) {
			for (Object container : list) {
				Object icon = callOf(container, "getIconView");
				if (icon == null) icon = fieldOf(container, "iconView");
				if (icon instanceof View) icons.add((View) icon);
			}
		}
		if (icons.isEmpty()) {
			Object idContainers = callOf(taskView, "getTaskIdAttributeContainers");
			if (idContainers instanceof Object[] array) {
				for (Object container : array) {
					Object icon = callOf(container, "getIconView");
					if (icon instanceof View) icons.add((View) icon);
				}
			}
		}
		if (icons.isEmpty()) {
			Object icon = callOf(taskView, "getIconView");
			if (icon instanceof View) icons.add((View) icon);
		}
		return icons;
	}

	private Object ownerTask(View icon) {
		View taskView = owningTaskView(icon);
		if (taskView == null) return null;
		Object containers = callOf(taskView, "getTaskContainers");
		if (containers instanceof List<?> list) {
			for (Object container : list) {
				Object containerIcon = callOf(container, "getIconView");
				if (containerIcon == null) containerIcon = fieldOf(container, "iconView");
				if (containerIcon == icon) {
					Object task = callOf(container, "getTask");
					return task != null ? task : fieldOf(container, "task");
				}
			}
		}
		Object idContainers = callOf(taskView, "getTaskIdAttributeContainers");
		if (idContainers instanceof Object[] array) {
			for (Object container : array) {
				if (callOf(container, "getIconView") == icon) return callOf(container, "getTask");
			}
		}
		if (callOf(taskView, "getIconView") == icon) return callOf(taskView, "getTask");
		return null;
	}

	private View owningTaskView(View view) {
		ViewParent parent = view.getParent();
		while (parent != null) {
			if (taskViewClass != null && taskViewClass.isInstance(parent)) return (View) parent;
			parent = parent.getParent();
		}
		return null;
	}

	private boolean insideChip(View view) {
		ViewParent parent = view.getParent();
		while (parent != null) {
			if (iconChipClass != null && iconChipClass.isInstance(parent)) return true;
			if (taskViewClass != null && taskViewClass.isInstance(parent)) return false;
			parent = parent.getParent();
		}
		return false;
	}

	/** Programmatically drawn padlock glyph (the module cannot inject drawable resources here). */
	private static class RecentsLockGlyph extends Drawable {
		private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final Path shackle = new Path();
		private final Path body = new Path();
		private final RectF arc = new RectF();
		private final RectF rectangle = new RectF();

		RecentsLockGlyph(int color) {
			fillPaint.setStyle(Paint.Style.FILL);
			fillPaint.setColor(color);
			strokePaint.setStyle(Paint.Style.STROKE);
			strokePaint.setColor(color);
			strokePaint.setStrokeCap(Paint.Cap.ROUND);
		}

		@Override
		public void draw(@NonNull Canvas canvas) {
			Rect bounds = getBounds();
			if (bounds.isEmpty()) return;
			float left = bounds.left;
			float top = bounds.top;
			float width = bounds.width();
			float height = bounds.height();
			float leg = width * 0.16f;
			float legBottom = top + height * 0.55f;

			strokePaint.setStrokeWidth(Math.max(1f, width * 0.12f));
			arc.set(left + leg, top + height * 0.10f, left + width - leg, top + height * 0.52f);
			shackle.reset();
			shackle.moveTo(left + leg, legBottom);
			shackle.lineTo(left + leg, arc.centerY());
			shackle.arcTo(arc, 180f, 180f, false);
			shackle.lineTo(left + width - leg, legBottom);
			canvas.drawPath(shackle, strokePaint);

			rectangle.set(left + width * 0.14f, top + height * 0.45f, left + width * 0.86f, top + height * 0.90f);
			body.reset();
			body.addRoundRect(rectangle, width * 0.10f, width * 0.10f, Path.Direction.CW);
			canvas.drawPath(body, fillPaint);
		}

		@Override
		public void setAlpha(int alpha) {
			fillPaint.setAlpha(alpha);
			strokePaint.setAlpha(alpha);
		}

		@Override
		public void setColorFilter(ColorFilter colorFilter) {
			fillPaint.setColorFilter(colorFilter);
			strokePaint.setColorFilter(colorFilter);
		}

		@Override
		public int getOpacity() {
			return PixelFormat.TRANSLUCENT;
		}
	}

	// ── Freeform gesture ─────────────────────────────────────────────────

	private void hookFreeform() {
		try {
			ReflectedClass dragLayer = ReflectedClass.ofIfPossible("com.android.launcher3.dragndrop.DragLayer");
			if (dragLayer.getClazz() != null) {
				dragLayer.afterConstruction().run(param -> {
					try {
						if (!freeformEnabled) return;
						if (param.thisObject instanceof ViewGroup && param.args.length > 0 && param.args[0] instanceof Context) {
							FreeformHintViewController.init((ViewGroup) param.thisObject, (Context) param.args[0]);
						}
					} catch (Throwable throwable) {
						log("RecentsEnhancements: freeform hint view init failed", throwable);
					}
				});
			}

			ReflectedClass absSwipe = ReflectedClass.ofIfPossible("com.android.quickstep.AbsSwipeUpHandler");
			ReflectedClass gestureState = ReflectedClass.ofIfPossible("com.android.quickstep.GestureState");
			ReflectedClass gestureEndTarget = ReflectedClass.ofIfPossible("com.android.quickstep.GestureState$GestureEndTarget");
			if (absSwipe.getClazz() == null || gestureState.getClazz() == null
					|| gestureEndTarget.getClazz() == null || gestureEndTarget.getClazz().getEnumConstants() == null) {
				return;
			}

			// Hint view updates while the gesture is in progress
			ReflectedClass.ReflectionConsumer shiftConsumer = param -> {
				try {
					if (!freeformEnabled) return;
					Object shift = getObjectField(getObjectField(param.thisObject, "mCurrentShift"), "value");
					if (shift instanceof Float) updateHintView(param.thisObject, (Float) shift);
				} catch (Throwable throwable) {
					log("RecentsEnhancements: freeform hint update failed", throwable);
				}
			};
			absSwipe.after("onCurrentShiftUpdated").run(shiftConsumer);
			absSwipe.after("updateFinalShift").run(shiftConsumer);

			absSwipe.after("onGestureEnded").run(param -> {
				try {
					FreeformHintViewController.reset();
				} catch (Throwable ignored) { }
			});

			Class<?> gestureStateClass = gestureState.getClazz();
			Class<?> gestureEndTargetClass = gestureEndTarget.getClazz();
			absSwipe.after("initStateCallbacks").run(param -> installFreeformTrigger(param, gestureStateClass, gestureEndTargetClass));
		} catch (Throwable throwable) {
			log("RecentsEnhancements: freeform hooks unavailable", throwable);
		}
	}

	private void installFreeformTrigger(HookHelper.RunParam param, Class<?> gestureStateClass, Class<?> gestureEndTargetClass) {
		try {
			if (!freeformEnabled) return;
			Object handler = param.thisObject;
			Object gestureState = getObjectField(handler, "mGestureState");
			int stateEndTargetSet = (int) getStaticObjectField(gestureStateClass, "STATE_END_TARGET_SET");
			Object homeTarget = gestureEndTargetClass.getEnumConstants()[0];
			callMethod(gestureState, "runOnceAtState", stateEndTargetSet, (Runnable) () -> {
				try {
					Object shift = getObjectField(getObjectField(handler, "mCurrentShift"), "value");
					if (!(shift instanceof Float) || (Float) shift <= offProgress) return;
					if (triggerFreeform(handler)) {
						callMethod(getObjectField(handler, "mGestureState"), "setEndTarget", homeTarget);
					}
				} catch (Throwable throwable) {
					log("RecentsEnhancements: freeform gesture trigger failed", throwable);
				}
			});
		} catch (Throwable throwable) {
			log("RecentsEnhancements: freeform trigger install failed", throwable);
		}
	}

	private void updateHintView(Object handler, float shift) {
		float hintStart = offProgress - 0.7f;
		FreeformHintView.HintPhase phase;
		if (shift < hintStart) phase = FreeformHintView.HintPhase.HIDDEN;
		else if (shift < offProgress) phase = FreeformHintView.HintPhase.SWIPE_UP_HINT;
		else phase = FreeformHintView.HintPhase.EXPAND;

		FreeformHintView hintView = FreeformHintViewController.getHintView();
		if (hintView == null) return;

		if (phase != hintView.getPhase()) {
			try {
				callMethod(handler, "performHapticFeedback");
			} catch (Throwable ignored) { }
		}
		hintView.setDisplayRotation(displayRotationOf(handler));
		hintView.setPhase(phase);
		if (phase == FreeformHintView.HintPhase.EXPAND) {
			Rect bounds = taskBoundsOf(handler);
			if (bounds != null) hintView.setTaskBounds(bounds);
		}
	}

	private boolean triggerFreeform(Object handler) {
		try {
			Object recentsView = getObjectField(handler, "mRecentsView");
			Object runningTaskView = callMethod(recentsView, "getRunningTaskView");
			if (runningTaskView == null) return false;
			Object containers = callOf(runningTaskView, "getTaskContainers");
			if (!(containers instanceof List<?> list) || list.isEmpty()) return false;
			Object container = list.get(0);
			Object task = callOf(container, "getTask");
			if (task == null) return false;

			Rect bounds = null;
			Object snapshot = callOf(container, "getSnapshotView");
			if (snapshot instanceof View) {
				View snapshotView = (View) snapshot;
				int[] position = new int[2];
				snapshotView.getLocationOnScreen(position);
				bounds = new Rect(position[0], position[1],
						position[0] + snapshotView.getWidth(), position[1] + snapshotView.getHeight());
			}

			boolean launched;
			switch (freeformMode) {
				case MODE_AOSP:
					launched = startFreeformFromRecents(task, bounds);
					break;
				case MODE_BUBBLE:
					launched = startAppBubble(task);
					break;
				case MODE_YAMF:
				case MODE_REYAMF:
					launched = currentToFreeform();
					break;
				default:
					launched = startFreeformByIntent(task);
					break;
			}

			if (launched) {
				FreeformHintView hintView = FreeformHintViewController.getHintView();
				if (hintView != null) hintView.playReleaseAnimation(null);
			}
			return launched;
		} catch (Throwable throwable) {
			log("RecentsEnhancements: freeform trigger failed", throwable);
			return false;
		}
	}

	private boolean startFreeformFromRecents(Object task, Rect bounds) {
		try {
			Object wrapper = newActivityManagerWrapper();
			if (wrapper == null) return false;
			Object key = getObjectField(task, "key");
			ActivityOptions options = ActivityOptions.makeBasic();
			callMethod(options, "setLaunchWindowingMode", WINDOWING_MODE_FREEFORM);
			if (bounds != null) callMethod(options, "setLaunchBounds", bounds);
			callMethod(wrapper, "startActivityFromRecents", key, options);
			return true;
		} catch (Throwable throwable) {
			log("RecentsEnhancements: AOSP freeform launch failed", throwable);
			return false;
		}
	}

	private Object newActivityManagerWrapper() {
		if (activityManagerWrapperClass == null) return null;
		try {
			Constructor<?> constructor = activityManagerWrapperClass.getDeclaredConstructor();
			constructor.setAccessible(true);
			return constructor.newInstance();
		} catch (Throwable ignored) { }
		try {
			return callStaticMethod(activityManagerWrapperClass, "getInstance");
		} catch (Throwable ignored) {
			return null;
		}
	}

	private boolean startFreeformByIntent(Object task) {
		try {
			boolean lmao = freeformMode == MODE_LMAO;
			Intent intent = new Intent(lmao
					? "com.libremobileos.freeform.START_FREEFORM"
					: "com.sunshine.freeform.start_freeform")
					.setPackage(lmao ? "com.libremobileos.freeform" : "com.sunshine.freeform");
			Object key = getObjectField(task, "key");
			Object packageName = callOf(key, "getPackageName");
			if (!(packageName instanceof String)) return false;
			Object topComponent = callOf(task, "getTopComponent");
			Object className = topComponent == null ? null : callOf(topComponent, "getClassName");
			Object userId = fieldOf(key, "userId");
			Object taskId = fieldOf(key, "id");
			intent.putExtra("packageName", (String) packageName);
			if (className instanceof String) intent.putExtra("activityName", (String) className);
			if (userId instanceof Integer) intent.putExtra("userId", (Integer) userId);
			if (taskId instanceof Integer) intent.putExtra("taskId", (Integer) taskId);
			mContext.sendBroadcast(intent);
			return true;
		} catch (Throwable throwable) {
			log("RecentsEnhancements: freeform broadcast failed", throwable);
			return false;
		}
	}

	private boolean currentToFreeform() {
		try {
			String action = freeformMode == MODE_REYAMF
					? "com.mja.reyamf.action.CURRENT_TO_WINDOW"
					: "io.github.duzhaokun123.yamf.action.CURRENT_TO_WINDOW";
			mContext.sendBroadcast(new Intent(action));
			return true;
		} catch (Throwable throwable) {
			log("RecentsEnhancements: current-to-freeform broadcast failed", throwable);
			return false;
		}
	}

	private boolean startAppBubble(Object task) {
		try {
			Object key = fieldOf(task, "key");
			if (key == null) return false;
			Object packageNameValue = callOf(key, "getPackageName");
			Object userIdValue = fieldOf(key, "userId");
			if (!(packageNameValue instanceof String) || !(userIdValue instanceof Integer)) return false;
			String packageName = (String) packageNameValue;
			int userId = (Integer) userIdValue;

			// Reuse the task's base intent so extras/data/categories survive
			Object baseIntentValue = fieldOf(key, "baseIntent");
			Intent intent = baseIntentValue instanceof Intent
					? new Intent((Intent) baseIntentValue)
					: new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
			Object topComponent = callOf(task, "getTopComponent");
			Object className = topComponent == null ? null : callOf(topComponent, "getClassName");
			if (className instanceof String) intent.setComponent(new ComponentName(packageName, (String) className));
			intent.setPackage(packageName);

			UserHandle userHandle;
			try {
				Constructor<UserHandle> constructor = UserHandle.class.getDeclaredConstructor(int.class);
				constructor.setAccessible(true);
				userHandle = constructor.newInstance(userId);
			} catch (Throwable throwable) {
				return false;
			}

			ReflectedClass systemUiProxyClass = ReflectedClass.ofIfPossible("com.android.quickstep.SystemUiProxy");
			if (systemUiProxyClass.getClazz() == null) return false;
			Object provider = staticFieldOf(systemUiProxyClass.getClazz(), "INSTANCE");
			if (provider == null) return false;
			Object systemUiProxy = callOf(provider, "get", mContext);
			if (systemUiProxy == null) systemUiProxy = callOf(provider, "get");
			if (systemUiProxy == null) return false;

			ReflectedClass entryPointClass = ReflectedClass.ofIfPossible("com.android.wm.shell.shared.bubbles.logging.EntryPoint");
			if (entryPointClass.getClazz() == null) return false;
			Object[] entryPoints = entryPointClass.getClazz().getEnumConstants();
			if (entryPoints == null || entryPoints.length == 0) return false;

			Method showAppBubble = null;
			for (Method method : systemUiProxy.getClass().getMethods()) {
				if ("showAppBubble".equals(method.getName()) && method.getParameterCount() == 4) {
					showAppBubble = method;
					break;
				}
			}
			if (showAppBubble == null) {
				log("RecentsEnhancements: showAppBubble with 4 params not found");
				return false;
			}
			Object entryPoint = entryPoints[0];
			Class<?>[] parameterTypes = showAppBubble.getParameterTypes();
			if (parameterTypes.length == 4 && parameterTypes[2] == int.class) {
				Object value = callOf(entryPoint, "getValue");
				entryPoint = value instanceof Integer ? value : ((Enum<?>) entryPoints[0]).ordinal();
			}
			showAppBubble.invoke(systemUiProxy, intent, userHandle, entryPoint, null);
			return true;
		} catch (Throwable throwable) {
			log("RecentsEnhancements: bubble launch failed", throwable);
			return false;
		}
	}

	private int displayRotationOf(Object handler) {
		try {
			Object recentsView = getObjectField(handler, "mRecentsView");
			Object orientedState = callMethod(recentsView, "getPagedViewOrientedState");
			Object rotation = callMethod(orientedState, "getDisplayRotation");
			if (rotation instanceof Integer) return (Integer) rotation;
		} catch (Throwable ignored) { }
		return Surface.ROTATION_0;
	}

	private Rect taskBoundsOf(Object handler) {
		try {
			Object recentsView = getObjectField(handler, "mRecentsView");
			Object runningTaskView = callMethod(recentsView, "getRunningTaskView");
			if (runningTaskView == null) return null;
			Method method = runningTaskView.getClass().getMethod("getThumbnailBounds", Rect.class, boolean.class);
			Rect bounds = new Rect();
			method.invoke(runningTaskView, bounds, true);
			return bounds;
		} catch (Throwable ignored) {
			return null;
		}
	}

	// ── Silent reflection helpers (PLE getFieldSilently/callMethodSilently) ──

	private static Object fieldOf(Object object, String name) {
		try {
			return getObjectField(object, name);
		} catch (Throwable ignored) {
			return null;
		}
	}

	private static Object callOf(Object object, String name, Object... args) {
		try {
			return callMethod(object, name, args);
		} catch (Throwable ignored) {
			return null;
		}
	}

	private static Object staticFieldOf(Class<?> type, String name) {
		try {
			return getStaticObjectField(type, name);
		} catch (Throwable ignored) {
			return null;
		}
	}
}
