package ru.vlad.triliumnotes

import org.junit.Assert.*
import org.junit.Test

class ConnectionStateTest {
    @Test fun capabilityNoiseDoesNotRestartRefreshButReconnectionDoes() {
        val connection = ConnectionState()
        assertFalse(connection.update(10, false))
        assertTrue(connection.update(10, true))
        repeat(8) { assertFalse(connection.update(10, true)) }
        assertFalse(connection.update(10, false))
        assertTrue(connection.update(10, true))
        assertFalse(connection.update(10, true))
    }

    @Test fun WifiToMobileHandoffAndLateOldLossDoNotInvalidateNewConnection() {
        val connection = ConnectionState()
        assertTrue(connection.update(10, true))
        assertTrue(connection.update(20, true))
        assertFalse(connection.lost(10))
        assertFalse(connection.update(20, true))
        assertTrue(connection.lost(20))
        assertTrue(connection.update(20, true))
        connection.reset()
        assertTrue(connection.update(20, true))
    }

    @Test fun sameConnectionSurvivesRepeatedActivityStopsAndStarts() {
        val connection = ConnectionState()
        assertTrue(connection.update(10, true))
        // The callback now stays registered until onDestroy, retaining network identity.
        repeat(5) { assertFalse(connection.update(10, true)) }
        assertTrue(connection.lost(10))
        assertTrue(connection.update(10, true))
    }
}
