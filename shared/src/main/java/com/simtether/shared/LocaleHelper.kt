package com.simtether.shared

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * In-app language override. Framework per-app locales (localeConfig /
 * LocaleManager) only exist on API 33+ and the bridge phone is older,
 * so we store a language tag and wrap each component's base context —
 * every Activity AND Service must call wrap() in attachBaseContext or
 * its notifications/strings stay in the system language.
 * No stored tag = follow the system locale.
 */
object LocaleHelper {
    private const val KEY = "locale_tag"

    /** BCP-47 tag or null when following the system. */
    fun storedTag(context: Context): String? =
        context.getSharedPreferences("app", Context.MODE_PRIVATE)
            .getString(KEY, null)?.takeIf { it.isNotBlank() }

    fun set(context: Context, tag: String?) {
        context.getSharedPreferences("app", Context.MODE_PRIVATE).edit()
            .putString(KEY, tag ?: "").apply()
    }

    fun wrap(base: Context): Context {
        val tag = storedTag(base) ?: return base
        val config = Configuration(base.resources.configuration)
        // setLocale also sets the layout direction (RTL for ar/fa).
        config.setLocale(Locale.forLanguageTag(tag))
        return base.createConfigurationContext(config)
    }
}
