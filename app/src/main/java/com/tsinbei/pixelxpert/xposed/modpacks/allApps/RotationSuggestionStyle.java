/* Native rotation presentation inspired by NativeComplements (Apache-2.0).
 * Unlike its cross-process replacement, keep the existing native button and
 * its click/cancellation/timeout lifecycle in each owning process. */
package com.tsinbei.pixelxpert.xposed.modpacks.allApps;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import java.util.Map;
import java.util.WeakHashMap;
import com.tsinbei.pixelxpert.R;
import com.tsinbei.pixelxpert.xposed.XPLauncher;
import com.tsinbei.pixelxpert.xposed.XposedModPack;
import com.tsinbei.pixelxpert.xposed.annotations.LauncherModPack;
import com.tsinbei.pixelxpert.xposed.annotations.SystemUIModPack;
import com.tsinbei.pixelxpert.xposed.utils.reflection.ReflectedClass;
import io.github.libxposed.api.XposedModuleInterface;
import static com.tsinbei.pixelxpert.xposed.XPrefs.Xprefs;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.callMethod;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.getObjectField;

@LauncherModPack
@SystemUIModPack
public class RotationSuggestionStyle extends XposedModPack {
	private final Handler main = new Handler(Looper.getMainLooper());
	private final Map<View, Original> buttons = new WeakHashMap<>();
	private boolean simpleIcon;
	private float scale = 1f;
	private static final class Original {
		final float scaleX, scaleY;
		Drawable icon, replacement;
		Original(View view) {
			scaleX = view.getScaleX(); scaleY = view.getScaleY();
			icon = view instanceof ImageView image ? image.getDrawable() : null;
		}
	}
	public RotationSuggestionStyle(Context context) { super(context); }

	@Override public void onPreferenceUpdated(String... keys) {
		simpleIcon = Xprefs.getBoolean("RotationSuggestionSimpleIcon", false);
		scale = Xprefs.getSliderInt("RotationSuggestionScale", 100) / 100f;
		main.post(() -> buttons.forEach(this::apply));
	}

	@Override public void onPackageLoaded(XposedModuleInterface.PackageReadyParam param) {
		ReflectedClass controller = ReflectedClass.ofIfPossible("com.android.systemui.shared.rotation.RotationButtonController");
		controller.after("setRotateSuggestionButtonState").run(hook -> {
			if (Looper.myLooper() != Looper.getMainLooper()) return;
			Object button = getObjectField(hook.thisObject, "mRotationButton");
			if (button == null) return;
			View view = callMethod(button, "getCurrentView");
			if (view == null) return;
			Original original = buttons.get(view);
			if (original == null) {
				original = new Original(view);
				buttons.put(view, original);
			}
			// Native proposals may update the drawable for a new orientation.
			// Never restore the old proposal's animation or our own replacement.
			if (view instanceof ImageView image && image.getDrawable() != original.replacement) original.icon = image.getDrawable();
			apply(view, original);
		});
	}

	private void apply(View view, Original original) {
		view.setScaleX(original.scaleX * scale);
		view.setScaleY(original.scaleY * scale);
		if (view instanceof ImageView image) {
			if (simpleIcon) {
				if (original.replacement == null) original.replacement = XPLauncher.moduleResources.getDrawable(R.drawable.ic_rotation_suggestion, null);
				image.setImageDrawable(original.replacement);
			}
			else image.setImageDrawable(original.icon);
		}
	}
}
