package com.tsinbei.pixelxpert.xposed.modpacks.dialer

import android.content.Context
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.wrap.DexMethod
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Locale

object CallRecordingDexResolver {
    data class ResolvedMethods(
        val canRecordCall: Method?,
        val withinCrosbyGeoFence: Method?,
        val getSupportedLocaleFromCountryCode: Method?,
        val callRecordingCountryMethod: Method?
    )

    fun interface Callback {
        fun accept(methods: ResolvedMethods)
    }

    private const val CACHE_FILE = "pixelxpert_callrecording_cache"
    private const val CACHE_VERSION = "callrecording_version"
    private const val CACHE_CAN_RECORD_CALL = "can_record_call_method"
    private const val CACHE_WITHIN_CROSBY = "within_crosby_method"
    private const val CACHE_GET_LOCALE = "get_locale_method"
    private const val CACHE_IS_RECORDING_COUNTRY = "is_recording_country_method"

    init {
        runCatching { System.loadLibrary("dexkit") }
    }

    @JvmStatic
    fun resolve(context: Context, classLoader: ClassLoader, callback: Callback) {
        Thread({
            callback.accept(runCatching { resolveInternal(context, classLoader) }
                .getOrElse { ResolvedMethods(null, null, null, null) })
        }, "PixelXpert-CallRecording").start()
    }

    private fun resolveInternal(context: Context, classLoader: ClassLoader): ResolvedMethods {
        val versionCode = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode }.getOrDefault(-1L)
        val cache = context.createDeviceProtectedStorageContext()
            .getSharedPreferences(CACHE_FILE, Context.MODE_PRIVATE)

        if (cache.getLong(CACHE_VERSION, -1L) == versionCode) {
            val canRecordCall = cache.getString(CACHE_CAN_RECORD_CALL, null)?.let { DexMethod(it).getMethodInstance(classLoader) }
            val withinCrosby = cache.getString(CACHE_WITHIN_CROSBY, null)?.let { DexMethod(it).getMethodInstance(classLoader) }
            val getLocale = cache.getString(CACHE_GET_LOCALE, null)?.let { DexMethod(it).getMethodInstance(classLoader) }
            val isRecordingCountry = cache.getString(CACHE_IS_RECORDING_COUNTRY, null)?.let { DexMethod(it).getMethodInstance(classLoader) }

            if (canRecordCall != null || withinCrosby != null || getLocale != null || isRecordingCountry != null) {
                return ResolvedMethods(canRecordCall, withinCrosby, getLocale, isRecordingCountry)
            }
        }

        var resCanRecordCall: DexMethod? = null
        var resWithinCrosby: DexMethod? = null
        var resGetLocale: DexMethod? = null
        var resIsRecordingCountry: DexMethod? = null

        runCatching {
            DexKitBridge.create(classLoader, true).use { bridge ->
                resCanRecordCall = bridge.findMethod {
                    matcher {
                        usingStrings("canRecordCall")
                        returnType("boolean")
                    }
                }.firstOrNull()?.toDexMethod()

                resWithinCrosby = bridge.findMethod {
                    matcher {
                        usingStrings("withinCrosbyGeoFence")
                        returnType("boolean")
                    }
                }.firstOrNull()?.toDexMethod()

                resGetLocale = bridge.findMethod {
                    matcher {
                        usingStrings("getSupportedLocaleFromCountryCode")
                        returnType("java.util.Locale")
                    }
                }.firstOrNull()?.toDexMethod()

                resIsRecordingCountry = bridge.findMethod {
                    matcher {
                        usingStrings("isCallRecordingCountry")
                        returnType("boolean")
                    }
                }.firstOrNull()?.toDexMethod()
            }
        }

        val methCanRecordCall = resCanRecordCall?.getMethodInstance(classLoader)
        val methWithinCrosby = resWithinCrosby?.getMethodInstance(classLoader)
        val methGetLocale = resGetLocale?.getMethodInstance(classLoader)
        val methIsRecordingCountry = resIsRecordingCountry?.getMethodInstance(classLoader)

        cache.edit().apply {
            putLong(CACHE_VERSION, versionCode)
            resCanRecordCall?.let { putString(CACHE_CAN_RECORD_CALL, it.serialize()) }
            resWithinCrosby?.let { putString(CACHE_WITHIN_CROSBY, it.serialize()) }
            resGetLocale?.let { putString(CACHE_GET_LOCALE, it.serialize()) }
            resIsRecordingCountry?.let { putString(CACHE_IS_RECORDING_COUNTRY, it.serialize()) }
        }.apply()

        return ResolvedMethods(methCanRecordCall, methWithinCrosby, methGetLocale, methIsRecordingCountry)
    }
}
