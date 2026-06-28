package com.gproust.sprout.ui.settings

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.core.content.edit
import java.util.Locale

/**
 * In-app language override layered on top of the system language.
 *
 * On Android 13+ (API 33) this delegates to the framework per-app language API,
 * so an in-app choice and the system Settings "App language" picker stay in sync.
 * On older versions the tag is persisted here and applied by wrapping the
 * activity's base context (see [MainActivity.attachBaseContext]).
 *
 * A null/blank tag means "follow the system language" — which, with our
 * English-default resources, falls back to English when no translation matches.
 */
object AppLocale {
    private const val PREFS = "settings"
    private const val KEY_LANG = "app_language"

    /** BCP-47 tag of the active override, or null when following the system. */
    fun currentTag(context: Context): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)
                ?.applicationLocales
                ?.takeUnless { it.isEmpty }
                ?.get(0)
                ?.toLanguageTag()
        } else {
            context.prefs().getString(KEY_LANG, null)
        }

    /**
     * Apply [tag] (null/blank = follow the system). Returns true when the caller
     * must recreate the activity itself; on API 33+ the framework does it.
     */
    fun apply(context: Context, tag: String?): Boolean {
        val normalized = tag?.takeIf { it.isNotBlank() }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)?.let { lm ->
                lm.applicationLocales =
                    if (normalized == null) LocaleList.getEmptyLocaleList()
                    else LocaleList.forLanguageTags(normalized)
            }
            false
        } else {
            context.prefs().edit { putString(KEY_LANG, normalized) }
            true
        }
    }

    /** Wrap a base context with the persisted locale. No-op on API 33+ (the framework handles it). */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = base.prefs().getString(KEY_LANG, null) ?: return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }

    private fun Context.prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** Unwraps a (possibly wrapped) Context to its hosting Activity, if any. */
fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
