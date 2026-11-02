package io.github.zyraxi21.nfc.cast

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionMessagesTest {
    @Test fun leavingThePageCancelsSuccessAndDoesNotReplayBackgroundDisconnection() = runTest {
        val shown = mutableListOf<String>()
        var visible: String? = null
        val messages = ConnectionMessages(backgroundScope) { message ->
            shown += message; visible = message
            try { awaitCancellation() } finally { visible = null }
        }
        messages.resume(); messages.show("connected"); runCurrent()
        assertEquals("connected", visible)
        messages.pause(); messages.show("disconnected"); messages.resume(); runCurrent()
        assertNull(visible)
        assertEquals(listOf("connected"), shown)
        messages.show("new connection"); runCurrent()
        assertEquals("new connection", visible)
    }

    @Test fun foregroundStatusChangesReplacePreviousMessagesRatherThanQueueing() = runTest {
        var visible: String? = null
        val messages = ConnectionMessages(backgroundScope) { message ->
            visible = message
            try { awaitCancellation() } finally { visible = null }
        }
        messages.resume(); messages.show("connecting"); runCurrent()
        messages.show("connected"); runCurrent()
        assertEquals("connected", visible)
        messages.show("disconnected"); runCurrent()
        assertEquals("disconnected", visible)
        messages.pause(); runCurrent()
        assertNull(visible)
    }

    @Test fun activityScopeCancellationAlsoCancelsTheVisibleMessage() = runTest {
        var visible = false
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val messages = ConnectionMessages(scope) {
            visible = true
            try { awaitCancellation() } finally { visible = false }
        }
        messages.resume(); messages.show("connected"); runCurrent()
        assertTrue(visible)
        scope.cancel(); runCurrent()
        assertFalse(visible)
    }
}
