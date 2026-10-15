package com.tsinbei.pixelxpert.xposed.modpacks.systemui;

import static com.tsinbei.pixelxpert.xposed.XPrefs.Xprefs;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.callMethod;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.getBooleanField;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.getIntField;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.getObjectField;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import io.github.libxposed.api.XposedModuleInterface;
import com.tsinbei.pixelxpert.xposed.XposedModPack;
import com.tsinbei.pixelxpert.xposed.annotations.SystemUIModPack;
import com.tsinbei.pixelxpert.xposed.utils.reflection.ReflectedClass;
import com.tsinbei.pixelxpert.xposed.utils.toolkit.Logger;

/**
 * Bubble chat customizations: reverses the classic (phone, portrait) bubble layout so the
 * icons row sits at the bottom for one-handed reach, and lets the dismiss target be moved.
 * Adapted from Pixel Taskbar Enabler by beyman-dev (GPLv3).
 */
@SystemUIModPack
public class BubblesMods extends XposedModPack {
	private static final String POSITIONER = "com.android.wm.shell.bubbles.BubblePositioner";
	private static final String EXPANDED_VIEW = "com.android.wm.shell.bubbles.BubbleExpandedView";
	private static final String STACK_VIEW = "com.android.wm.shell.bubbles.BubbleStackView";
	private static final String DISMISS_VIEW = "com.android.wm.shell.shared.bubbles.DismissView";

	/** Scale applied to the selected bubble icon while the stack is expanded. */
	private static final float SELECTED_SCALE = 1.18f;
	private static final long SCALE_ANIM_DURATION_MS = 180L;
	/** SystemUI's springs reset the scale during (un)expansion; re-check shortly after. */
	private static final long[] RECHECK_DELAYS_MS = {0L, 350L, 750L};

	private static boolean enableReverseBubble = false;
	private static String dismissPosition = "CENTER";

	public BubblesMods(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {
		if (Xprefs == null) return;
		enableReverseBubble = Xprefs.getBoolean("enable_reverse_bubble", false);
		dismissPosition = Xprefs.getString("dismiss_position", "CENTER");
	}

	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) throws Throwable {
		applyReverseBubbleLayoutHooks();
		applyDismissPositionHooks();
	}

	// ---------------------------------------------------------------- positioner helpers

	/** True when the bubbles are shown in a horizontal row (phone in portrait, no bubble bar). */
	private static boolean isReversed(Object positioner) {
		if (!enableReverseBubble) return false;
		try {
			if (getBooleanField(positioner, "mShowingInBubbleBar")) return false;
			return !(Boolean) callMethod(positioner, "showBubblesVertically");
		} catch (Throwable t) {
			return false;
		}
	}

	/** Y (top) of the bubbles row when placed at the bottom, above the IME / navigation bar. */
	private static float bottomRowY(Object positioner) {
		Rect position = (Rect) getObjectField(positioner, "mPositionRect");
		int ime = (Integer) callMethod(positioner, "getImeHeight");
		return position.bottom - ime - getIntField(positioner, "mBubbleSize") - getIntField(positioner, "mExpandedViewPadding");
	}

	/** Y where the expanded view starts: the top of the usable area. */
	private static int expandedTopY(Object positioner) {
		Rect position = (Rect) getObjectField(positioner, "mPositionRect");
		return position.top + getIntField(positioner, "mExpandedViewPadding");
	}

	/** Maximum height the expanded view (task view) may use so it stays above the bubbles row. */
	private static int expandedMaxHeight(Object positioner, boolean overflow) {
		int padding = getIntField(positioner, "mExpandedViewPadding");
		int reserved = overflow ? padding : getIntField(positioner, "mManageButtonHeightIncludingMargins");
		float available = (bottomRowY(positioner) - padding) - expandedTopY(positioner)
				- reserved - getIntField(positioner, "mPointerMargin");
		return Math.max(0, (int) available);
	}

	// ---------------------------------------------------------------- expanded view helpers

	private static boolean isReversedForView(ViewGroup expandedView) {
		try {
			Object positioner = getObjectField(expandedView, "mPositioner");
			if (positioner != null) return isReversed(positioner);
		} catch (Throwable ignored) {
		}
		if (!enableReverseBubble) return false;
		// Positioner not attached yet: fall back to phone + portrait
		Configuration config = expandedView.getResources().getConfiguration();
		return config.orientation == Configuration.ORIENTATION_PORTRAIT && config.smallestScreenWidthDp < 600;
	}

	private static void applyExpandedViewLayout(ViewGroup expandedView) {
		if (!enableReverseBubble) return;
		try {
			View manage = (View) getObjectField(expandedView, "mManageButton");
			boolean reversed = isReversedForView(expandedView);

			if (manage != null && manage.getParent() == expandedView) {
				int wanted = reversed ? 0 : expandedView.getChildCount() - 1;
				if (expandedView.indexOfChild(manage) != wanted) {
					expandedView.removeView(manage);
					expandedView.addView(manage, reversed ? 0 : -1);
				}
			}

			if (reversed) {
				View pointer = (View) getObjectField(expandedView, "mPointerView");
				if (pointer != null) pointer.setVisibility(View.GONE);
			}
		} catch (Throwable t) {
			Logger.log("BubblesMods: applyExpandedViewLayout failed: " + t);
		}
	}

	// ---------------------------------------------------------------- selected icon scale

	private static void applySelectedScale(ViewGroup stack) {
		if (!enableReverseBubble) return;
		try {
			ViewGroup container = (ViewGroup) getObjectField(stack, "mBubbleContainer");
			boolean expanded = getBooleanField(stack, "mIsExpanded");
			Object selected = getObjectField(stack, "mExpandedBubble");
			String selectedKey = (expanded && selected != null)
					? (String) callMethod(selected, "getKey") : null;
			Object dragged = null;
			try {
				Object magnetized = getObjectField(stack, "mMagnetizedObject");
				if (magnetized != null) dragged = getObjectField(magnetized, "underlyingObject");
			} catch (Throwable ignored) {
			}

			for (int i = 0; i < container.getChildCount(); i++) {
				View icon = container.getChildAt(i);
				if (icon == dragged) continue; // don't fight the drag-to-dismiss animation
				Object bubble = getObjectField(icon, "mBubble");
				String key = bubble != null ? (String) callMethod(bubble, "getKey") : null;
				float target = (selectedKey != null && selectedKey.equals(key)) ? SELECTED_SCALE : 1f;
				if (Math.abs(icon.getScaleX() - target) > 0.01f) {
					icon.animate().scaleX(target).scaleY(target)
							.setDuration(SCALE_ANIM_DURATION_MS).start();
				}
			}
		} catch (Throwable t) {
			Logger.log("BubblesMods: applySelectedScale failed: " + t);
		}
	}

	private static void scheduleSelectedScale(ViewGroup stack) {
		if (!enableReverseBubble) return;
		for (long delay : RECHECK_DELAYS_MS) {
			if (delay == 0L) applySelectedScale(stack);
			else stack.postDelayed(() -> applySelectedScale(stack), delay);
		}
	}

	// ---------------------------------------------------------------- hooks

	private void applyReverseBubbleLayoutHooks() {
		ReflectedClass positionerClass = ReflectedClass.ofIfPossible(POSITIONER);
		ReflectedClass expandedViewClass = ReflectedClass.ofIfPossible(EXPANDED_VIEW);
		if (positionerClass.getClazz() == null) return;

		// Bubbles row -> bottom of the screen
		positionerClass.after("getExpandedBubbleXY").run(param -> {
			try {
				Object positioner = param.thisObject;
				PointF point = param.getResult();
				if (point != null && isReversed(positioner)) {
					point.y = bottomRowY(positioner);
				}
			} catch (Throwable t) {
				Logger.log("BubblesMods: getExpandedBubbleXY hook failed: " + t);
			}
		});

		// Expanded view -> starts at the top
		positionerClass.after("getExpandedViewYTopAligned").run(param -> {
			try {
				if (isReversed(param.thisObject)) param.setResult(expandedTopY(param.thisObject));
			} catch (Throwable t) {
				Logger.log("BubblesMods: getExpandedViewYTopAligned hook failed: " + t);
			}
		});

		positionerClass.after("getExpandedViewY").run(param -> {
			try {
				Object positioner = param.thisObject;
				if (!isReversed(positioner)) return;
				Object provider = param.args[0];
				boolean overflow = provider == null
						|| "Overflow".equals(callMethod(provider, "getKey"));
				if (overflow) {
					// "Recent bubbles" window: stick it to the icons row so the thumb reaches it
					float height = (Float) callMethod(positioner, "getExpandedViewHeight", provider);
					int max = expandedMaxHeight(positioner, true);
					float used = height == -1f ? max : Math.min(height, max);
					float y = bottomRowY(positioner) - getIntField(positioner, "mExpandedViewPadding")
							- getIntField(positioner, "mPointerMargin") - used;
					param.setResult(Math.max(y, expandedTopY(positioner)));
				} else {
					param.setResult((float) expandedTopY(positioner));
				}
			} catch (Throwable t) {
				Logger.log("BubblesMods: getExpandedViewY hook failed: " + t);
			}
		});

		// ...and ends right above the bubbles row
		positionerClass.after("getMaxExpandedViewHeight").run(param -> {
			try {
				if (isReversed(param.thisObject)) {
					param.setResult(expandedMaxHeight(param.thisObject, (Boolean) param.args[0]));
				}
			} catch (Throwable t) {
				Logger.log("BubblesMods: getMaxExpandedViewHeight hook failed: " + t);
			}
		});

		positionerClass.after("getTaskViewRestBounds").run(param -> {
			try {
				Object positioner = param.thisObject;
				if (isReversed(positioner)) {
					Rect rect = (Rect) param.args[0];
					int top = expandedTopY(positioner);
					rect.set(rect.left, top, rect.right, top + expandedMaxHeight(positioner, false));
				}
			} catch (Throwable t) {
				Logger.log("BubblesMods: getTaskViewRestBounds hook failed: " + t);
			}
		});

		// Initial bubble position -> bottom right
		positionerClass.after("getStartPosition").run(param -> {
			try {
				Object positioner = param.thisObject;
				PointF point = param.getResult();
				if (point != null && isReversed(positioner)) {
					RectF allowable = (RectF) callMethod(positioner, "getAllowableStackPositionRegion", 1);
					point.x = allowable.right;
					point.y = allowable.bottom;
				}
			} catch (Throwable t) {
				Logger.log("BubblesMods: getStartPosition hook failed: " + t);
			}
		});

		if (expandedViewClass.getClazz() != null) {
			// Manage button -> top of the expanded view, pointer hidden
			expandedViewClass.after("onFinishInflate").run(param -> applyExpandedViewLayout(param.getThisObject()));
			expandedViewClass.after("initialize").run(param -> applyExpandedViewLayout(param.getThisObject()));
			expandedViewClass.after("updateDimensions").run(param -> applyExpandedViewLayout(param.getThisObject()));
			expandedViewClass.after("updateManageButtonIfExists").run(param -> applyExpandedViewLayout(param.getThisObject()));
			expandedViewClass.after("updatePointerViewIfExists").run(param -> applyExpandedViewLayout(param.getThisObject()));
			//R8-generated name; silently unhooks when the build renames it
			expandedViewClass.after("lambda$setPointerPosition$0").run(param -> applyExpandedViewLayout(param.getThisObject()));
		}

		// Selected bubble icon -> bigger than the rest
		ReflectedClass stackViewClass = ReflectedClass.ofIfPossible(STACK_VIEW);
		if (stackViewClass.getClazz() != null) {
			for (String method : new String[]{"setSelectedBubble", "setExpanded", "updateExpandedView",
					"updateBubbleOrderInternal", "updateBubbleShadows"}) {
				stackViewClass.after(method).run(param -> scheduleSelectedScale(param.getThisObject()));
			}
		}
	}

	private void applyDismissPositionHooks() {
		ReflectedClass dismissViewClass = ReflectedClass.ofIfPossible(DISMISS_VIEW);
		if (dismissViewClass.getClazz() == null) {
			return;
		}

		dismissViewClass.after("setup").run(param -> {
			if (!enableReverseBubble) return;
			try {
				FrameLayout dismissView = (FrameLayout) param.thisObject;
				applyDismissLayout(dismissView, getIntField(param.args[0], "floatingGradientHeightResId"));
			} catch (Throwable t) {
				Logger.log("BubblesMods: dismiss setup hook failed: " + t);
			}
		});

		dismissViewClass.before("updatePadding").run(param -> {
			if (!enableReverseBubble) return;
			if (!"BOTTOM_MIDDLE".equals(dismissPosition)) {
				FrameLayout dismissView = (FrameLayout) param.thisObject;
				dismissView.setPadding(0, 0, 0, 0); // Clear padding so it doesn't offset our position
				param.setResult(null); // skip original
			}
		});

		dismissViewClass.after("updateResources").run(param -> {
			if (!enableReverseBubble) return;
			try {
				FrameLayout dismissView = (FrameLayout) param.thisObject;
				Object config = getObjectField(dismissView, "config");
				if (config == null) return;

				int gradientHeight = dismissView.getResources().getDimensionPixelSize(
						getIntField(config, "floatingGradientHeightResId"));

				FrameLayout.LayoutParams dvParams = (FrameLayout.LayoutParams) dismissView.getLayoutParams();
				switch (dismissPosition) {
					case "LEFT_MIDDLE":
					case "RIGHT_MIDDLE":
						dvParams.width = gradientHeight;
						dvParams.height = FrameLayout.LayoutParams.MATCH_PARENT;
						break;
					case "TOP_MIDDLE":
						dvParams.width = FrameLayout.LayoutParams.MATCH_PARENT;
						dvParams.height = gradientHeight;
						break;
					case "CENTER":
						dvParams.width = FrameLayout.LayoutParams.MATCH_PARENT;
						dvParams.height = FrameLayout.LayoutParams.MATCH_PARENT;
						break;
					default:
						return;
				}
				dismissView.setLayoutParams(dvParams);
			} catch (Throwable t) {
				Logger.log("BubblesMods: dismiss updateResources hook failed: " + t);
			}
		});
	}

	private static void applyDismissLayout(FrameLayout dismissView, int floatingGradientHeightResId) {
		View circle = (View) getObjectField(dismissView, "circle");
		GradientDrawable gradient = (GradientDrawable) getObjectField(dismissView, "gradientDrawable");

		FrameLayout.LayoutParams dvParams = (FrameLayout.LayoutParams) dismissView.getLayoutParams();
		FrameLayout.LayoutParams circleParams = (FrameLayout.LayoutParams) circle.getLayoutParams();

		int gradientHeight = dismissView.getResources().getDimensionPixelSize(floatingGradientHeightResId);

		switch (dismissPosition) {
			case "TOP_MIDDLE":
				dvParams.width = FrameLayout.LayoutParams.MATCH_PARENT;
				dvParams.height = gradientHeight;
				dvParams.gravity = Gravity.TOP;

				circleParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
				if (gradient != null) gradient.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
				break;

			case "LEFT_MIDDLE":
				dvParams.width = gradientHeight;
				dvParams.height = FrameLayout.LayoutParams.MATCH_PARENT;
				dvParams.gravity = Gravity.LEFT;

				circleParams.gravity = Gravity.LEFT | Gravity.CENTER_VERTICAL;
				if (gradient != null) gradient.setOrientation(GradientDrawable.Orientation.LEFT_RIGHT);
				break;

			case "RIGHT_MIDDLE":
				dvParams.width = gradientHeight;
				dvParams.height = FrameLayout.LayoutParams.MATCH_PARENT;
				dvParams.gravity = Gravity.RIGHT;

				circleParams.gravity = Gravity.RIGHT | Gravity.CENTER_VERTICAL;
				if (gradient != null) gradient.setOrientation(GradientDrawable.Orientation.RIGHT_LEFT);
				break;

			case "CENTER":
				dvParams.width = FrameLayout.LayoutParams.MATCH_PARENT;
				dvParams.height = FrameLayout.LayoutParams.MATCH_PARENT;
				dvParams.gravity = Gravity.CENTER;

				circleParams.gravity = Gravity.CENTER;
				if (gradient != null) dismissView.setBackground(null);
				break;

			case "BOTTOM_MIDDLE":
			default:
				dvParams.width = FrameLayout.LayoutParams.MATCH_PARENT;
				dvParams.height = gradientHeight;
				dvParams.gravity = Gravity.BOTTOM;

				circleParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
				if (gradient != null) gradient.setOrientation(GradientDrawable.Orientation.BOTTOM_TOP);
				break;
		}

		dismissView.setLayoutParams(dvParams);
		circle.setLayoutParams(circleParams);

		// Remove translation applied by default
		if (!"BOTTOM_MIDDLE".equals(dismissPosition)) {
			circle.setTranslationY(0f);
			circle.setTranslationX(0f);
		}
	}
}
