package com.tsinbei.pixelxpert.xposed.modpacks.launcher.mods;

import android.content.Context;

import com.tsinbei.pixelxpert.xposed.modpacks.launcher.TaskbarActivator;

/**
 * Base class for the launcher sub-mods: provides shared references and the
 * {@link #applyHooks()} contract. Adapted from Pixel Taskbar Enabler by beyman-dev (GPLv3).
 */
public abstract class BaseLauncherMod {

	protected final Context mContext;
	protected final TaskbarActivator mActivator;
	protected final TaskbarSettings mSettings;

	public BaseLauncherMod(TaskbarActivator activator, Context context, TaskbarSettings settings) {
		this.mActivator = activator;
		this.mContext = context;
		this.mSettings = settings;
	}

	public abstract void applyHooks() throws Throwable;
}
