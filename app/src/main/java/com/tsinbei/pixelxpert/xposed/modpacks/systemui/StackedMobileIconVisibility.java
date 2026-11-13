/* Adapted from NativeComplements SignalDisplay.installStacked (Apache-2.0).
 * Changes: apply existing PixelXpert visibility rules to the native stacked
 * presentation; preserve DualSim subscription IDs and signal models unchanged. */
package com.tsinbei.pixelxpert.xposed.modpacks.systemui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.lang.reflect.Method;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import com.tsinbei.pixelxpert.xposed.XposedModPack;
import com.tsinbei.pixelxpert.xposed.annotations.SystemUIModPack;
import com.tsinbei.pixelxpert.xposed.utils.reflection.ReflectedClass;
import io.github.libxposed.api.XposedModuleInterface;
import static com.tsinbei.pixelxpert.xposed.XPrefs.Xprefs;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.getBooleanField;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.getObjectField;

@SystemUIModPack
public class StackedMobileIconVisibility extends XposedModPack {
	private final Handler main = new Handler(Looper.getMainLooper());
	private volatile boolean combined, hidden, wifiVisible;
	private Object revision;
	private Method read, write;
	private record ModifierCopy(WeakReference<Object> adjusted, Object original) { }
	private final List<ModifierCopy> copies = new ArrayList<>();
	public StackedMobileIconVisibility(Context context) { super(context); }
	@Override public void onPreferenceUpdated(String... keys) {
		combined = Xprefs.getBoolean("combinedSignalEnabled", false);
		Set<String> ignored = Xprefs.getStringSet("SBIgnoredIcons", Set.of());
		hidden = ignored.contains("mobile");
		refresh();
	}

	@Override public void onPackageLoaded(XposedModuleInterface.PackageReadyParam param) {
		try {
			String prefix = "com.android.systemui.statusbar.pipeline.";
			Class<?> dual = ReflectedClass.of(prefix + "mobile.ui.model.DualSim").getClazz();
			Class<?> composer = ReflectedClass.of("androidx.compose.runtime.Composer").getClazz();
			Class<?> modifier = ReflectedClass.of("androidx.compose.ui.Modifier").getClazz();
			Class<?> draw = ReflectedClass.of(prefix + "shared.ui.composable.StackedMobileIconKt").getClazz();
			Class<?> state = ReflectedClass.of("androidx.compose.runtime.ParcelableSnapshotMutableIntState").getClazz();
			Object signalRevision = state.getDeclaredConstructor(int.class).newInstance(0);
			Method stateRead = state.getDeclaredMethod("getIntValue"), stateWrite = state.getDeclaredMethod("setIntValue", int.class);
			Method width = ReflectedClass.of("androidx.compose.foundation.layout.SizeKt").getClazz()
					.getDeclaredMethod("width-3ABfNKs", modifier, float.class);
			Method alpha = ReflectedClass.of("androidx.compose.ui.draw.AlphaKt").getClazz()
					.getDeclaredMethod("alpha", modifier, float.class);
			Method render = draw.getDeclaredMethod("StackedMobileIcon-sW7UJKQ", dual, long.class, String.class, modifier, composer, int.class);
			Class<?> viewModel = ReflectedClass.of(prefix + "mobile.ui.viewmodel.StackedMobileIconViewModel").getClazz();
			Method parent = draw.getDeclaredMethod("StackedMobileIcon", viewModel, modifier, composer, int.class);
			Method restart = ReflectedClass.of(draw.getName() + "$$ExternalSyntheticLambda6").getClazz()
					.getDeclaredMethod("invoke", Object.class, Object.class);
			if (!ReflectedClass.deoptimize(parent) || !ReflectedClass.deoptimize(restart)) throw new IllegalStateException("stacked callers");
			ReflectedClass.getDefaultXposedInterface().hook(render).intercept(chain -> {
				Object[] args = chain.getArgs().toArray();
				stateRead.invoke(signalRevision); // Observe module-owned state; never write native flows.
				// Compose restart callbacks can retain the modified argument. Like
				// NativeComplements' presentation copies, resolve its original by
				// identity so disabling the setting restores width and alpha.
				copies.removeIf(copy -> copy.adjusted.get() == null);
				Object original = args[3];
				for (ModifierCopy copy : copies) {
					if (copy.adjusted.get() == original) { original = copy.original; break; }
				}
				args[3] = original;
				if (hidden || (combined && wifiVisible)) {
					Object adjusted = alpha.invoke(null, width.invoke(null, original, 0f), 0f);
					copies.add(new ModifierCopy(new WeakReference<>(adjusted), original));
					args[3] = adjusted;
				}
				// Force only the modifier's change bits; the original DualSim is untouched.
				args[5] = ((Integer) args[5] & ~(7 << 9)) | (2 << 9);
				return chain.proceed(args);
			});
			revision = signalRevision; read = stateRead; write = stateWrite;
			ReflectedClass.of("com.android.systemui.statusbar.connectivity.CallbackHandler")
					.after("setWifiIndicators").run(hook -> {
						boolean visible = getBooleanField(getObjectField(hook.args[0], "statusIcon"), "visible");
						if (wifiVisible != visible) { wifiVisible = visible; refresh(); }
					});
			refresh();
		} catch (Throwable failure) { log("StackedMobileIconVisibility: target unavailable", failure); }
	}

	private void refresh() {
		main.post(() -> {
			if (revision == null) return;
			try { write.invoke(revision, (Integer) read.invoke(revision) + 1); }
			catch (ReflectiveOperationException failure) { log("StackedMobileIconVisibility: refresh failed", failure); }
		});
	}
}
