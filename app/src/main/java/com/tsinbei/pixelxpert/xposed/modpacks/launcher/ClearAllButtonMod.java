package com.tsinbei.pixelxpert.xposed.modpacks.launcher;

import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.findMethodBestMatch;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.getObjectField;
import static com.tsinbei.pixelxpert.xposed.XPrefs.Xprefs;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;

import androidx.appcompat.view.ContextThemeWrapper;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModuleInterface;
import com.tsinbei.pixelxpert.R;
import com.tsinbei.pixelxpert.xposed.XPLauncher;
import com.tsinbei.pixelxpert.xposed.XposedModPack;
import com.tsinbei.pixelxpert.xposed.annotations.LauncherModPack;
import com.tsinbei.pixelxpert.xposed.utils.SystemUtils;
import com.tsinbei.pixelxpert.xposed.utils.reflection.ReflectedClass;

/**
 * Adds Clear all beside the native Screenshot and Select overview actions. The implementation is
 * derived from PixelLauncherEnhanced (GPL-3.0, da7139b7e6ac41377cfacfcc45610b5014e59f51).
 */
@SuppressWarnings("RedundantThrows")
@LauncherModPack
public class ClearAllButtonMod extends XposedModPack {
	private static final int OVERVIEW_ACTIONS = 1 << 3;
	private static final int CLEAR_ALL_BUTTON = 1 << 4;
	private static final String CLEAR_ALL_BUTTON_TAG = "pixelxpert_clear_all_button";

	private Object recentsView;
	private boolean enabled;
	private boolean removeScreenshot;
	private boolean equalActionWidths;

	public ClearAllButtonMod(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... key) {
		if (Xprefs == null) return;
		enabled = Xprefs.getBoolean("RecentClearAllReposition", false);
		removeScreenshot = Xprefs.getBoolean("RecentRemoveScreenshot", false);
		equalActionWidths = Xprefs.getBoolean("RecentEqualActionWidths", false);
		if (key.length > 0 && ("RecentClearAllReposition".equals(key[0])
				|| "RecentRemoveScreenshot".equals(key[0])
				|| "RecentEqualActionWidths".equals(key[0]))) {
			SystemUtils.killSelf();
		}
	}

	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam param) throws Throwable {
		ReflectedClass recentsViewClass = ReflectedClass.of("com.android.quickstep.views.RecentsView");
		ReflectedClass overviewActionsViewClass = ReflectedClass.ofIfPossible(
				"com.google.android.apps.nexuslauncher.overview.NexusOverviewActionsView");
		if (overviewActionsViewClass.getClazz() == null) {
			overviewActionsViewClass = ReflectedClass.of("com.android.quickstep.views.OverviewActionsView");
		}
		Method dismissAllTasks = findMethodBestMatch(recentsViewClass.getClazz(), "dismissAllTasks", View.class);
		Method onFinishInflate = findMethodBestMatch(overviewActionsViewClass.getClazz(), "onFinishInflate");

		recentsViewClass.afterConstruction().run(hook -> recentsView = hook.thisObject);
		overviewActionsViewClass.after(onFinishInflate).run(hook -> {
			ViewGroup overviewActionsView = (ViewGroup) hook.thisObject;
			ViewGroup actions = findActionButtons(overviewActionsView);
			if (actions == null) {
				log("ClearAllButton: action_buttons container unavailable");
				return;
			}
			Button existingButton = findClearAllButton(actions);
			if (existingButton != null) {
				updateActions(actions, existingButton);
				return;
			}
			Context launcherContext = actions.getContext();
			android.content.res.Resources launcherResources = launcherContext.getResources();

			Context themedContext = new ContextThemeWrapper(launcherContext,
					launcherResources.getIdentifier("ThemeControlHighlightWorkspaceColor", "style", launcherContext.getPackageName()));
			Button button = new Button(themedContext, null, 0,
					launcherResources.getIdentifier("OverviewActionButton", "style", launcherContext.getPackageName()));
			button.setId(View.generateViewId());
			button.setTag(CLEAR_ALL_BUTTON_TAG);
			button.setText(XPLauncher.moduleResources.getText(R.string.recents_clear_all));
			button.setMaxLines(1);
			button.setEllipsize(TextUtils.TruncateAt.END);
			button.setCompoundDrawablesWithIntrinsicBounds(
					XPLauncher.moduleResources.getDrawable(R.drawable.ic_clear_all, launcherContext.getTheme()),
					null, null, null);
			button.setOnClickListener(view -> {
				if (recentsView == null) return;
				try {
					dismissAllTasks.invoke(recentsView, view);
				} catch (Throwable ignored) { }
			});

			ViewGroup.MarginLayoutParams layoutParams = new ViewGroup.MarginLayoutParams(
					ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
			int spacing = launcherResources.getIdentifier(
					"overview_actions_button_spacing", "dimen", launcherContext.getPackageName());
			if (spacing != 0) layoutParams.setMarginStart(launcherResources.getDimensionPixelSize(spacing));
			button.setLayoutParams(layoutParams);
			copyNativeButtonBackground(actions, button);
			actions.addView(button);
			updateActions(actions, button);
			log("ClearAllButton: added overview action");
		});

		hookOptionalState("com.android.launcher3.uioverrides.states.BackgroundAppState",
				"getVisibleElements", false, hook -> {
					if (enabled) hook.setResult((Integer) hook.getResult() & ~CLEAR_ALL_BUTTON);
				});
		hookOptionalState("com.android.launcher3.uioverrides.states.OverviewModalTaskState",
				"getVisibleElements", true, hook -> {
					if (enabled) hook.setResult(OVERVIEW_ACTIONS);
				});
		hookOptionalState("com.android.quickstep.fallback.RecentsState", "hasClearAllButton", true,
					hook -> {
						if (enabled) hook.setResult(false);
					});
	}

	private void updateActions(ViewGroup actions, Button clearAllButton) {
		clearAllButton.setVisibility(enabled ? View.VISIBLE : View.GONE);
		int screenshotId = actions.getResources().getIdentifier("action_screenshot", "id", actions.getContext().getPackageName());
		View screenshot = screenshotId == 0 ? null : actions.findViewById(screenshotId);
		if (screenshot != null) screenshot.setVisibility(removeScreenshot ? View.GONE : View.VISIBLE);
		if (!equalActionWidths) return;
		int visibleButtons = 0;
		for (int index = 0; index < actions.getChildCount(); index++) {
			View child = actions.getChildAt(index);
			if (child instanceof Button && child.getVisibility() == View.VISIBLE) visibleButtons++;
		}
		if (visibleButtons == 0) return;
		int maxWidth = actions.getResources().getDisplayMetrics().widthPixels / visibleButtons;
		for (int index = 0; index < actions.getChildCount(); index++) {
			View child = actions.getChildAt(index);
			if (child instanceof Button) ((Button) child).setMaxWidth(maxWidth);
		}
	}

	private Button findClearAllButton(ViewGroup actions) {
		for (int index = 0; index < actions.getChildCount(); index++) {
			View child = actions.getChildAt(index);
			if (child instanceof Button && CLEAR_ALL_BUTTON_TAG.equals(child.getTag())) {
				return (Button) child;
			}
		}
		return null;
	}

	private void hookOptionalState(String className, String methodName, boolean before,
	                               ReflectedClass.ReflectionConsumer callback) {
		try {
			ReflectedClass clazz = ReflectedClass.ofIfPossible(className);
			if (clazz.getClazz() == null) return;
			if (before) clazz.before(methodName).run(callback);
			else clazz.after(methodName).run(callback);
		} catch (Throwable ignored) { }
	}

	private ViewGroup findActionButtons(ViewGroup root) {
		try {
			Object value = getObjectField(root, "mActionButtons");
			if (value instanceof LinearLayout) return (ViewGroup) value;
		} catch (Throwable ignored) { }
		Context launcherContext = root.getContext();
		int id = launcherContext.getResources().getIdentifier("action_buttons", "id", launcherContext.getPackageName());
		return id == 0 ? null : root.findViewById(id);
	}

	private void copyNativeButtonBackground(ViewGroup actions, Button button) {
		for (int index = 0; index < actions.getChildCount(); index++) {
			View child = actions.getChildAt(index);
			if (!(child instanceof Button) || child == button || child.getBackground() == null) continue;
			Drawable.ConstantState state = child.getBackground().getConstantState();
			if (state != null) button.setBackground(state.newDrawable().mutate());
			return;
		}
	}
}
