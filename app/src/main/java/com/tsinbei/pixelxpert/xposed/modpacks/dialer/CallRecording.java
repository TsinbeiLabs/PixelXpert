package com.tsinbei.pixelxpert.xposed.modpacks.dialer;

import static com.tsinbei.pixelxpert.xposed.XPrefs.Xprefs;
import static com.tsinbei.pixelxpert.xposed.utils.toolkit.Logger.log;

import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import com.tsinbei.pixelxpert.xposed.XposedModPack;
import com.tsinbei.pixelxpert.xposed.annotations.DialerModPack;
import com.tsinbei.pixelxpert.xposed.utils.SystemUtils;
import com.tsinbei.pixelxpert.xposed.utils.reflection.ReflectedClass;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;

import io.github.libxposed.api.XposedModuleInterface;

@SuppressWarnings("RedundantThrows")
@DialerModPack
public class CallRecording extends XposedModPack {
    private static boolean forceEnableCallRecording = false;

    private static final byte[] wav = {
            82, 73, 70, 70, 36, 0, 0, 0, 87, 65, 86,
            69, 102, 109, 116, 32, 16, 0, 0, 0, 1, 0,
            1, 0, -128, 62, 0, 0, 0, 125, 0, 0, 2,
            0, 16, 0, 100, 97, 116, 97, 0, 0, 0, 0};

    public CallRecording(Context context) {
        super(context);
    }

    @Override
    public void onPreferenceUpdated(String... Key) {
        if (Xprefs == null) return;

        if (Key.length > 0 && Key[0].equals("DialerForceEnableCallRecording")) {
            SystemUtils.killSelf();
        }
        forceEnableCallRecording = Xprefs.getBoolean("DialerForceEnableCallRecording", false);
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) throws Throwable {
        if (!forceEnableCallRecording) return;

        CallRecordingDexResolver.resolve(mContext, PRParam.getClassLoader(), methods -> {
            if (methods.getCanRecordCall() != null) {
                log("CallRecording: Hooking canRecordCall");
                ReflectedClass.of(methods.getCanRecordCall().getDeclaringClass())
                        .before(methods.getCanRecordCall())
                        .run(param -> param.setResult(true));
            }

            if (methods.getWithinCrosbyGeoFence() != null) {
                log("CallRecording: Hooking withinCrosbyGeoFence");
                ReflectedClass.of(methods.getWithinCrosbyGeoFence().getDeclaringClass())
                        .before(methods.getWithinCrosbyGeoFence())
                        .run(param -> param.setResult(true));
            }

            if (methods.getGetSupportedLocaleFromCountryCode() != null) {
                log("CallRecording: Hooking getSupportedLocaleFromCountryCode");
                ReflectedClass.of(methods.getGetSupportedLocaleFromCountryCode().getDeclaringClass())
                        .before(methods.getGetSupportedLocaleFromCountryCode())
                        .run(param -> param.setResult(Locale.US));
            }

            if (methods.getCallRecordingCountryMethod() != null) {
                log("CallRecording: Hooking isCallRecordingCountry");
                ReflectedClass.of(methods.getCallRecordingCountryMethod().getDeclaringClass())
                        .before(methods.getCallRecordingCountryMethod())
                        .run(param -> param.setResult(true));
            }
        });

        hookTTS();
    }

    private void hookTTS() {
        try {
            Method dispatchOnInit = TextToSpeech.class.getDeclaredMethod("dispatchOnInit", int.class);
            ReflectedClass.of(TextToSpeech.class)
                    .before(dispatchOnInit)
                    .run(param -> {
                        if ((Integer) param.args[0] != TextToSpeech.SUCCESS) {
                            param.args[0] = TextToSpeech.SUCCESS;
                            log("CallRecording: TTS dispatchOnInit failed, overridden to SUCCESS");
                        }
                    });
        } catch (NoSuchMethodException e) {
            log("CallRecording: TTS dispatchOnInit not found");
        }

        try {
            Method isLanguageAvailable = TextToSpeech.class.getDeclaredMethod("isLanguageAvailable", Locale.class);
            ReflectedClass.of(TextToSpeech.class)
                    .after(isLanguageAvailable)
                    .run(param -> {
                        if ((Integer) param.getResult() < TextToSpeech.LANG_AVAILABLE) {
                            param.setResult(TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE);
                            log("CallRecording: TTS language not available, overridden to available");
                        }
                    });
        } catch (NoSuchMethodException e) {
            log("CallRecording: TTS isLanguageAvailable not found");
        }

        try {
            Method synthesizeToFile = TextToSpeech.class.getDeclaredMethod("synthesizeToFile",
                    CharSequence.class, Bundle.class, File.class, String.class);
            ReflectedClass.of(TextToSpeech.class)
                    .before(synthesizeToFile)
                    .run(param -> param.args[0] = "");
                    
            ReflectedClass.of(TextToSpeech.class)
                    .after(synthesizeToFile)
                    .run(param -> {
                        if ((Integer) param.getResult() != TextToSpeech.SUCCESS) {
                            log("CallRecording: synthesizeToFile TTS failed, using built-in wav");
                            File file = (File) param.args[2];
                            try (FileOutputStream out = new FileOutputStream(file)) {
                                out.write(wav);
                                param.setResult(TextToSpeech.SUCCESS);
                            } catch (IOException e) {
                                log("CallRecording: synthesizeToFile cannot write " + file);
                            }
                            try {
                                Field field = param.thisObject.getClass().getDeclaredField("mUtteranceProgressListener");
                                field.setAccessible(true);
                                UtteranceProgressListener listener = (UtteranceProgressListener) field.get(param.thisObject);
                                if (listener != null) {
                                    Method onDone = UtteranceProgressListener.class.getDeclaredMethod("onDone", String.class);
                                    onDone.invoke(listener, (String) param.args[3]);
                                }
                            } catch (Exception e) {
                                log("CallRecording: synthesizeToFile cannot invoke onDone");
                            }
                        }
                    });
        } catch (NoSuchMethodException e) {
            log("CallRecording: TTS synthesizeToFile not found");
        }
    }
}
