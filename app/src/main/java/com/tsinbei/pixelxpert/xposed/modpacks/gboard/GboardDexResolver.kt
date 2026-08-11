package com.tsinbei.pixelxpert.xposed.modpacks.gboard

import android.content.Context
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.wrap.DexMethod
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Resolves Gboard's obfuscated flag reader and caches it per Gboard version. */
object GboardDexResolver {
    fun interface Callback {
        fun accept(method: Method?)
    }

    private const val CACHE_FILE = "pixelxpert_gboard_cache"
    private const val CACHE_VERSION = "flag_reader_version"
    private const val CACHE_METHOD = "flag_reader_method"
    private const val CLIPBOARD_CACHE_VERSION = "clipboard_loader_version"
    private const val CLIPBOARD_CACHE_METHOD = "clipboard_loader_method"

    init {
        runCatching { System.loadLibrary("dexkit") }
    }

    @JvmStatic
    fun resolve(context: Context, classLoader: ClassLoader, callback: Callback) {
        Thread({ callback.accept(resolveFlagReader(context, classLoader)) }, "PixelXpert-GboardFlags").start()
    }

    @JvmStatic
    fun resolveClipboardLoader(context: Context, classLoader: ClassLoader, callback: Callback) {
        Thread({ callback.accept(resolveClipboardLoaderInternal(context, classLoader)) }, "PixelXpert-GboardClipboard").start()
    }

    private fun resolveFlagReader(context: Context, classLoader: ClassLoader): Method? = runCatching {
        val versionCode = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        val cache = context.getSharedPreferences(CACHE_FILE, Context.MODE_PRIVATE)
        if (cache.getLong(CACHE_VERSION, -1L) == versionCode) {
            cache.getString(CACHE_METHOD, null)?.let { serialized ->
                validate(DexMethod(serialized).getMethodInstance(classLoader))?.let { return it }
            }
        }

        val resolved = DexKitBridge.create(classLoader, true).use { bridge ->
            val matches = bridge.findMethod {
                matcher {
                    usingStrings("Invalid flag: ")
                    returnType("java.lang.Object")
                }
            }
            if (matches.size != 1) return@use null
            matches.single().toDexMethod()
        } ?: return null

        val method = validate(resolved.getMethodInstance(classLoader)) ?: return null
        cache.edit()
            .putLong(CACHE_VERSION, versionCode)
            .putString(CACHE_METHOD, resolved.serialize())
            .apply()
        method
    }.getOrNull()

    private fun resolveClipboardLoaderInternal(context: Context, classLoader: ClassLoader): Method? = runCatching {
        val versionCode = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        val cache = context.getSharedPreferences(CACHE_FILE, Context.MODE_PRIVATE)
        if (cache.getLong(CLIPBOARD_CACHE_VERSION, -1L) == versionCode) {
            cache.getString(CLIPBOARD_CACHE_METHOD, null)?.let { serialized ->
                validate(DexMethod(serialized).getMethodInstance(classLoader))?.let { return it }
            }
        }

        val resolved = DexKitBridge.create(classLoader, true).use { bridge ->
            val matches = bridge.findMethod {
                matcher {
                    usingStrings("timestamp DESC limit %d")
                    returnType("java.lang.Object")
                    paramTypes()
                }
            }
            if (matches.size != 1) return@use null
            matches.single().toDexMethod()
        } ?: return null

        val method = validate(resolved.getMethodInstance(classLoader)) ?: return null
        cache.edit()
            .putLong(CLIPBOARD_CACHE_VERSION, versionCode)
            .putString(CLIPBOARD_CACHE_METHOD, resolved.serialize())
            .apply()
        method
    }.getOrNull()

    private fun validate(method: Method): Method? {
        return method.takeIf {
            it.parameterCount == 0 &&
                it.returnType == Any::class.java &&
                !Modifier.isStatic(it.modifiers)
        }
    }
}
