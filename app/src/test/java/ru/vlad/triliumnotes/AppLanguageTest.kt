package ru.vlad.triliumnotes

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import ru.vlad.triliumnotes.data.accountContext
import ru.vlad.triliumnotes.data.ApiException
import ru.vlad.triliumnotes.data.authFailureMessage
import ru.vlad.triliumnotes.ui.folderDisplayTitle
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class AppLanguageTest {
    @Before fun clearPreference() {
        RuntimeEnvironment.getApplication().getSharedPreferences("app-language", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After fun clearSavedTestChoice() = clearPreference()

    private fun sourceContext(vararg tags: String): Context {
        val application = RuntimeEnvironment.getApplication()
        val configuration = Configuration(application.resources.configuration).apply {
            setLocales(LocaleList(*tags.map(Locale::forLanguageTag).toTypedArray()))
        }
        return application.createConfigurationContext(configuration)
    }

    @Test fun supportedPrimaryLanguagesAndUnsupportedFallback() {
        mapOf("en-US" to "en", "en-GB" to "en", "ru-RU" to "ru", "fr-FR" to "ru").forEach { (tag, language) ->
            assertEquals(tag, language, AppLanguage.resolve(Locale.forLanguageTag(tag)))
            val source = sourceContext(tag)
            val localized = AppLanguage.context(source)
            assertEquals(tag, language, localized.resources.configuration.locales[0].language)
            assertEquals(tag, 1, localized.resources.configuration.locales.size())
            assertEquals(tag, if (language == "en") "Connect" else "Подключиться", localized.getString(R.string.connect))
        }
    }

    @Test fun onlyThePrimaryLanguageControlsFallback() {
        val source = sourceContext("fr-FR", "en-US")
        assertEquals("ru", AppLanguage.context(source).resources.configuration.locales[0].language)
        assertEquals("Подключиться", AppLanguage.get(source, R.string.connect))
        assertEquals("Не отправлено: 3", AppLanguage.get(source, R.string.unsent_count, 3))
    }

    @Test fun localizationDoesNotChangeTheSourceConfigurationOrJvmLocale() {
        val source = sourceContext("fr-FR", "en-US")
        val before = Configuration(source.resources.configuration)
        val defaultLocale = Locale.getDefault()
        AppLanguage.context(source)
        AppLanguage.get(source, R.string.connect)
        assertEquals(before, source.resources.configuration)
        assertEquals(LocaleList(Locale.FRANCE, Locale.US), source.resources.configuration.locales)
        assertEquals(defaultLocale, Locale.getDefault())
    }

    @Test fun englishResourcesContainNoRussianUiText() {
        val english = sourceContext("en-GB")
        val cyrillic = Regex("[А-Яа-яЁё]")
        // Native language names remain the same in both translations by design.
        R.string::class.java.fields.filter {
            it.type == Int::class.javaPrimitiveType && it.name != "language_russian"
        }.forEach { field ->
            val text = AppLanguage.context(english).getString(field.getInt(null))
            assertFalse("English resource ${field.name}: $text", cyrillic.containsMatchIn(text))
        }
        assertEquals("Sending changes: 4", AppLanguage.get(english, R.string.sending_count, 4))
    }


    @Test fun manualLanguageOverridesTheSystemAndPersistsAcrossContexts() {
        val french = sourceContext("fr-FR", "en-US")
        assertEquals(null, AppLanguage.preference(french))
        assertEquals("ru", AppLanguage.language(french))
        AppLanguage.select(french, "en")
        val reopened = sourceContext("ru-RU")
        assertEquals("en", AppLanguage.preference(reopened))
        assertEquals("Connect", AppLanguage.get(reopened, R.string.connect))
        assertEquals("en", AppLanguage.context(reopened).resources.configuration.locales[0].language)
        val application = RuntimeEnvironment.getApplication()
        val account = accountContext(application, "a".repeat(64))
        assertEquals("en", AppLanguage.preference(application))
        assertEquals("Connect", AppLanguage.get(account, R.string.connect))
        assertEquals("Incorrect password or ETAPI token. Check your credentials.",
            authFailureMessage(account, ApiException(401)))
        AppLanguage.select(reopened, "ru")
        assertEquals("Подключиться", AppLanguage.get(sourceContext("en-US"), R.string.connect))
    }

    @Test fun choosingTheAutomaticLanguageStillMakesItAnExplicitPreference() {
        val russianFallback = sourceContext("fr-FR")
        AppLanguage.select(russianFallback, "ru")
        assertEquals("ru", AppLanguage.preference(russianFallback))
        assertEquals("ru", AppLanguage.language(sourceContext("en-US")))
        clearPreference()
        val automaticEnglish = sourceContext("en-US")
        assertEquals("en", AppLanguage.language(automaticEnglish))
        AppLanguage.select(automaticEnglish, "en")
        assertEquals("en", AppLanguage.preference(automaticEnglish))
        assertEquals("en", AppLanguage.language(sourceContext("ru-RU")))
    }

    @Test fun rootHeadingIsLocalizedAndUserFolderNamesArePreserved() {
        val english = sourceContext("en-US")
        val russian = sourceContext("ru-RU")
        assertEquals("Notes", folderDisplayTitle(english, Folder("root", "")))
        assertEquals("Заметки", folderDisplayTitle(russian, Folder("root", "")))
        assertEquals("Мои заметки", folderDisplayTitle(english, Folder("user-folder", "Мои заметки")))
    }
}
