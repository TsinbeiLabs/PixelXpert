package com.tsinbei.pixelxpert.xposed.utils.reflection;

import androidx.collection.ArraySet;

import com.google.errorprone.annotations.CanIgnoreReturnValue;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;

import de.robv.android.xposed.XposedHelpers;
import io.github.libxposed.api.XposedInterface;

public class HookHelper {
	public static Set<XposedInterface.HookHandle> hookAllConstructors(Class<?> hookClass, ReflectedClass.ReflectionConsumer callback, boolean runBefore, XposedInterface xposedInterface) {
		ArraySet<XposedInterface.HookHandle> result = new ArraySet<>();

		findConstructors(hookClass)
				.forEach(constructor ->
						         result.add(hookMethod(constructor, callback, runBefore, xposedInterface)));
		return result;
	}

	public static Set<XposedInterface.HookHandle> hookAllMethods(Class<?> hookClass, String methodName, ReflectedClass.ReflectionConsumer callback, boolean runBefore, XposedInterface xposedInterface) {
		ArraySet<XposedInterface.HookHandle> result = new ArraySet<>();
		for(Executable method : findMethods(hookClass, methodName)) {
			result.add(hookMethod(method, callback, runBefore, xposedInterface));
		}
		return result;
	}

	@SuppressWarnings("unchecked")
	@CanIgnoreReturnValue
	public static <T> T callMethod(Object obj, String methodName, Object... args) {
		return (T) XposedHelpers.callMethod(obj, methodName, args);
	}

	@SuppressWarnings("unchecked")
	public static <T> T getObjectField(Object obj, String fieldName) {
		return (T) XposedHelpers.getObjectField(obj, fieldName);
	}

	public static XposedInterface.HookHandle hookMethod(Executable hookMethod, ReflectedClass.ReflectionConsumer callback, boolean runBefore, XposedInterface xposedInterface) {
		return xposedInterface.hook(hookMethod)
				.intercept(chain -> {
					RunParam param = new RunParam(chain.getThisObject(), chain.getExecutable(), chain.getArgs().toArray());

					if(runBefore)
					{
						callback.run(param);
						if(param.isResultSet)
							return param.result; //we won't proceed if result is already set

						return chain.proceed(param.args);
					}
					else
					{
						param.result = chain.proceed(param.args);
						callback.run(param);
						return param.result;
					}
				});
	}

	private static Set<Executable> findConstructors(Class<?> clazz)
	{
		return new ArraySet<>(clazz.getDeclaredConstructors());
	}

	private static Set<Method> findMethods(Class<?> clazz, String name) {
		return Arrays.stream(clazz.getDeclaredMethods()).filter(m -> m.getName().equals(name)).collect(ArraySet::new, ArraySet::add, ArraySet::addAll);
	}

	@SuppressWarnings({"unused"})
	public static class RunParam
	{
		public Object thisObject;
		public Executable method;
		public Object[] args;
		private Object result;
		private boolean isResultSet = false;
		public RunParam(Object thisObject, Executable method, Object[] args)
		{
			this.thisObject = thisObject;
			this.method = method;
			this.args = args;
		}

		public void setResult(Object result)
		{
			isResultSet = true;
			this.result = result;
		}
		@SuppressWarnings("unchecked")
		public <T> T getResult()
		{
			return (T) result;
		}

		@SuppressWarnings("unchecked")
		public <T> T getThisObject()
		{
			return (T) thisObject;
		}

		@SuppressWarnings({"unchecked"})
		public <T> T getArg(int index) {
			return (T) args[index];
		}
	}
}