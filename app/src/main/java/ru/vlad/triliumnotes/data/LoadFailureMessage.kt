package ru.vlad.triliumnotes.data

import ru.vlad.triliumnotes.AppLanguage
import ru.vlad.triliumnotes.R
import android.content.Context
import org.json.JSONException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Never includes exception messages: they can contain URLs or note contents. */
internal fun loadFailureMessage(context: Context, error: Throwable): String {
    fun text(id: Int, vararg args: Any) = AppLanguage.get(context, id, *args)
    val causes = generateSequence(error) { it.cause }.take(8).toList()
    if (causes.any { it is HiddenNoteException }) return text(R.string.msg_hidden_notes)
    if (causes.any { it is UnexpectedHtmlResponseException }) {
        return text(R.string.msg_html_response)
    }
    causes.filterIsInstance<ApiException>().firstOrNull()?.let {
        return when (it.status) {
            401, 403 -> text(R.string.msg_access_rejected, it.status)
            404 -> text(R.string.msg_note_not_found)
            429 -> text(R.string.msg_too_many_requests)
            else -> text(R.string.msg_http_error, it.status)
        }
    }
    return when {
        causes.any { it is UnknownHostException } ->
            text(R.string.msg_dns_failed)
        causes.any { it is SSLException } ->
            text(R.string.msg_tls_failed)
        causes.any { it is InterruptedIOException } ->
            text(R.string.msg_load_timeout)
        causes.any { it is ConnectException } ->
            text(R.string.msg_load_connect_failed)
        causes.any { it is JSONException } ->
            text(R.string.msg_invalid_json)
        else -> {
            val code = causes.last().javaClass.simpleName
                .takeIf { it.matches(Regex("[A-Za-z0-9_]{1,60}")) } ?: "UnknownError"
            text(R.string.msg_load_failed, code)
        }
    }
}
