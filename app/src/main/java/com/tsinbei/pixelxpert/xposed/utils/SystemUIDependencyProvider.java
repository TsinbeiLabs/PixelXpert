package com.tsinbei.pixelxpert.xposed.utils;




import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.callMethod;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.getStaticObjectField;

import com.tsinbei.pixelxpert.xposed.utils.reflection.ReflectedClass;

public class SystemUIDependencyProvider {
	private static Object sDependency;
	public static Object get(String fullClassName)
	{
		if(sDependency == null)
			getInstance();

		try {
			return callMethod(sDependency, "getDependencyInner", ReflectedClass.of(fullClassName).getClazz());
		}
		catch (Throwable ignored)
		{
			return null;
		}
	}

	private static void getInstance() {
		ReflectedClass DependencyClass = ReflectedClass.of("com.android.systemui.Dependency");
		sDependency = getStaticObjectField(DependencyClass.getClazz(), "sDependency");
	}
}