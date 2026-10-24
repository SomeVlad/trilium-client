package ru.vlad.triliumnotes.data

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import ru.vlad.triliumnotes.AppLanguage
import ru.vlad.triliumnotes.R
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import javax.net.ssl.SSLException

@RunWith(RobolectricTestRunner::class)
class DataLocalizationTest {
    private fun context(vararg tags: String): Context {
        val application = RuntimeEnvironment.getApplication()
        return application.createConfigurationContext(Configuration(application.resources.configuration).apply {
            setLocales(LocaleList(*tags.map { Locale.forLanguageTag(it) }.toTypedArray()))
        })
    }

    @Test fun authAndLoadErrorsUseTheSamePrimaryLanguageRule() {
        listOf(arrayOf("en-US"), arrayOf("ru-RU"), arrayOf("fr-FR"), arrayOf("fr-FR", "en-US")).forEach { tags ->
            val source = context(*tags)
            val english = tags.first().startsWith("en")
            assertEquals(tags.joinToString(), if (english)
                "Incorrect password or ETAPI token. Check your credentials."
                else "Неверный пароль или ETAPI-токен. Проверьте данные входа.",
                authFailureMessage(source, ApiException(401)))
            assertEquals(tags.joinToString(), if (english)
                "The server denied access (HTTP 403)."
                else "Сервер отклонил доступ (HTTP 403).",
                loadFailureMessage(source, IOException("private wrapper", ApiException(403))))
            assertEquals(tags.joinToString(), if (english)
                "The server returned HTTP error 502. Try again later."
                else "Сервер вернул ошибку HTTP 502. Попробуйте позже.",
                authFailureMessage(source, ApiException(502)))
            assertEquals(tags.joinToString(), if (english)
                "The server returned HTTP error 503."
                else "Сервер вернул ошибку HTTP 503.",
                loadFailureMessage(source, ApiException(503)))
        }
    }

    @Test fun englishNetworkFailuresAndHtmlChallengesStayActionable() {
        val source = context("en-GB")
        assertEquals("Server not found. Check the address and your connection.",
            authFailureMessage(source, UnknownHostException("private host")))
        assertEquals("Could not resolve the Trilium address (DNS). Check your connection.",
            loadFailureMessage(source, IOException("private wrapper", UnknownHostException("private host"))))
        assertEquals("The server did not respond in time. Check your connection and try signing in again.",
            authFailureMessage(source, SocketTimeoutException("private URL")))
        assertEquals("Trilium did not respond in time. Try loading again.",
            loadFailureMessage(source, IOException("private wrapper", SocketTimeoutException("private URL"))))
        assertTrue(authFailureMessage(source, UnexpectedHtmlResponseException(200)).contains("CAPTCHA"))
        assertTrue(loadFailureMessage(source, IOException("private HTML", UnexpectedHtmlResponseException(200))).contains("CAPTCHA"))
    }

    @Test fun invalidAddressesKeepTheirSpecificValidationMessageInEachLanguage() {
        val cases = listOf(
            "private.invalid" to R.string.msg_server_url_required,
            "https://private-user:private-password@notes.example.com" to R.string.msg_server_url_components,
            "https://notes.example.com?token=private-token" to R.string.msg_server_url_components,
            "https://notes.example.com#private-note" to R.string.msg_server_url_components,
            "http://notes.example.com" to R.string.msg_http_opt_in,
        )
        cases.forEach { (input, expectedId) ->
            val failure = runCatching { normalizeServerUrl(input) }.exceptionOrNull()
            assertTrue(input, failure is InvalidServerUrlException)
            val invalid = failure as InvalidServerUrlException
            assertEquals(input, expectedId, invalid.messageId)
            listOf(context("en-US"), context("ru-RU"), context("fr-FR", "en-US")).forEach { source ->
                val message = authFailureMessage(source, invalid)
                assertEquals(AppLanguage.get(source, expectedId), message)
                assertFalse(message, message.contains("private-"))
                if (source.resources.configuration.locales[0].language == "en")
                    assertFalse(message, Regex("[А-Яа-яЁё]").containsMatchIn(message))
                else assertTrue(message, Regex("[А-Яа-яЁё]").containsMatchIn(message))
            }
        }
        assertEquals("http://notes.example.com", normalizeServerUrl("http://notes.example.com", allowInsecureHttp = true))
    }

    @Test fun diagnosticsNeverDisplayExceptionPayloadsOrNestedPrivateContent() {
        val payload = "private-token https://private-host/private-note?password=private-password"
        val errors = listOf(
            IOException(payload, IllegalStateException(payload)),
            UnknownHostException(payload), SocketTimeoutException(payload), SSLException(payload),
            JSONException(payload), IllegalArgumentException(payload),
            SessionStorageException(IOException(payload)),
        )
        listOf(context("en-US"), context("ru-RU"), context("fr-FR", "en-US")).forEach { source ->
            errors.forEach { error ->
                val outputs = listOf(authFailureMessage(source, error), loadFailureMessage(source, error))
                outputs.forEach { message ->
                    assertFalse(message, message.contains("private-"))
                    assertFalse(message, message.contains("password="))
                }
            }
            val unknown = loadFailureMessage(source, errors.first())
            assertTrue(unknown, unknown.contains("IllegalStateException"))
        }
    }

    @Test fun legacyPendingCountsAndSyncStatusCodesAreFormatted() {
        val english = context("en-US")
        val russianFallback = context("fr-FR", "en-US")
        assertEquals("This server has unsent changes (7). Sync them or save them separately first; the previous files have been kept.",
            authFailureMessage(english, LegacyImportException(R.string.msg_legacy_pending, 7)))
        assertEquals("У этого сервера есть неотправленные изменения (7). Сначала отправьте их или сохраните отдельно; старые файлы сохранены.",
            authFailureMessage(russianFallback, LegacyImportException(R.string.msg_legacy_pending, 7)))
        assertEquals("The Trilium server returned error 503", AppLanguage.get(english, R.string.msg_sync_http, 503))
        assertEquals("Сервер Trilium ответил с ошибкой 503", AppLanguage.get(russianFallback, R.string.msg_sync_http, 503))
    }
}
