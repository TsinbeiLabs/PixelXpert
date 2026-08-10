package com.tsinbei.pixelxpert.xposed.modpacks.launcher;

import static com.tsinbei.pixelxpert.xposed.XPrefs.Xprefs;

import android.content.Context;
import android.view.WindowManager;

import java.lang.reflect.Array;

import com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat;
import io.github.libxposed.api.XposedModuleInterface;
import com.tsinbei.pixelxpert.xposed.XposedModPack;
import com.tsinbei.pixelxpert.xposed.annotations.LauncherModPack;
import com.tsinbei.pixelxpert.xposed.utils.SystemUtils;
import com.tsinbei.pixelxpert.xposed.utils.reflection.ReflectedClass;

@LauncherModPack
public class HideNavigationBarInsets extends XposedModPack {
    private boolean HideNavbarInsets;

    public HideNavigationBarInsets(Context context) {
        super(context);
    }

    @Override
    public void onPreferenceUpdated(String... Key) {
        HideNavbarInsets = Xprefs.getBoolean("HideNavbarInsets", false);
        if (Key.length > 0 && Key[0].equals("HideNavbarInsets")) {
            SystemUtils.killSelf();
        }
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) throws Throwable {
        ReflectedClass TaskbarActivityContextClass = ReflectedClass.of("com.android.launcher3.taskbar.TaskbarActivityContext");
        TaskbarActivityContextClass
                .before("notifyUpdateLayoutParams")
                .run(param -> {
                    if (!HideNavbarInsets)
                        return;

                    WindowManager.LayoutParams layoutParams = (WindowManager.LayoutParams) XposedCompat.getObjectField(param.thisObject, "mWindowLayoutParams");
                    transformLayoutParams(layoutParams);

                    WindowManager.LayoutParams[] rotationParams = (WindowManager.LayoutParams[]) XposedCompat.getObjectField(layoutParams, "paramsForRotation");
                    if (rotationParams == null)
                        return;

                    for (WindowManager.LayoutParams rotationLayoutParams : rotationParams)
                        transformLayoutParams(rotationLayoutParams);
                });
    }

    private void transformLayoutParams(WindowManager.LayoutParams layoutParams) {
        if (layoutParams == null)
            return;

        Object providedInsets = XposedCompat.getObjectField(layoutParams, "providedInsets");
        if (providedInsets == null)
            return;

        int providedInsetsLength = Array.getLength(providedInsets);
        for (int i = 0; i < providedInsetsLength; i++) {
            Object insetsFrame = Array.get(providedInsets, i);

            if (insetsFrame == null)
                continue;
            if (!insetsFrame.toString().contains("type=navigationBars")) // no constants, maximum compatibility with Android versions
                continue;

            XposedCompat.callMethod(insetsFrame, "setInsetsSize", android.graphics.Insets.NONE);
        }
    }
}
