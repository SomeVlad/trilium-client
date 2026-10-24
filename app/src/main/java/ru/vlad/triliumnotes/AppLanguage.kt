package ru.vlad.triliumnotes

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.annotation.StringRes
import java.util.Locale

/** A saved language wins; until the first choice, follow the primary system language. */
object AppLanguage {
    private const val PREFERENCES = "app-language"
    private const val LANGUAGE = "language"

    /** Null is the internal first-launch default, never a selectable menu entry. */
    fun preference(context: Context): String? = (context.applicationContext ?: context).getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getString(LANGUAGE, null)?.takeIf { it == "ru" || it == "en" }

    fun select(context: Context, language: String) {
        require(language == "ru" || language == "en")
        (context.applicationContext ?: context).getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putString(LANGUAGE, language).apply()
    }

    fun language(context: Context): String = preference(context) ?: resolve(
        context.resources.configuration.locales.get(0) ?: Locale.ROOT
    )

    fun resolve(locale: Locale): String = if (locale.language == "en") "en" else "ru"

    fun context(context: Context): Context {
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocales(LocaleList(Locale.forLanguageTag(language(context))))
        return context.createConfigurationContext(configuration)
    }

    fun get(context: Context, @StringRes id: Int, vararg args: Any): String =
        this.context(context).getString(id, *args)
}
