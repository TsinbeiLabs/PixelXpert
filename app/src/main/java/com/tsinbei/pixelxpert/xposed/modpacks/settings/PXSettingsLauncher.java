package com.tsinbei.pixelxpert.xposed.modpacks.settings;

import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.callMethod;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.setObjectField;
import static com.tsinbei.pixelxpert.xposed.XPrefs.Xprefs;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;

import androidx.core.content.res.ResourcesCompat;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import io.github.libxposed.api.XposedModuleInterface;
import com.tsinbei.pixelxpert.BuildConfig;
import com.tsinbei.pixelxpert.R;
import com.tsinbei.pixelxpert.xposed.XPLauncher;
import com.tsinbei.pixelxpert.xposed.XposedModPack;
import com.tsinbei.pixelxpert.xposed.annotations.SettingsModPack;
import com.tsinbei.pixelxpert.xposed.utils.reflection.ReflectedClass;

@SuppressWarnings("RedundantThrows")
@SettingsModPack
public class PXSettingsLauncher extends XposedModPack {
	private static boolean PXInSettings = true;

	private boolean mNewSettings = true;

	private boolean mExpressiveTheme = false;

	public PXSettingsLauncher(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {
		PXInSettings = Xprefs.getBoolean("PXInSettings", true);
	}

	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) throws Throwable {
		ReflectedClass HomepagePreferenceClass = ReflectedClass.of("com.android.settings.widget.HomepagePreference");
		ReflectedClass TopLevelSettingsClass = ReflectedClass.of("com.android.settings.homepage.TopLevelSettings");
		ReflectedClass OnPreferenceClickListenerInterface = ReflectedClass.of("androidx.preference.Preference$OnPreferenceClickListener");

		ReflectedClass PreferenceCategoryClass = ReflectedClass.ofIfPossible("androidx.preference.PreferenceCategory");
		ReflectedClass PreferenceManagerClass = ReflectedClass.ofIfPossible("androidx.preference.PreferenceManager");

		try { //A16 expressive theme needs a different icon of PX
			ReflectedClass SettingsThemeHelperClass = ReflectedClass.of("com.android.settingslib.widget.SettingsThemeHelper");
			mExpressiveTheme = (boolean) SettingsThemeHelperClass.callStaticMethod("isExpressiveTheme", mContext);
		}
		catch (Throwable ignored){}

		TopLevelSettingsClass
				.after("getPreferenceScreenResId")
				.run(param -> {
					@SuppressLint("DiscouragedApi")
					int oldResName = mContext.getResources().getIdentifier("top_level_settings", "xml", mContext.getPackageName());

					if (param.getResult().equals(oldResName)) {
						mNewSettings = false;
					}
				});

		TopLevelSettingsClass
				.before("onCreateAdapter")
				.run(param -> {
					if (PXInSettings) {
						Object PXPreference = HomepagePreferenceClass.getClazz().getConstructor(Context.class).newInstance(mContext);

						callMethod(PXPreference, "setIcon",
								ResourcesCompat.getDrawable(XPLauncher.moduleResources,
										mExpressiveTheme
												? R.mipmap.ic_launcher
												: R.drawable.ic_notification_foreground,
										mContext.getTheme()));
						callMethod(PXPreference, "setTitle", XPLauncher.moduleResources.getString(R.string.app_name));

						Object onClickListener = Proxy.newProxyInstance(
								OnPreferenceClickListenerInterface.getClazz().getClassLoader(),
								new Class[]{OnPreferenceClickListenerInterface.getClazz()},
								new PXClickListener());

						setObjectField(PXPreference, "mOnClickListener", onClickListener);

						if (mNewSettings) {
							callMethod(PXPreference, "setSummary", XPLauncher.moduleResources.getString(R.string.xposed_desc));
							Object PXPreferenceCategory = PreferenceCategoryClass.getClazz().getConstructor(Context.class).newInstance(mContext);

							setObjectField(PXPreferenceCategory,
									"mPreferenceManager",
									PreferenceManagerClass.getClazz().getConstructor(Context.class).newInstance(mContext));
							callMethod(PXPreferenceCategory, "setOrder", 9999);

							Object supportCategory = callMethod(param.args[0],
									"findPreference", "top_level_support_category");
							int layoutID = supportCategory == null
									? 0
									: (int) callMethod(supportCategory, "getLayoutResource");

							if (layoutID == 0) {
								@SuppressLint("DiscouragedApi")
								int fallbackLayoutID = mContext.getResources().getIdentifier(
										"settingslib_expressive_untitled_preference_category",
										"layout",
										mContext.getPackageName());
								layoutID = fallbackLayoutID;
							}

							if (layoutID != 0)
								callMethod(PXPreferenceCategory, "setLayoutResource", layoutID);

							callMethod(PXPreferenceCategory, "addPreference", PXPreference);

							callMethod(param.args[0], "addPreference", PXPreferenceCategory);
						} else {
							callMethod(PXPreference, "setOrder", 9999);
							callMethod(param.args[0], "addPreference", PXPreference);
						}
					}
				});
	}

	class PXClickListener implements InvocationHandler {
		/**
		 * @noinspection SuspiciousInvocationHandlerImplementation
		 */
		@Override
		public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			Intent intent = mContext.getPackageManager().getLaunchIntentForPackage(BuildConfig.APPLICATION_ID);
			mContext.startActivity(intent);

			return true;
		}
	}
}
