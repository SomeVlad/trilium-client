package ru.vlad.triliumnotes.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class LoadFailureMessageTest {
    @Test fun wrappedHttpFailureRetainsStatus() {
        val message = loadFailureMessage(IOException("hidden", ApiException(401)))
        assertTrue(message.contains("HTTP 401"))
        assertFalse(message.contains("hidden"))
    }

    @Test fun networkFailuresAreDistinguished() {
        assertTrue(loadFailureMessage(IOException(UnknownHostException("private-host"))).contains("DNS"))
        assertTrue(loadFailureMessage(IOException(SocketTimeoutException("private-url"))).contains("вовремя"))
    }

    @Test fun unknownFailureDoesNotExposePrivateContent() {
        val message = loadFailureMessage(IOException("private-token", IllegalStateException("private-note")))
        assertEquals("Не удалось загрузить данные. Код ошибки: IllegalStateException.", message)
        assertFalse(message.contains("private"))
    }

    @Test fun htmlChallengeGetsCaptchaHintWithoutResponseBody() {
        val message = loadFailureMessage(
            IOException("private response body", UnexpectedHtmlResponseException(200))
        )
        assertTrue(message.contains("CAPTCHA"))
        assertFalse(message.contains("private"))
    }
}
