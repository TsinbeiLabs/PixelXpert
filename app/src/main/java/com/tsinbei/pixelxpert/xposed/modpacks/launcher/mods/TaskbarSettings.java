package com.tsinbei.pixelxpert.xposed.modpacks.launcher.mods;

import static com.tsinbei.pixelxpert.xposed.XPrefs.Xprefs;

/**
 * Centralized state of the taskbar-related preferences, consumed by the launcher sub-mods.
 * Structure adapted from Pixel Taskbar Enabler by beyman-dev (GPLv3).
 */
public class TaskbarSettings {

	public static final int TASKBAR_DEFAULT = 0;
	public static final int TASKBAR_ON = 1;
	public static final int TASKBAR_OFF = 2;

	public int taskbarMode = TASKBAR_DEFAULT;
	public boolean mobileRecents = false;
	public float taskbarScale = 1.0f;
	public float gridHeaderScale = 1.0f;
	public float mobileHeaderScale = 1.0f;
	public int taskbarIconCount = 4;

	public TaskbarSettings() {
		update();
	}

	public void update(String... key) {
		if (Xprefs == null) return;
		taskbarMode = Integer.parseInt(Xprefs.getString("taskBarMode", String.valueOf(TASKBAR_DEFAULT)));
		mobileRecents = Xprefs.getBoolean("mobile_recents", false);
		taskbarScale = Xprefs.getInt("taskbar_scale", 100) / 100f;
		gridHeaderScale = Xprefs.getInt("grid_header_scale", 100) / 100f;
		mobileHeaderScale = Xprefs.getInt("mobile_header_scale", 100) / 100f;
		taskbarIconCount = Math.min(Xprefs.getInt("taskbar_icon_count", 4), 5);
	}
}
