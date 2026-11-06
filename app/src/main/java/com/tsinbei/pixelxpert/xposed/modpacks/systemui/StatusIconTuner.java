package com.tsinbei.pixelxpert.xposed.modpacks.systemui;

import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.getObjectField;
import static com.tsinbei.pixelxpert.xposed.XPrefs.Xprefs;
import static com.tsinbei.pixelxpert.xposed.modpacks.systemui.StatusbarMods.APP_SWITCH_SLOT;


import android.content.Context;
import android.util.ArraySet;
import android.view.View;

import java.util.Collection;
import java.util.Set;

import io.github.libxposed.api.XposedModuleInterface;
import com.tsinbei.pixelxpert.xposed.XposedModPack;
import com.tsinbei.pixelxpert.xposed.annotations.SystemUIModPack;
import com.tsinbei.pixelxpert.xposed.utils.reflection.ReflectedClass;

@SystemUIModPack
public class StatusIconTuner extends XposedModPack {
	private static Set<String> SBIgnoredIcons = new ArraySet<>();
	private static Set<String> KGIgnoredIcons = new ArraySet<>();
	private static Set<String> QSIgnoredIcons = new ArraySet<>();

	private Object mSBIconContainer, mQSIconContainer, mKGIconContainer;

	public StatusIconTuner(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {
		SBIgnoredIcons = Xprefs.getStringSet("SBIgnoredIcons", new ArraySet<>());
		KGIgnoredIcons = Xprefs.getStringSet("KGIgnoredIcons", new ArraySet<>());
		QSIgnoredIcons = Xprefs.getStringSet("QSIgnoredIcons", new ArraySet<>());

		KGIgnoredIcons.add(APP_SWITCH_SLOT); //we don't need it on keyguard

		setIgnoredIcons(mSBIconContainer, SBIgnoredIcons);
		setIgnoredIcons(mKGIconContainer, KGIgnoredIcons);
		setIgnoredIcons(mQSIconContainer, QSIgnoredIcons);
	}

	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) throws Throwable {
		ReflectedClass IconManagerClass = ReflectedClass.ofIfPossible("com.android.systemui.statusbar.phone.ui.IconManager");
		if(IconManagerClass.getClazz() == null) //pre 15beta3
		{
			IconManagerClass = ReflectedClass.ofIfPossible("com.android.systemui.statusbar.phone.StatusBarIconController$IconManager");
		}

		if (IconManagerClass.getClazz() != null) {
			IconManagerClass
					.beforeConstruction()
					.run(param -> {
						try {
							classifyAndIgnore((View) param.args[0]);
						}
						catch (Throwable ignored){}
					});
		} else {
			//A17: IconManager was removed; the ignored-slots Set now lives directly on StatusIconContainer.
			ReflectedClass.ofIfPossible("com.android.systemui.statusbar.phone.StatusIconContainer")
					.afterConstruction()
					.run(param -> {
						try {
							classifyAndIgnore((View) param.thisObject);
						}
						catch (Throwable ignored){}
					});
		}
	}

	private void classifyAndIgnore(View iconContainer) {
		//Walks up the view tree so it keeps working whether the identifying id sits on the
		//container itself (A17) or on one of its ancestors (legacy IconManager layout).
		View candidate = iconContainer;
		while (candidate != null) {
			String id;
			try {
				id = mContext.getResources().getResourceName(candidate.getId());
			} catch (Throwable ignored) {
				candidate = (View) candidate.getParent();
				continue;
			}

			if (id.contains("status_bar_end_side_content")) //statusbar specific id
			{
				mSBIconContainer = iconContainer;
				setIgnoredIcons(iconContainer, SBIgnoredIcons);
				return;
			}
			else if(id.contains("shade_header_system_icons")) //QS specific id
			{
				mQSIconContainer = iconContainer;
				setIgnoredIcons(iconContainer, QSIgnoredIcons);
				return;
			}
			else if(id.contains("system_icons_container")) //KG specific id
			{
				mKGIconContainer = iconContainer;
				setIgnoredIcons(iconContainer, KGIgnoredIcons);
				return;
			}

			candidate = (View) candidate.getParent();
		}
	}

	private void setIgnoredIcons(Object container, Set<String> ignorableSlots){
		try
		{
			Object value = getObjectField(container, "mIgnoredSlots");
			//A17: mIgnoredSlots may be a Set rather than a List
			if (!(value instanceof Collection<?> ignoredSlots)) return;

			ignoredSlots.clear();

			for (String slot : ignorableSlots) {
				if(!ignoredSlots.contains(slot))
				{
					com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.callMethod(ignoredSlots, "add", slot);
				}
			}

			((View)container).requestLayout();
		}
		catch (Throwable ignored){
		}
	}
}
