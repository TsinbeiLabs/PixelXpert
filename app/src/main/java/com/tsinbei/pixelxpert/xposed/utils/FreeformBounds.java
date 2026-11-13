package com.tsinbei.pixelxpert.xposed.utils;

import android.content.Context;
import android.graphics.Rect;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;

/** Fit AOSP freeform windows to the current display's usable bounds.
 * Inspired by NativeComplements Geometry (Apache-2.0), with native insets. */
public final class FreeformBounds {
	private FreeformBounds() { }
	public static Rect forDisplay(Context context) {
		WindowMetrics metrics = context.getSystemService(WindowManager.class).getCurrentWindowMetrics();
		Rect usable = new Rect(metrics.getBounds());
		android.graphics.Insets insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(
				WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
		usable.inset(insets.left, insets.top, insets.right, insets.bottom);
		int margin = Math.round(16 * context.getResources().getDisplayMetrics().density);
		usable.inset(margin, margin);
		boolean landscape = usable.width() > usable.height();
		int height = Math.round(usable.height() * (landscape ? 0.90f : 0.65f));
		int width = Math.min(Math.round(usable.width() * 0.90f), Math.round(height * 9f / 16));
		int left = usable.centerX() - width / 2;
		int top = usable.centerY() - height / 2;
		return new Rect(left, top, left + width, top + height);
	}
}
