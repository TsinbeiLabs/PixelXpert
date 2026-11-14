/*
 * Adapted from PixelLauncherEnhanced (GPL-3.0), ported to PixelXpert
 */
package com.tsinbei.pixelxpert.xposed.modpacks.launcher;

import android.content.Context;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;

import com.tsinbei.pixelxpert.xposed.modpacks.launcher.FreeformHintView.HintPhase;

public class FreeformHintViewController {

	private static FreeformHintView sHintView;

	/**
	 * @param dragLayer DragLayer instance owning the hint view
	 * @param context   Launcher context
	 */
	public static void init(ViewGroup dragLayer, Context context) {
		if (sHintView != null) {
			ViewParent parent = sHintView.getParent();
			if (parent == dragLayer) return;
			// DragLayer was recreated: detach the stale view and build a fresh one
			if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(sHintView);
			sHintView = null;
		}

		sHintView = new FreeformHintView(context);
		sHintView.setVisibility(ViewGroup.VISIBLE);

		// Create correct LayoutParams BEFORE addView to avoid InsettableFrameLayout crash
		ViewGroup.LayoutParams lp = createLayoutParams(dragLayer);
		sHintView.setLayoutParams(lp);

		dragLayer.addView(sHintView);
		sHintView.initLayout();
	}

	private static ViewGroup.LayoutParams createLayoutParams(ViewGroup parent) {
		try {
			Class<?> lpClass = parent.getClass().getClassLoader().loadClass(
					"com.android.launcher3.InsettableFrameLayout$LayoutParams");
			ViewGroup.LayoutParams lp = (ViewGroup.LayoutParams)
					lpClass.getConstructor(int.class, int.class)
							.newInstance(ViewGroup.LayoutParams.WRAP_CONTENT,
									ViewGroup.LayoutParams.WRAP_CONTENT);
			if (lp instanceof FrameLayout.LayoutParams) {
				((FrameLayout.LayoutParams) lp).gravity =
						Gravity.TOP | Gravity.START;
			}
			return lp;
		} catch (Exception e) {
			FrameLayout.LayoutParams lp =
					new FrameLayout.LayoutParams(
							ViewGroup.LayoutParams.WRAP_CONTENT,
							ViewGroup.LayoutParams.WRAP_CONTENT);
			lp.gravity = Gravity.TOP | Gravity.START;
			return lp;
		}
	}

	public static FreeformHintView getHintView() {
		return sHintView;
	}

	public static void reset() {
		if (sHintView != null) {
			sHintView.setPhase(HintPhase.HIDDEN);
		}
	}
}
