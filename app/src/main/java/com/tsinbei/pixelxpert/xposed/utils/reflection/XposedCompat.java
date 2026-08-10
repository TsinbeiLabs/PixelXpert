package com.tsinbei.pixelxpert.xposed.utils.reflection;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface;

/** Reflection helpers backed by the libxposed module interface. */
public final class XposedCompat {
	private static final Map<Object, Map<String, Object>> additionalFields = Collections.synchronizedMap(new WeakHashMap<>());

	private XposedCompat() {
	}

	public static Class<?> findClass(String name, ClassLoader classLoader) {
		try {
			return Class.forName(name, false, classLoader);
		} catch (ClassNotFoundException exception) {
			throw new IllegalArgumentException("Class not found: " + name, exception);
		}
	}

	public static Class<?> findClassIfExists(String name, ClassLoader classLoader) {
		try {
			return Class.forName(name, false, classLoader);
		} catch (ClassNotFoundException exception) {
			return null;
		}
	}

	@SuppressWarnings("unchecked")
	public static <T> T callMethod(Object object, String name, Object... arguments) {
		try {
			return (T) findBestMethod(object.getClass(), name, arguments).invoke(object, arguments);
		} catch (IllegalAccessException | InvocationTargetException exception) {
			throw new IllegalStateException("Cannot call " + name, exception);
		}
	}

	@SuppressWarnings("unchecked")
	public static <T> T callStaticMethod(Class<?> type, String name, Object... arguments) {
		try {
			return (T) findBestMethod(type, name, arguments).invoke(null, arguments);
		} catch (IllegalAccessException | InvocationTargetException exception) {
			throw new IllegalStateException("Cannot call " + name, exception);
		}
	}

	@SuppressWarnings("unchecked")
	public static <T> T getObjectField(Object object, String name) {
		try {
			return (T) findField(object.getClass(), name).get(object);
		} catch (IllegalAccessException exception) {
			throw new IllegalStateException("Cannot read " + name, exception);
		}
	}

	public static boolean getBooleanField(Object object, String name) { return (boolean) getObjectField(object, name); }
	public static int getIntField(Object object, String name) { return (int) getObjectField(object, name); }
	public static long getLongField(Object object, String name) { return (long) getObjectField(object, name); }
	public static float getFloatField(Object object, String name) { return (float) getObjectField(object, name); }
	@SuppressWarnings("unchecked")
	public static <T> T getStaticObjectField(Class<?> type, String name) {
		try {
			return (T) findField(type, name).get(null);
		} catch (IllegalAccessException exception) {
			throw new IllegalStateException("Cannot read " + name, exception);
		}
	}

	public static void setObjectField(Object object, String name, Object value) {
		try {
			findField(object.getClass(), name).set(object, value);
		} catch (IllegalAccessException exception) {
			throw new IllegalStateException("Cannot write " + name, exception);
		}
	}

	public static Field findFieldIfExists(Class<?> type, String name) {
		try {
			return findField(type, name);
		} catch (IllegalArgumentException exception) {
			return null;
		}
	}

	public static Object getAdditionalInstanceField(Object object, String key) {
		synchronized (additionalFields) {
			Map<String, Object> fields = additionalFields.get(object);
			return fields == null ? null : fields.get(key);
		}
	}

	public static Object setAdditionalInstanceField(Object object, String key, Object value) {
		synchronized (additionalFields) {
			return additionalFields.computeIfAbsent(object, ignored -> new HashMap<>()).put(key, value);
		}
	}

	public static Method findMethodExact(Class<?> type, String name, Class<?>... parameterTypes) {
		try {
			Method method = type.getDeclaredMethod(name, parameterTypes);
			method.setAccessible(true);
			return method;
		} catch (NoSuchMethodException exception) {
			throw new IllegalArgumentException("Method not found: " + name, exception);
		}
	}

	public static Object invokeOriginalMethod(Method method, Object object, Object[] arguments) throws Throwable {
		try {
			return ReflectedClass.getDefaultXposedInterface().getInvoker(method).invoke(object, arguments);
		} catch (InvocationTargetException exception) {
			throw exception.getCause();
		}
	}

	public static void log(String message) {
		XposedInterface xposedInterface = ReflectedClass.getDefaultXposedInterface();
		if (xposedInterface != null) {
			xposedInterface.log(4, "PixelXpert", message);
		}
	}

	public static void log(Throwable throwable) {
		XposedInterface xposedInterface = ReflectedClass.getDefaultXposedInterface();
		if (xposedInterface != null) {
			xposedInterface.log(6, "PixelXpert", throwable.toString(), throwable);
		}
	}

	public static Method findMethodBestMatch(Class<?> type, String name, Object... arguments) {
		return findBestMethod(type, name, arguments);
	}

	private static Field findField(Class<?> type, String name) {
		for (Class<?> current = type; current != null; current = current.getSuperclass()) {
			try {
				Field field = current.getDeclaredField(name);
				field.setAccessible(true);
				return field;
			} catch (NoSuchFieldException ignored) {
			}
		}
		throw new IllegalArgumentException("Field not found: " + name);
	}

	private static Method findBestMethod(Class<?> type, String name, Object[] arguments) {
		for (Class<?> current = type; current != null; current = current.getSuperclass()) {
			for (Method method : current.getDeclaredMethods()) {
				if (method.getName().equals(name) && method.getParameterCount() == arguments.length) {
					method.setAccessible(true);
					return method;
				}
			}
		}
		throw new IllegalArgumentException("Method not found: " + name);
	}
}
