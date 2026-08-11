package com.tsinbei.pixelxpert.xposed.modpacks.gboard;

import static com.tsinbei.pixelxpert.xposed.XPrefs.Xprefs;
import static com.tsinbei.pixelxpert.xposed.utils.reflection.XposedCompat.getObjectField;

import android.content.ContentValues;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.inputmethodservice.InputMethodService;
import android.net.Uri;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import io.github.libxposed.api.XposedModuleInterface;

import com.tsinbei.pixelxpert.xposed.XposedModPack;
import com.tsinbei.pixelxpert.xposed.annotations.GboardModPack;
import com.tsinbei.pixelxpert.xposed.utils.reflection.ReflectedClass;

/**
 * Gboard enhancements adapted from Gboard Material Expressive Black, GboardHook and KeyFlux.
 * The implementation is rewritten for PixelXpert's libxposed and remote preference model.
 */
@GboardModPack
public class GboardMods extends XposedModPack {
	private static final int AMOLED_BLACK = 0xFF000000;
	private static final Set<Integer> OBFUSCATED_DARK_SURFACES = Set.of(
			0xFF202124, 0xFF131314, 0xFF1F1F1F, 0xFF1C1B1F, 0xFF171717,
			0xFF2C2C2C, 0xFF303030, 0xFF18191A, 0xFF282A2D);
	private static final Pattern SHORT_CODE = Pattern.compile("(?<!\\d)\\d{4,8}(?!\\d)");
	private static final Pattern LONG_NUMBER = Pattern.compile("(?<!\\d)\\d{12,19}(?!\\d)");

	private static volatile boolean amoledBlack;
	private static volatile boolean clipboardHistory;
	private static volatile boolean secureClipboard;
	private static volatile boolean forceIncognito;
	private static volatile boolean privacyFlags;
	private static volatile boolean aiFeatures;
	private static volatile boolean grammarFeatures;
	private static volatile boolean multilingualFeatures;
	private static volatile boolean floatingFeatures;
	private static volatile boolean emojiKitchen;
	private static volatile boolean accessPointRedesign;
	private static volatile boolean meteredDownloads;
	private static volatile boolean inlineSuggestions;
	private static volatile boolean proactiveEmoji;
	private static volatile boolean clipboardChips;
	private static volatile boolean tfliteEngine;
	private static volatile boolean fastAccessBar;
	private static volatile int clipboardSize = 10;
	private static volatile long clipboardDuration = 3L * 24 * 60 * 60 * 1000;
	private static volatile boolean currentFieldSecure;

	private static final Set<String> AI_FLAGS = Set.of(
			"enable_ai_core_llm", "enable_ai_core_smart_reply", "enable_emojify",
			"enable_emojify_settings_option", "enable_smart_reply", "enable_smart_compose",
			"enable_smart_compose_inline_suggestions", "enable_inline_suggestions",
			"enable_inline_suggestions_on_all_apps", "enable_custom_sticker_tab",
			"enable_custom_sticker_lol_fix", "enable_custom_sticker_naive_prompt_expander",
			"enable_sticker_predictions_while_typing", "enable_animated_emoji_content_suggestions",
			"show_animated_emoji_in_expression_moment", "enable_emojify_language_tags",
			"enable_emojify_model_language_tags", "enable_expression_moment_language_tags",
			"enable_expression_moment_proactive_emoji_kitchen_language_tags",
			"enable_dynamic_art_language_tags", "enable_tenor_trending_term_v2_for_language_tags");
	private static final Set<String> GRAMMAR_FLAGS = Set.of(
			"enable_grammar_checker", "enable_on_device_proofread", "enable_llm_based_grammar_checker",
			"enable_writing_tools_cooperative_mode", "enable_text_conversion",
			"enable_highlight_voice_reconversion_composing_text", "nga_enable_undo_delete",
			"enable_proofread", "enable_pk_auto_correction_locales");
	private static final Set<String> MULTILINGUAL_FLAGS = Set.of(
			"enable_multilingual_typing", "enable_crank_for_first_supported_locale_in_multilingual",
			"enable_crank_for_primary_locale_in_multilingual", "enable_more_candidates_view_for_multilingual",
			"enable_auto_multi_lang_on_all_pixel_devices", "enable_speech_enhancement_for_multilang_users");
	private static final Set<String> FLOATING_FLAGS = Set.of(
			"enable_auto_float_keyboard_in_landscape", "enable_auto_float_keyboard_in_multi_window",
			"enable_auto_float_keyboard_in_freeform", "enable_split_keyboard_on_tablet_large",
			"enable_dynamic_font_size_slider", "enable_split_keyboard", "enable_tablet_split_keyboard",
			"enable_enter_exit_animation");
	private static final Set<String> EMOJI_FLAGS = Set.of(
			"enable_emoji_kitchen_browse", "enable_emoji_kitchen_browse_entry_point_v2",
			"enable_emoji_kitchen_for_zero_state_emojis", "enable_embedded_photo_picker",
			"enable_emoji_search_v2", "enable_emoji_recommendations", "enable_play_emoji_kitchen_mix_animation");
	private static final Set<String> ACCESS_POINT_FLAGS = Set.of(
			"enable_access_points_menu_redesign", "enable_access_point_keyboard", "use_silk_theme_by_default",
			"use_system_font", "enable_custom_themes", "enable_silk_theme",
			"enable_candidates_access_points_switching_animation", "keyboard_redesign_google_sans",
			"keyboard_redesign_forbid_key_shadows");
	private static final Set<String> METERED_FLAGS = Set.of(
			"allow_language_pack_downloads_on_metered_connections", "allow_metered_network_to_download_langid_model",
			"allow_metered_small_speech_pack_downloads", "force_speech_language_pack_updates");
	private static final Set<String> PRIVACY_TRUE_FLAGS = Set.of(
			"disable_correction_storage", "disable_content_capture_for_input_view", "deprecate_native_log_event");
	private static final Set<String> PRIVACY_FALSE_FLAGS = Set.of(
			"always_log_speed_stats", "enable_logging_for_emoji_search_query",
			"enable_internal_speech_enhancement_pii_logging", "enable_report_from_training_cache",
			"enable_chinese_training_cache", "enable_spell_checker_training_cache",
			"enable_training_cache_metrics_processors", "enable_conversation_id_in_training_cache",
			"enable_auto_correction_stats", "enable_metric_counts_stats", "enable_spatial_stats",
			"enable_spell_checker_stats", "enable_typo_stats", "voice_donation_promo_banner",
			"voice_donation_confirm_banner");
	private static final Set<String> INLINE_FLAGS = Set.of(
			"enable_inline_suggestions_on_decoder_side", "enable_multiword_predictions_as_inline_from_crank_cifg");
	private static final Set<String> PROACTIVE_EMOJI_FLAGS = Set.of(
			"enable_proactive_emoji_kitchen", "enable_expression_moment");
	private static final Set<String> CLIPBOARD_CHIP_FLAGS = Set.of(
			"enable_clipboard_action_chips", "enable_clipboard_entity_extraction", "enable_copy_to_reply");
	private static final Set<String> TFLITE_FLAGS = Set.of(
			"enable_nwp_tflite_engine", "enable_emoji_predictor_tflite_engine");
	private static final Set<String> FAST_ACCESS_FLAGS = Set.of(
			"enable_fast_access_bar", "keyboard_redesign_google_sans");
	private static final Set<String> ALL_FLAGS = buildAllFlags();

	public GboardMods(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... key) {
		if (Xprefs == null) return;
		amoledBlack = Xprefs.getBoolean("GboardAmoledBlack", false);
		clipboardHistory = Xprefs.getBoolean("GboardClipboardHistory", false);
		secureClipboard = Xprefs.getBoolean("GboardSecureClipboard", false);
		forceIncognito = Xprefs.getBoolean("GboardForceIncognito", false);
		privacyFlags = Xprefs.getBoolean("GboardPrivacyFlags", false);
		aiFeatures = Xprefs.getBoolean("GboardAiFeatures", false);
		grammarFeatures = Xprefs.getBoolean("GboardGrammarFeatures", false);
		multilingualFeatures = Xprefs.getBoolean("GboardMultilingualFeatures", false);
		floatingFeatures = Xprefs.getBoolean("GboardFloatingFeatures", false);
		emojiKitchen = Xprefs.getBoolean("GboardEmojiKitchen", false);
		accessPointRedesign = Xprefs.getBoolean("GboardAccessPointRedesign", false);
		meteredDownloads = Xprefs.getBoolean("GboardMeteredDownloads", false);
		inlineSuggestions = Xprefs.getBoolean("GboardInlineSuggestions", false);
		proactiveEmoji = Xprefs.getBoolean("GboardProactiveEmoji", false);
		clipboardChips = Xprefs.getBoolean("GboardClipboardChips", false);
		tfliteEngine = Xprefs.getBoolean("GboardTfliteEngine", false);
		fastAccessBar = Xprefs.getBoolean("GboardFastAccessBar", false);
		clipboardSize = parseInt(Xprefs.getString("GboardClipboardSize", "10"), 10, 5, 100);
		long days = parseInt(Xprefs.getString("GboardClipboardDays", "3"), 3, 0, 3650);
		clipboardDuration = days == 0 ? -1 : days * 24L * 60 * 60 * 1000;
	}

	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam param) throws Throwable {
		hookAmoledTheme();
		hookInputPrivacy();
		hookClipboardResolver();
		hookClipboardProvider(param.getClassLoader());
		hookClipboardSizeCompatibility();
		GboardDexResolver.resolve(mContext, param.getClassLoader(), method -> {
			if (method != null) hookFlagReader(method);
		});
	}

	private void hookAmoledTheme() throws NoSuchMethodException {
		Method method = TypedArray.class.getDeclaredMethod("getColor", int.class, int.class);
		ReflectedClass.of(TypedArray.class).after(method).run(param -> {
			if (!amoledBlack) return;
			TypedArray typedArray = param.getThisObject();
			if ((typedArray.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
					!= Configuration.UI_MODE_NIGHT_YES) return;
			int resourceId = typedArray.getResourceId(param.getArg(0), 0);
			if (resourceId == 0) return;
			int color = param.getResult();
			if (shouldUseAmoledBlack(typedArray.getResources(), resourceId, color)) {
				param.setResult(AMOLED_BLACK);
			}
		});

		Method getColor = Resources.class.getDeclaredMethod("getColor", int.class);
		ReflectedClass.of(Resources.class).after(getColor).run(param -> {
			if (amoledBlack && isNightMode((Resources) param.thisObject)
					&& shouldUseAmoledBlack((Resources) param.thisObject, param.getArg(0), param.getResult())) {
				param.setResult(AMOLED_BLACK);
			}
		});

		Method getColorWithTheme = Resources.class.getDeclaredMethod("getColor", int.class, Resources.Theme.class);
		ReflectedClass.of(Resources.class).after(getColorWithTheme).run(param -> {
			if (amoledBlack && isNightMode((Resources) param.thisObject)
					&& shouldUseAmoledBlack((Resources) param.thisObject, param.getArg(0), param.getResult())) {
				param.setResult(AMOLED_BLACK);
			}
		});
	}

	private static boolean isNightMode(Resources resources) {
		return (resources.getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
				== Configuration.UI_MODE_NIGHT_YES;
	}

	private static boolean shouldUseAmoledBlack(Resources resources, int resourceId, int color) {
		try {
			String name = resources.getResourceEntryName(resourceId);
			if ((name.startsWith("system_surface_container") || name.startsWith("color_gm3_surface_container"))
					&& !name.contains("high")) {
				return true;
			}
			return "0_resource_name_obfuscated".equals(name) && OBFUSCATED_DARK_SURFACES.contains(color);
		} catch (Throwable ignored) {
			return false;
		}
	}

	private void hookInputPrivacy() throws NoSuchMethodException {
		Method method = InputMethodService.class.getDeclaredMethod("onStartInput", EditorInfo.class, boolean.class);
		ReflectedClass.of(InputMethodService.class).before(method).run(param -> {
			EditorInfo editorInfo = param.getArg(0);
			int inputType = editorInfo.inputType;
			int inputClass = inputType & InputType.TYPE_MASK_CLASS;
			int variation = inputType & InputType.TYPE_MASK_VARIATION;
			currentFieldSecure = (inputClass == InputType.TYPE_CLASS_TEXT
					&& (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
					|| variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
					|| variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
					|| (inputClass == InputType.TYPE_CLASS_NUMBER
					&& variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD);
			if (forceIncognito) {
				editorInfo.imeOptions |= EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;
			}
		});
	}

	private void hookClipboardProvider(ClassLoader classLoader) {
		String[] candidates = {
				"com.google.android.apps.inputmethod.libs.clipboard.ClipboardContentProvider",
				"com.google.android.inputmethod.latin.clipboard.ClipboardContentProvider",
				"com.google.android.apps.inputmethod.latin.clipboard.ClipboardContentProvider"
		};
		for (String candidate : candidates) {
			try {
				Class<?> provider = Class.forName(candidate, false, classLoader);
				Method query = provider.getDeclaredMethod("query", Uri.class, String[].class, String.class, String[].class, String.class);
				ReflectedClass.of(provider).before(query).run(param -> modifyClipboardQuery(param.args));
				Method insert = provider.getDeclaredMethod("insert", Uri.class, ContentValues.class);
				ReflectedClass.of(provider).before(insert).run(param -> {
					if (!secureClipboard) return;
					ContentValues values = param.getArg(1);
					if (currentFieldSecure || (values != null && containsSensitiveClipboardText(values))) {
						param.setResult(null);
					}
				});
				return;
			} catch (Throwable ignored) { }
		}
	}

	private void hookClipboardResolver() throws NoSuchMethodException {
		Method query = ContentResolver.class.getDeclaredMethod("query", Uri.class, String[].class,
				String.class, String[].class, String.class);
		ReflectedClass.of(ContentResolver.class).before(query).run(param -> {
			if (!clipboardHistory) return;
			String[] projection = param.getArg(1);
			String selection = param.getArg(2);
			String[] selectionArgs = param.getArg(3);
			String sortOrder = param.getArg(4);
			if (!isClipboardQuery(projection, selection, sortOrder)) return;
			modifyClipboardQuery(param.args);
			if (sortOrder == null || !sortOrder.matches("(?i).*\\blimit\\s+\\d+.*")) {
				param.args[4] = (sortOrder == null ? "" : sortOrder + " ") + "LIMIT " + clipboardSize;
			}
		});
	}

	private void hookClipboardSizeCompatibility() throws NoSuchMethodException {
		Method size = HashSet.class.getDeclaredMethod("size");
		ReflectedClass.of(HashSet.class).before(size).run(param -> {
			if (!clipboardHistory) return;
			HashSet<?> set = param.getThisObject();
			Object first = set.isEmpty() ? null : set.iterator().next();
			if (first == null || !"j$.time.Instant".equals(first.getClass().getName())) return;
			Object backingMap = getObjectField(set, "map");
			if (backingMap instanceof Map<?, ?> map && map.size() <= clipboardSize) {
				param.setResult(5);
			}
		});
	}

	private static boolean isClipboardQuery(String[] projection, String selection, String sortOrder) {
		String query = (selection == null ? "" : selection) + " " + (sortOrder == null ? "" : sortOrder);
		if (query.matches("(?i).*timestamp.*")) return true;
		if (projection != null) {
			for (String column : projection) {
				if (column != null && column.matches("(?i).*timestamp.*")) return true;
			}
		}
		return false;
	}

	private static void modifyClipboardQuery(Object[] args) {
		if (!clipboardHistory || args.length < 5) return;
		String selection = args[2] instanceof String ? (String) args[2] : "";
		String[] selectionArgs = args[3] instanceof String[] ? (String[]) args[3] : null;
		String sortOrder = args[4] instanceof String ? (String) args[4] : null;
		int timestampIndex = selection.toLowerCase(Locale.ROOT).indexOf("timestamp >= ?");
		if (timestampIndex >= 0 && selectionArgs != null) {
			int argumentIndex = 0;
			for (int i = 0; i < timestampIndex; i++) if (selection.charAt(i) == '?') argumentIndex++;
			if (argumentIndex < selectionArgs.length) {
				long after = clipboardDuration < 0 ? 0 : System.currentTimeMillis() - clipboardDuration;
				selectionArgs[argumentIndex] = Long.toString(after);
				args[3] = selectionArgs;
			}
		}
		if (sortOrder != null && sortOrder.matches("(?i).*\\blimit\\s+\\d+.*")) {
			args[4] = sortOrder.replaceFirst("(?i)limit\\s+\\d+", "limit " + clipboardSize);
		}
	}

	private static boolean containsSensitiveClipboardText(ContentValues values) {
		for (String key : values.keySet()) {
			String normalizedKey = key.toLowerCase(Locale.ROOT);
			if (!normalizedKey.contains("text") && !normalizedKey.contains("content")) continue;
			String value = values.getAsString(key);
			if (value != null && isSensitiveText(value)) return true;
		}
		return false;
	}

	private static boolean isSensitiveText(String text) {
		String normalized = text.toLowerCase(Locale.ROOT);
		return SHORT_CODE.matcher(normalized).find()
				|| LONG_NUMBER.matcher(normalized).find()
				|| normalized.contains("otp") || normalized.contains("one-time")
				|| normalized.contains("verification code") || normalized.contains("password")
				|| normalized.contains("passcode") || normalized.contains("pin code");
	}

	private static void hookFlagReader(Method method) {
		ReflectedClass.of(method.getDeclaringClass()).before(method).run(param -> {
			String name = findKnownFlagName(param.thisObject);
			if (name == null) return;
			Object override = evaluateFlag(name);
			if (override != null) param.setResult(override);
		});
	}

	private static String findKnownFlagName(Object flag) {
		for (Class<?> type = flag.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
			for (Field field : type.getDeclaredFields()) {
				if (field.getType() != String.class || Modifier.isStatic(field.getModifiers())) continue;
				try {
					field.setAccessible(true);
					Object value = field.get(flag);
					if (value instanceof String && ALL_FLAGS.contains(value)) return (String) value;
				} catch (Throwable ignored) { }
			}
		}
		return null;
	}

	private static Object evaluateFlag(String name) {
		Object enabled = stringFlag(name) ? "*" : Boolean.TRUE;
		Object disabled = stringFlag(name) ? "" : Boolean.FALSE;
		if (clipboardHistory
				&& (name.equals("enable_clipboard_entity_extraction") || name.equals("enable_clipboard_query_refactoring"))) {
			return disabled;
		}
		if (privacyFlags && PRIVACY_TRUE_FLAGS.contains(name)) return enabled;
		if (privacyFlags && PRIVACY_FALSE_FLAGS.contains(name)) return disabled;
		if (aiFeatures && AI_FLAGS.contains(name)) return enabled;
		if (grammarFeatures && GRAMMAR_FLAGS.contains(name)) return enabled;
		if (multilingualFeatures && MULTILINGUAL_FLAGS.contains(name)) return enabled;
		if (floatingFeatures && FLOATING_FLAGS.contains(name)) return enabled;
		if (emojiKitchen && EMOJI_FLAGS.contains(name)) return enabled;
		if (accessPointRedesign && ACCESS_POINT_FLAGS.contains(name)) return enabled;
		if (meteredDownloads && METERED_FLAGS.contains(name)) return enabled;
		if (inlineSuggestions && INLINE_FLAGS.contains(name)) return enabled;
		if (proactiveEmoji && PROACTIVE_EMOJI_FLAGS.contains(name)) return enabled;
		if (clipboardChips && CLIPBOARD_CHIP_FLAGS.contains(name)) return enabled;
		if (tfliteEngine && TFLITE_FLAGS.contains(name)) return enabled;
		if (fastAccessBar && FAST_ACCESS_FLAGS.contains(name)) return enabled;
		return null;
	}

	private static boolean stringFlag(String name) {
		return name.endsWith("_language_tags") || name.endsWith("_locales") || name.endsWith("_countries");
	}

	@SafeVarargs
	private static Set<String> combine(Set<String>... sets) {
		java.util.HashSet<String> result = new java.util.HashSet<>();
		for (Set<String> set : sets) result.addAll(set);
		result.add("enable_clipboard_query_refactoring");
		return Set.copyOf(result);
	}

	private static Set<String> buildAllFlags() {
		return combine(AI_FLAGS, GRAMMAR_FLAGS, MULTILINGUAL_FLAGS, FLOATING_FLAGS, EMOJI_FLAGS,
				ACCESS_POINT_FLAGS, METERED_FLAGS, PRIVACY_TRUE_FLAGS, PRIVACY_FALSE_FLAGS, INLINE_FLAGS,
				PROACTIVE_EMOJI_FLAGS, CLIPBOARD_CHIP_FLAGS, TFLITE_FLAGS, FAST_ACCESS_FLAGS);
	}

	private static int parseInt(String value, int fallback, int minimum, int maximum) {
		try {
			return Math.max(minimum, Math.min(maximum, Integer.parseInt(value.trim())));
		} catch (Throwable ignored) {
			return fallback;
		}
	}
}
