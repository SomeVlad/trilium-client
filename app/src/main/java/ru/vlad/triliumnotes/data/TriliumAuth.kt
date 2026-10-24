package ru.vlad.triliumnotes.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

class InvalidServerUrlException(message: String) : IllegalArgumentException(message)
class InvalidAuthInputException(message: String) : IllegalArgumentException(message)
class InvalidAuthResponseException : IOException("Server did not return a Trilium authentication token")

/** The token never appears in diagnostics or generated data-class representations. */
data class TriliumSession(
    val id: String,
    val accountId: String,
    val serverUrl: String,
    val token: String,
    val passwordLogin: Boolean,
) {
    override fun toString(): String = "TriliumSession(id=$id, serverUrl=$serverUrl, token=<redacted>)"
}

fun normalizeServerUrl(value: String, allowInsecureHttp: Boolean = false): String {
    val input = value.trim()
    val uri = runCatching { URI(input) }.getOrNull()
        ?: throw InvalidServerUrlException("Укажите полный адрес сервера, например https://notes.example.com")
    if (uri.isOpaque || uri.rawAuthority.isNullOrBlank())
        throw InvalidServerUrlException("Укажите полный адрес сервера, например https://notes.example.com")
    if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null || '\\' in input)
        throw InvalidServerUrlException("Адрес сервера не должен содержать логин, параметры или фрагмент")
    val url = input.toHttpUrlOrNull()
        ?: throw InvalidServerUrlException("Укажите полный адрес сервера, например https://notes.example.com")
    if (url.scheme != "https" && !allowInsecureHttp)
        throw InvalidServerUrlException("Для HTTP включите разрешение незащищённого соединения")
    if (url.username.isNotEmpty() || url.password.isNotEmpty())
        throw InvalidServerUrlException("Адрес сервера не должен содержать логин или пароль")
    return url.toString().trimEnd('/')
}

internal fun accountIdFor(serverUrl: String): String = MessageDigest.getInstance("SHA-256")
    .digest(serverUrl.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

class TriliumAuth(
    client: OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS).build(),
) {
    // Redirects from reverse-proxy login pages must never receive a password or token.
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    suspend fun authenticate(
        serverUrl: String,
        secret: String,
        useToken: Boolean = false,
        allowInsecureHttp: Boolean = false,
    ): TriliumSession = withContext(Dispatchers.IO) {
        val normalized = normalizeServerUrl(serverUrl, allowInsecureHttp)
        if (secret.isBlank()) throw InvalidAuthInputException(if (useToken) "Введите ETAPI-токен" else "Введите пароль Trilium")
        val token = if (useToken) secret.removePrefix("Bearer ").trim() else {
            val body = JSONObject().put("password", secret).toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder().url("$normalized/etapi/auth/login").post(body).build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (isHtmlInsteadOfJson(response.header("Content-Type"), text))
                    throw UnexpectedHtmlResponseException(response.code)
                if (!response.isSuccessful) throw ApiException(response.code)
                val json = runCatching { JSONObject(text) }.getOrElse { throw InvalidAuthResponseException() }
                json.optString("authToken").takeIf { it.isNotBlank() && it != "null" }
                    ?: throw InvalidAuthResponseException()
            }
        }
        if (token.isBlank() || !token.all { it in '!'..'~' }) throw InvalidAuthInputException("Некорректный ETAPI-токен")
        TriliumApi(normalized, token, client).validateSession()
        TriliumSession(UUID.randomUUID().toString(), accountIdFor(normalized), normalized, token, !useToken)
    }
}

fun authFailureMessage(error: Throwable): String = when (error) {
    is InvalidServerUrlException -> error.message.orEmpty()
    is ApiException -> when (error.status) {
        400, 401 -> "Неверный пароль или ETAPI-токен. Проверьте данные входа."
        403 -> "Сервер отказал в доступе. Проверьте пароль, токен и правила прокси."
        404, 405 -> "ETAPI недоступен по этому адресу. Проверьте адрес сервера и его подпуть."
        in 300..399 -> "Сервер перенаправляет запрос. Укажите конечный адрес Trilium без страницы входа прокси."
        else -> "Сервер вернул ошибку HTTP ${error.status}. Попробуйте позже."
    }
    is UnexpectedHtmlResponseException -> "Вместо Trilium API получена веб-страница. Проверьте адрес, вход прокси или CAPTCHA."
    is InvalidAuthResponseException -> "Сервер вернул некорректный ответ Trilium. Проверьте адрес и поддержку ETAPI."
    is SessionStorageException -> "Не удалось открыть защищённое хранилище входа. Данные и локальные заметки сохранены."
    is javax.net.ssl.SSLException -> "Не удалось проверить TLS-сертификат сервера. Проверьте сертификат и адрес."
    is java.net.UnknownHostException -> "Сервер не найден. Проверьте адрес и подключение."
    is java.net.SocketTimeoutException -> "Сервер не ответил вовремя. Проверьте подключение и повторите вход."
    is InvalidAuthInputException -> error.message.orEmpty()
    is LegacyImportException -> error.message.orEmpty()
    is IllegalArgumentException -> "Проверьте адрес сервера и данные входа"
    is CancellationException -> "Вход отменён"
    else -> "Не удалось подключиться к Trilium. Проверьте адрес сервера и сеть."
}
