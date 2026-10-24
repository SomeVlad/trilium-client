package ru.vlad.triliumnotes.data

import ru.vlad.triliumnotes.AppLanguage
import ru.vlad.triliumnotes.R
import android.content.Context
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

class InvalidServerUrlException(val messageId: Int) : IllegalArgumentException("Invalid server address")
class InvalidAuthInputException(val messageId: Int) : IllegalArgumentException("Invalid authentication input")
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
        ?: throw InvalidServerUrlException(R.string.msg_server_url_required)
    if (uri.isOpaque || uri.rawAuthority.isNullOrBlank())
        throw InvalidServerUrlException(R.string.msg_server_url_required)
    if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null || '\\' in input)
        throw InvalidServerUrlException(R.string.msg_server_url_components)
    val url = input.toHttpUrlOrNull()
        ?: throw InvalidServerUrlException(R.string.msg_server_url_required)
    if (url.scheme != "https" && !allowInsecureHttp)
        throw InvalidServerUrlException(R.string.msg_http_opt_in)
    if (url.username.isNotEmpty() || url.password.isNotEmpty())
        throw InvalidServerUrlException(R.string.msg_url_credentials)
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
        if (secret.isBlank()) throw InvalidAuthInputException(if (useToken) R.string.msg_token_required else R.string.msg_password_required)
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
        if (token.isBlank() || !token.all { it in '!'..'~' }) throw InvalidAuthInputException(R.string.msg_invalid_token)
        TriliumApi(normalized, token, client).validateSession()
        TriliumSession(UUID.randomUUID().toString(), accountIdFor(normalized), normalized, token, !useToken)
    }
}

fun authFailureMessage(context: Context, error: Throwable): String {
    fun text(id: Int, vararg args: Any) = AppLanguage.get(context, id, *args)
    return when (error) {
    is InvalidServerUrlException -> text(error.messageId)
    is ApiException -> when (error.status) {
        400, 401 -> text(R.string.msg_invalid_credentials)
        403 -> text(R.string.msg_auth_forbidden)
        404, 405 -> text(R.string.msg_etapi_unavailable)
        in 300..399 -> text(R.string.msg_auth_redirect)
        else -> text(R.string.msg_auth_http, error.status)
    }
    is UnexpectedHtmlResponseException -> text(R.string.msg_auth_html)
    is InvalidAuthResponseException -> text(R.string.msg_auth_response)
    is SessionStorageException -> text(R.string.msg_auth_storage)
    is javax.net.ssl.SSLException -> text(R.string.msg_auth_tls)
    is java.net.UnknownHostException -> text(R.string.msg_auth_dns)
    is java.net.SocketTimeoutException -> text(R.string.msg_auth_timeout)
    is InvalidAuthInputException -> text(error.messageId)
    is LegacyImportException -> text(error.messageId, *error.arguments)
    is IllegalArgumentException -> text(R.string.msg_auth_input)
    is CancellationException -> text(R.string.msg_auth_cancelled)
    else -> text(R.string.msg_auth_connect)
}
}
