package com.tsinbei.pixelxpert.xposed.modpacks.systemui;

import static android.view.MotionEvent.ACTION_DOWN;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.getFloatField;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.getObjectField;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.setObjectField;
import static com.tsinbei.pixelxpert.xposed.XPrefs.Xprefs;




import android.content.Context;
import android.graphics.Point;
import android.view.MotionEvent;

import io.github.libxposed.api.XposedModuleInterface;
import com.tsinbei.pixelxpert.xposed.XposedModPack;
import com.tsinbei.pixelxpert.xposed.annotations.SystemUIModPack;
import com.tsinbei.pixelxpert.xposed.utils.reflection.ReflectedClass;

@SuppressWarnings("RedundantThrows")
@SystemUIModPack
public class GestureNavbarManager extends XposedModPack {
	//region Back gesture
	private static float backGestureHeightFractionLeft = 1f; // % of screen height. can be anything between 0 to 1
	private static float backGestureHeightFractionRight = 1f; // % of screen height. can be anything between 0 to 1
	private static boolean leftEnabled = true;
	private static boolean rightEnabled = true;
	float initialBackX = 0;

	Object EdgeBackGestureHandler;
	//endregion

	public GestureNavbarManager(Context context) {
		super(context);
	}

	public void onPreferenceUpdated(String... Key) {
		if (Xprefs == null) return;

		//region Back gesture
		leftEnabled = Xprefs.getBoolean("BackFromLeft", true);
		rightEnabled = Xprefs.getBoolean("BackFromRight", true);
		backGestureHeightFractionLeft = Xprefs.getSliderInt( "BackLeftHeight", 100) / 100f;
		backGestureHeightFractionRight = Xprefs.getSliderInt( "BackRightHeight", 100) / 100f;
		//endregion
	}

	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) throws Throwable {
		ReflectedClass EdgeBackGestureHandlerClass = ReflectedClass.ofIfPossible("com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler");
		ReflectedClass NavigationBarEdgePanelClass = ReflectedClass.ofIfPossible("com.android.systemui.navigationbar.gestural.NavigationBarEdgePanel");
		ReflectedClass BackPanelControllerClass = ReflectedClass.of("com.android.systemui.navigationbar.gestural.BackPanelController");

		//region back gesture
		//A16 QPR2 - The class doesn't have a visible constructor anymore, thus replacement method
		EdgeBackGestureHandlerClass
				.before("updateIsEnabled")
				.run(param -> EdgeBackGestureHandler = param.thisObject);

		BackPanelControllerClass
				.before("onMotionEvent")
				.run(param -> {
					MotionEvent ev = (MotionEvent) param.args[0];

					if(ev.getActionMasked() == ACTION_DOWN) //down action is enough. once gesture is refused it won't accept further actions
					{
						if(notWithinInsets(ev.getX(),
								ev.getY(),
								(Point) getObjectField(EdgeBackGestureHandler, "mDisplaySize"),
								getFloatField(EdgeBackGestureHandler, "mBottomGestureHeight")))
						{
							setObjectField(EdgeBackGestureHandler, "mAllowGesture", false); //act like the gesture was not good enough
							param.setResult(null); //and stop the current method too
						}
					}
				});

		//Android 13
		NavigationBarEdgePanelClass
				.before("onMotionEvent")
				.run(param -> {
					MotionEvent event = (MotionEvent) param.args[0];
					if(event.getAction() == ACTION_DOWN)
					{
						initialBackX = event.getX();
					}
					if (notWithinInsets(initialBackX, event.getY(), (Point) getObjectField(param.thisObject, "mDisplaySize"), 0)) {
						//event.setAction(MotionEvent.ACTION_CANCEL);
						param.setResult(null);
					}
				});
		//endregion

	}

	//region Back gesture
	private boolean notWithinInsets(float x, float y, Point mDisplaySize, float mBottomGestureHeight) {
		boolean isLeftSide = x < (mDisplaySize.x / 3f);
		if ((isLeftSide && !leftEnabled)
				|| (!isLeftSide && !rightEnabled)) {
			return true;
		}

		int mEdgeHeight = isLeftSide ?
				Math.round(mDisplaySize.y * backGestureHeightFractionLeft) :
				Math.round(mDisplaySize.y * backGestureHeightFractionRight);

		return mEdgeHeight != 0
				&& y < (mDisplaySize.y
				- mBottomGestureHeight
				- mEdgeHeight);
	}
	//endregion
}