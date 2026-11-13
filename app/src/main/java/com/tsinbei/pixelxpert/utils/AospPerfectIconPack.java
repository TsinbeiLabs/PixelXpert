package com.tsinbei.pixelxpert.utils;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Reads AOSP Perfect Icons' generated index and adaptive resources from its APK. */
public final class AospPerfectIconPack {
	public static final String PACKAGE = "com.tsinbei.aospperfecticons";
	public static final String SOURCE_KEY = "LauncherIconSource";
	public static final String IMPORTED = "imported";
	public record Catalog(Map<ComponentName, Drawable.ConstantState> components,
			Map<String, Drawable.ConstantState> packages) {
		public Set<String> affectedPackages() {
			Set<String> result = new HashSet<>(packages.keySet());
			for (ComponentName component : components.keySet()) result.add(component.getPackageName());
			return result;
		}
	}
	public static Catalog load(Context context) throws Exception {
		Context pack = context.createPackageContext(PACKAGE, 0);
		JSONArray index;
		try (InputStream stream = pack.getAssets().open("icon_pack_index.json")) {
			byte[] bytes = stream.readNBytes(2 * 1024 * 1024 + 1);
			if (bytes.length > 2 * 1024 * 1024) throw new IllegalArgumentException("Icon index exceeds 2 MiB");
			index = new JSONArray(new String(bytes, StandardCharsets.UTF_8));
		}
		Map<ComponentName, Drawable.ConstantState> components = new HashMap<>();
		Map<String, Drawable.ConstantState> packages = new HashMap<>();
		if (index.length() == 0 || index.length() > 4000) throw new IllegalArgumentException("Invalid icon entry count");
		for (int i = 0; i < index.length(); i++) {
			JSONObject entry = index.getJSONObject(i);
			String drawable = entry.getString("launcherDrawable");
			int id = pack.getResources().getIdentifier(drawable, "drawable", PACKAGE);
			if (id == 0) throw new IllegalArgumentException("Missing adaptive icon: " + drawable);
			Drawable icon = pack.getDrawable(id).mutate();
			// Preserve the pack's color layers. A themed mask must come from the
			// transparent foreground, never from the opaque complete icon.
			if (icon instanceof AdaptiveIconDrawable adaptive && adaptive.getMonochrome() == null) {
				Drawable foreground = adaptive.getForeground().getConstantState().newDrawable(pack.getResources()).mutate();
				Bitmap mask = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
				foreground.setBounds(0, 0, 128, 128);
				foreground.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
				foreground.draw(new Canvas(mask));
				icon = new AdaptiveIconDrawable(adaptive.getBackground(), adaptive.getForeground(), new BitmapDrawable(pack.getResources(), mask));
			}
			Drawable.ConstantState state = icon.getConstantState();
			if (state == null) throw new IllegalArgumentException("Icon cannot be copied: " + drawable);
			JSONArray mappings = entry.getJSONArray("components");
			for (int j = 0; j < mappings.length(); j++) {
				String mapping = mappings.getString(j);
				if (mapping.startsWith("ComponentInfo{") && mapping.endsWith("}")) {
					ComponentName component = ComponentName.unflattenFromString(mapping.substring(14, mapping.length() - 1));
					if (component == null) throw new IllegalArgumentException("Invalid component: " + mapping);
					if (components.putIfAbsent(component, state) != null) throw new IllegalArgumentException("Duplicate component: " + mapping);
				} else if (mapping.startsWith("PackageInfo{") && mapping.endsWith("}")) {
					String pkg = mapping.substring(12, mapping.length() - 1);
					if (!LauncherIconLibrary.validPackage(pkg)) throw new IllegalArgumentException("Invalid package: " + pkg);
					if (packages.putIfAbsent(pkg, state) != null) throw new IllegalArgumentException("Duplicate package: " + pkg);
				} else throw new IllegalArgumentException("Invalid icon mapping: " + mapping);
			}
		}
		return new Catalog(Map.copyOf(components), Map.copyOf(packages));
	}
}
