package ru.vlad.triliumnotes.data

import org.json.JSONException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Never includes exception messages: they can contain URLs or note contents. */
internal fun loadFailureMessage(error: Throwable): String {
    val causes = generateSequence(error) { it.cause }.take(8).toList()
    if (causes.any { it is HiddenNoteException }) return "Системные заметки скрыты"
    if (causes.any { it is UnexpectedHtmlResponseException }) {
        return "Сервер вернул веб-страницу вместо данных (возможно, CAPTCHA)."
    }
    causes.filterIsInstance<ApiException>().firstOrNull()?.let {
        return when (it.status) {
            401, 403 -> "Сервер отклонил доступ (HTTP ${it.status})."
            404 -> "Запрошенная заметка или папка не найдена (HTTP 404)."
            429 -> "Слишком много запросов к серверу (HTTP 429). Повторите чуть позже."
            else -> "Сервер вернул ошибку HTTP ${it.status}."
        }
    }
    return when {
        causes.any { it is UnknownHostException } ->
            "Не удалось найти адрес Trilium (DNS). Проверьте подключение."
        causes.any { it is SSLException } ->
            "Не удалось установить защищённое соединение с Trilium (TLS)."
        causes.any { it is InterruptedIOException } ->
            "Trilium не ответил вовремя. Повторите загрузку."
        causes.any { it is ConnectException } ->
            "Не удалось подключиться к Trilium. Повторите загрузку."
        causes.any { it is JSONException } ->
            "Не удалось прочитать данные Trilium (JSON)."
        else -> {
            val code = causes.last().javaClass.simpleName
                .takeIf { it.matches(Regex("[A-Za-z0-9_]{1,60}")) } ?: "UnknownError"
            "Не удалось загрузить данные. Код ошибки: $code."
        }
    }
}
