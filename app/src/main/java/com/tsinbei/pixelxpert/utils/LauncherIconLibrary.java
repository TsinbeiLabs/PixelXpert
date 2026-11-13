/* Adapted from NativeComplements IconArchive (Apache-2.0).
 * Changes: normalize imported layers, publish immutable libraries through
 * PixelXpert preferences, and expose only generated filenames to the launcher. */
package com.tsinbei.pixelxpert.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import java.io.*;
import java.util.*;
import java.util.zip.*;

public final class LauncherIconLibrary implements AutoCloseable {
	private final File staging;
	private ZipFile archive;
	private final Map<String, Map<String, String[]>> sources = new TreeMap<>();
	public static final String GENERATION_KEY = "LauncherIconLibrary";
	public static final String RESTORE_KEY = "LauncherIconRestorePackages";
	public static File directory(Context context) {
		return new File(context.createDeviceProtectedStorageContext().getFilesDir(), "launcher-icons");
	}
	public static void clear(Context context) {
		PXPreferences.getPrefs().edit().remove(GENERATION_KEY).putString(AospPerfectIconPack.SOURCE_KEY, AospPerfectIconPack.IMPORTED).putBoolean("LauncherIconOverridesEnabled", false).apply();
		File[] libraries = directory(context).listFiles();
		if (libraries != null) for (File library : libraries) if (validGeneration(library.getName())) delete(library);
	}
	public LauncherIconLibrary(Context context, Uri uri) throws IOException {
		staging = new File(context.getCacheDir(), "icons-" + UUID.randomUUID());
		if (!staging.mkdirs()) throw new IOException("Cannot create import workspace");
		try {
			File file = new File(staging, "source.zip");
			try (InputStream input = context.getContentResolver().openInputStream(uri); OutputStream output = new FileOutputStream(file)) {
				if (input == null) throw new IOException("Cannot read archive");
				copy(input, output, 512L * 1024 * 1024);
			}
			archive = new ZipFile(file);
			scan();
			if (sources.isEmpty()) {
				ZipEntry inner = null;
				for (ZipEntry entry : Collections.list(archive.entries())) {
					if (!entry.isDirectory() && (entry.getName().equals("icons") || entry.getName().endsWith("/icons"))) {
						if (inner != null) throw new IOException("Select one inner icon archive");
						inner = entry;
					}
				}
				if (inner != null) {
					File nested = new File(staging, "inner.zip");
					try (InputStream input = archive.getInputStream(inner); OutputStream output = new FileOutputStream(nested)) {
						copy(input, output, 512L * 1024 * 1024);
					}
					archive.close(); archive = new ZipFile(nested); scan();
				}
			}
			if (sources.isEmpty()) throw new IOException("No package/0.png + package/1.png icon pairs found");
		} catch (IOException | RuntimeException failure) { close(); throw failure; }
	}
	public List<String> sources() { return new ArrayList<>(sources.keySet()); }
	private void scan() throws IOException {
		Enumeration<? extends ZipEntry> entries = archive.entries();
		int count = 0;
		while (entries.hasMoreElements()) {
			ZipEntry entry = entries.nextElement();
			if (++count > 60000) throw new IOException("Too many archive entries");
			String path = entry.getName();
			if (path.length() > 1024 || path.startsWith("/") || path.contains("\\") || path.contains(":")) throw new IOException("Invalid archive path");
			for (String segment : path.split("/")) if (segment.equals("..") || segment.equals(".") || segment.isEmpty()) throw new IOException("Invalid archive path");
			if (entry.isDirectory() || path.contains("fancy_icons/") || path.contains("layer_animating_icons/")) continue;
			int layer = path.endsWith("/0.png") ? 0 : path.endsWith("/1.png") ? 1 : -1;
			if (layer < 0) continue;
			String parent = path.substring(0, path.length() - 6);
			int slash = parent.lastIndexOf('/');
			String pkg = parent.substring(slash + 1);
			if (!validPackage(pkg)) continue;
			String root = parent.substring(0, slash + 1);
			String[] pair = sources.computeIfAbsent(root, key -> new TreeMap<>()).computeIfAbsent(pkg, key -> new String[2]);
			if (pair[layer] != null) throw new IOException("Duplicate layer for " + pkg);
			pair[layer] = path;
		}
		sources.values().forEach(pairs -> pairs.values().removeIf(pair -> pair[0] == null || pair[1] == null));
		sources.values().removeIf(Map::isEmpty);
	}
	public int install(Context context, String root) throws IOException {
		Map<String, String[]> pairs = sources.get(root);
		if (pairs == null) throw new IOException("Unknown icon source");
		if (pairs.size() > 4000) throw new IOException("Icon source exceeds 4000 icons");
		if (pairs.keySet().stream().mapToInt(String::length).sum() > 100000) throw new IOException("Icon catalog is too large");
		File libraries = directory(context);
		if (!libraries.isDirectory() && !libraries.mkdirs()) throw new IOException("Cannot create icon storage");
		String generation = UUID.randomUUID().toString();
		File output = new File(libraries, generation);
		if (!output.mkdir()) throw new IOException("Cannot create icon library");
		try {
			long written = 0;
			for (Map.Entry<String, String[]> entry : pairs.entrySet()) {
				if (Thread.currentThread().isInterrupted()) throw new IOException("Import canceled");
				for (int layer = 0; layer < 2; layer++) {
					ZipEntry image = archive.getEntry(entry.getValue()[layer]);
					ByteArrayOutputStream bytes = new ByteArrayOutputStream();
					try (InputStream input = archive.getInputStream(image)) { copy(input, bytes, 8L * 1024 * 1024); }
					byte[] png = bytes.toByteArray();
					BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
					BitmapFactory.decodeByteArray(png, 0, png.length, options);
					if (options.outWidth <= 0 || options.outHeight <= 0 || options.outWidth > 4096 || options.outHeight > 4096) throw new IOException("Invalid icon: " + entry.getKey());
					options.inJustDecodeBounds = false;
					while (Math.max(options.outWidth, options.outHeight) / Math.max(1, options.inSampleSize) > 256) options.inSampleSize = Math.max(1, options.inSampleSize) * 2;
					Bitmap bitmap = BitmapFactory.decodeByteArray(png, 0, png.length, options);
					if (bitmap == null) throw new IOException("Cannot decode icon: " + entry.getKey());
					Bitmap normalized = Bitmap.createScaledBitmap(bitmap, 128, 128, true);
					try (OutputStream file = new FileOutputStream(new File(output, entry.getKey() + "." + layer + ".png"))) {
						if (!normalized.compress(Bitmap.CompressFormat.PNG, 100, file)) throw new IOException("Cannot save icon");
					} finally { if (bitmap != normalized) bitmap.recycle(); normalized.recycle(); }
					written += new File(output, entry.getKey() + "." + layer + ".png").length();
					if (written > 128L * 1024 * 1024) throw new IOException("Icon library exceeds 128 MiB");
				}
			}
			if (Thread.currentThread().isInterrupted()) throw new IOException("Import canceled");
			Set<String> restore = new HashSet<>(PXPreferences.getPrefs().getStringSet(RESTORE_KEY, Set.of()));
			restore.addAll(pairs.keySet());
			if (!PXPreferences.getPrefs().edit().putStringSet(RESTORE_KEY, restore).putString(GENERATION_KEY, generation).putString(AospPerfectIconPack.SOURCE_KEY, AospPerfectIconPack.IMPORTED).commit()) throw new IOException("Cannot publish icon library");
			File[] previous = libraries.listFiles();
			if (previous != null) for (File library : previous) {
				if (!library.equals(output) && validGeneration(library.getName())) delete(library);
			}
			return pairs.size();
		} catch (IOException | RuntimeException failure) { delete(output); throw failure; }
	}
	public static boolean validPackage(String name) { return name != null && name.length() <= 240 && name.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+"); }
	public static boolean validGeneration(String name) {
		try { return UUID.fromString(name).toString().equals(name); } catch (IllegalArgumentException | NullPointerException failure) { return false; }
	}
	private static void copy(InputStream input, OutputStream output, long limit) throws IOException {
		byte[] buffer = new byte[32768]; long total = 0; int count;
		while ((count = input.read(buffer)) != -1) {
			if (Thread.currentThread().isInterrupted()) throw new IOException("Import canceled");
			if ((total += count) > limit) throw new IOException("Archive/image too large");
			output.write(buffer, 0, count);
		}
	}
	private static void delete(File file) {
		File[] children = file.listFiles();
		if (children != null) for (File child : children) delete(child);
		file.delete();
	}
	@Override public void close() {
		try { if (archive != null) archive.close(); } catch (IOException ignored) { }
		delete(staging);
	}
}
