package com.tsinbei.pixelxpert.xposed.modpacks.systemui;

import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.getObjectField;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.callMethod;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.setObjectField;
import static com.tsinbei.pixelxpert.xposed.XPrefs.Xprefs;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.content.res.ResourcesCompat;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

import io.github.libxposed.api.XposedModuleInterface;
import com.tsinbei.pixelxpert.R;
import com.tsinbei.pixelxpert.xposed.XPLauncher;
import com.tsinbei.pixelxpert.xposed.XposedModPack;
import com.tsinbei.pixelxpert.xposed.annotations.SystemUIModPack;
import com.tsinbei.pixelxpert.xposed.utils.SystemUtils;
import com.tsinbei.pixelxpert.xposed.utils.reflection.ReflectedClass;

@SystemUIModPack
public class PowerMenu extends XposedModPack {
	private ReflectedClass mLongPressActionInterface = null;
	private static boolean advancedPowerMenu = false;

	public PowerMenu(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {
		advancedPowerMenu = Xprefs.getBoolean("advancedPowerMenu", false);
	}

	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) throws Throwable {
		ReflectedClass GlobalActionsDialogLiteClass = ReflectedClass.of("com.android.systemui.globalactions.GlobalActionsDialogLite");
		ReflectedClass PowerOptionsAction = ReflectedClass.of("com.android.systemui.globalactions.GlobalActionsDialogLite$PowerOptionsAction");
		mLongPressActionInterface = ReflectedClass.of("com.android.systemui.globalactions.GlobalActionsDialogLite$LongPressAction");

		PowerOptionsAction
				.afterConstruction()
				.run(param -> {
					if(!advancedPowerMenu) return;

					setObjectField(param.thisObject, "mMessageResId", 0);
					setObjectField(param.thisObject, "mMessage", getString(R.string.advanced_option_button_title));
				});

		GlobalActionsDialogLiteClass
				.after("createActionItems")
				.run(param -> {
					if(!advancedPowerMenu) return;


					Object itemsValue = getObjectField(param.thisObject, "mItems");
					Object powerItemsValue = getObjectField(param.thisObject, "mPowerItems");
					if (!(itemsValue instanceof List<?> mItems) || !(powerItemsValue instanceof List<?> mPowerItems)) return;
					callMethod(mItems, "add", PowerOptionsAction.getClazz().getConstructors()[0].newInstance(param.thisObject));
					callMethod(mPowerItems, "add", getAction(new BootloaderAction()));
					callMethod(mPowerItems, "add", getAction(new SoftRebootAction()));
					callMethod(mPowerItems, "add", getAction(new SystemUIRebootAction()));
				});
	}

	private String getString(int id)
	{
		return XPLauncher.moduleResources.getString(id);
	}

	private Object getAction(PXCustomAction action)
	{
		return Proxy.newProxyInstance(
				mLongPressActionInterface.getClazz().getClassLoader(),
				new Class[]{mLongPressActionInterface.getClazz()},
				action);
	}
	class BootloaderAction extends PXCustomAction
	{
		@Override
		protected boolean onLongPress() {
			return false;
		}

		@Override
		void onPress() {
			SystemUtils.restart("bootloader");
		}

		@Override
		String getText() {
			return getString(R.string.reboot_bootloader_title);
		}

		@SuppressLint("DiscouragedApi")
		@Override
		Drawable getIcon() {
			Resources res = mContext.getResources();
			return ResourcesCompat.getDrawable(res, res.getIdentifier("ic_restart", "drawable", "android"), mContext.getTheme());
		}
	}

	class SoftRebootAction extends PXCustomAction
	{
		@Override
		protected boolean onLongPress() {
			return false;
		}

		@Override
		void onPress() {
			SystemUtils.restart("android");
		}

		@Override
		String getText() {
			return getString(R.string.soft_reboot_title);
		}

		@SuppressLint("DiscouragedApi")
		@Override
		Drawable getIcon() {
			Resources res = mContext.getResources();
			return ResourcesCompat.getDrawable(res, res.getIdentifier("ic_restart", "drawable", "android"), mContext.getTheme());
		}
	}

	class SystemUIRebootAction extends PXCustomAction
	{
		@Override
		protected boolean onLongPress() {
			return false;
		}

		@Override
		void onPress() {
			SystemUtils.restart("systemUI");
		}

		@Override
		String getText() {
			return getString(R.string.restart_systemui_title);
		}

		@SuppressLint("DiscouragedApi")
		@Override
		Drawable getIcon() {
			Resources res = mContext.getResources();
			return ResourcesCompat.getDrawable(res, res.getIdentifier("ic_restart", "drawable", "android"), mContext.getTheme());
		}
	}

	abstract class PXCustomAction implements InvocationHandler {
		@Override
		public Object invoke(Object proxy, Method method, Object[] args) {
			switch (method.getName())
			{
				case "create":
//					Context context = (Context) args[0];
//					View convertView = (View) args[1];
					ViewGroup parent = (ViewGroup) args[2];
					LayoutInflater layoutInflater = (LayoutInflater) args[3];
					Resources res = mContext.getResources();

					return create(parent, layoutInflater, res);

				case "onPress": //main action
					onPress();
					return null;
				case "onLongPress": //result: if longpress is handled by us
					return onLongPress();
				case "shouldShow": //used
					return true;

				//unused methods
				case "getLabelForAccessibility": //haven't seen usage
					return "";
				case "showDuringKeyguard": //didn't see usage
				case "showBeforeProvisioning": //didn't see usage
				case "shouldBeSeparated": //didn't see usage
					return false;
				case "isEnabled": //didn't see usage
					return true;
				case "getMessageResId": //not used
					return 0;
				case "getIcon": //not used
					return getIcon();
				case "getMessage": //not used
					return getText();
				//general object methods
				case "equals":
					return this.equals(args[0]);
				case "hashCode":
					return this.hashCode();
				case "toString":
					return this.toString();
			}
			return null;
		}

		private View create(ViewGroup parent, LayoutInflater layoutInflater, Resources res) {
			@SuppressLint("DiscouragedApi")
			View view = layoutInflater.inflate(res.getIdentifier("global_actions_grid_item_lite", "layout", mContext.getPackageName()), parent, false);

			@SuppressLint("DiscouragedApi")
			ImageView iconView = view.findViewById(res.getIdentifier("icon", "id", "android"));

			iconView.setImageDrawable(getIcon());
			iconView.setScaleType(ImageView.ScaleType.CENTER_CROP);

			@SuppressLint("DiscouragedApi")
			TextView messageView = view.findViewById(res.getIdentifier("message", "id", "android"));
			messageView.setSelected(true);
			messageView.setText(getText());

			view.setId(View.generateViewId());
			return view;
		}

		protected abstract boolean onLongPress();

		abstract void onPress();

		abstract String getText();

		abstract Drawable getIcon();
	}
}
