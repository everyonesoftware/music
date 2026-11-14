package com.everyonesoftware.audio.playback.output

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MediaOutputControllerTest {
    private val phone = OutputDevice("phone", "Phone", "This phone", OutputDeviceKind.PHONE)
    private val headphones = OutputDevice("bt", "Headphones", "Bluetooth audio", OutputDeviceKind.BLUETOOTH)
    private fun initial() = MediaOutputState(listOf(phone, headphones), phone.id)

    @Test fun transferWaitsForConfirmedBackendSelection() = runTest {
        val backend = FakeBackend(initial())
        val controller = MediaOutputController(backend, backgroundScope)
        controller.start()
        controller.select(headphones.id)
        assertEquals(phone, controller.state.value.selected)
        assertEquals(headphones.id, controller.state.value.pendingId)
        controller.select(headphones.id)
        controller.select(phone.id)
        assertEquals(listOf(headphones.id), backend.selections)
        backend.emit(initial().copy(selectedId = headphones.id))
        assertEquals(headphones, controller.state.value.selected)
        assertNull(controller.state.value.pendingId)
        controller.stop()
    }

    @Test fun transferTimesOutAfterTenSecondsWithoutChangingSelection() = runTest {
        val backend = FakeBackend(initial())
        val controller = MediaOutputController(backend, backgroundScope)
        controller.start()
        controller.select(headphones.id)
        runCurrent()
        advanceTimeBy(9_999)
        runCurrent()
        assertEquals(headphones.id, controller.state.value.pendingId)
        advanceTimeBy(1)
        runCurrent()
        assertNull(controller.state.value.pendingId)
        assertEquals(phone.id, controller.state.value.selectedId)
        assertNotNull(controller.state.value.error)
        controller.stop()
    }

    @Test fun lateFailureForTimedOutRouteDoesNotCancelNewPendingTransfer() = runTest {
        val outputA = headphones
        val outputB = OutputDevice("speaker", "Speaker", "Bluetooth audio", OutputDeviceKind.SPEAKER)
        val backend = FakeBackend(MediaOutputState(listOf(phone, outputA, outputB), phone.id))
        val controller = MediaOutputController(backend, backgroundScope)
        controller.start()
        controller.select(outputA.id)
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        assertNull(controller.state.value.pendingId)
        assertNotNull(controller.state.value.error)

        controller.select(outputB.id)
        assertEquals(outputB.id, controller.state.value.pendingId)
        assertNull(controller.state.value.error)
        backend.fail(outputA.id)
        assertEquals(outputB.id, controller.state.value.pendingId)
        assertNull(controller.state.value.error)
        backend.fail(outputB.id)
        assertNull(controller.state.value.pendingId)
        assertEquals("Couldn't switch audio output. Try again.", controller.state.value.error)
        assertEquals(phone.id, controller.state.value.selectedId)
        controller.stop()
    }

    @Test fun disconnectCancelsPendingAndUsesConfirmedFallback() = runTest {
        val backend = FakeBackend(initial())
        val controller = MediaOutputController(backend, backgroundScope)
        controller.start()
        controller.select(headphones.id)
        backend.emit(MediaOutputState(listOf(phone), phone.id))
        assertNull(controller.state.value.pendingId)
        assertEquals(phone.id, controller.state.value.selectedId)
        assertNotNull(controller.state.value.error)
        controller.stop()
    }

    @Test fun missingAndBackendStaleRoutesAreRejected() = runTest {
        val backend = FakeBackend(initial())
        val controller = MediaOutputController(backend, backgroundScope)
        controller.start()
        controller.select("missing")
        assertTrue(backend.selections.isEmpty())
        assertNotNull(controller.state.value.error)
        backend.accept = false
        controller.select(headphones.id)
        assertNull(controller.state.value.pendingId)
        assertEquals(phone.id, controller.state.value.selectedId)
        assertNotNull(controller.state.value.error)
        controller.stop()
    }

    @Test fun failureAndExceptionKeepConfirmedSelection() = runTest {
        val backend = FakeBackend(initial())
        val controller = MediaOutputController(backend, backgroundScope)
        controller.start()
        controller.select(headphones.id)
        backend.fail(headphones.id)
        assertNull(controller.state.value.pendingId)
        assertNotNull(controller.state.value.error)
        backend.throwSelect = true
        controller.select(headphones.id)
        assertEquals(phone.id, controller.state.value.selectedId)
        assertNull(controller.state.value.pendingId)
        assertNotNull(controller.state.value.error)
        controller.stop()
    }

    @Test fun permissionDeniedAndSystemPickerNeverAttemptSelection() = runTest {
        val backend = FakeBackend(initial().copy(permissionRequired = true))
        val controller = MediaOutputController(backend, backgroundScope)
        controller.start()
        controller.select(headphones.id)
        assertTrue(backend.selections.isEmpty())
        assertEquals(phone.id, controller.state.value.selectedId)
        backend.emit(initial().copy(systemPickerRequired = true))
        controller.select(headphones.id)
        assertTrue(backend.selections.isEmpty())
        assertNull(controller.state.value.pendingId)
        controller.stop()
    }

    @Test fun lifecycleIsIdempotentAndIgnoresLateCallbacks() = runTest {
        val backend = FakeBackend(initial())
        val controller = MediaOutputController(backend, backgroundScope)
        controller.start()
        controller.start()
        assertEquals(1, backend.starts)
        controller.refresh()
        assertEquals(1, backend.refreshes)
        controller.select(headphones.id)
        val lateUpdate = backend.update
        val lateFailure = backend.failure
        controller.stop()
        controller.stop()
        assertEquals(1, backend.stops)
        assertNull(controller.state.value.pendingId)
        controller.start()
        lateUpdate(initial().copy(selectedId = headphones.id))
        lateFailure(headphones.id)
        assertEquals(phone.id, controller.state.value.selectedId)
        assertNull(controller.state.value.error)
        controller.stop()
        advanceTimeBy(20_000)
        runCurrent()
        assertNull(controller.state.value.error)
    }

    @Test fun synchronousConfirmationDoesNotLeavePendingTimeout() = runTest {
        val backend = FakeBackend(initial())
        val controller = MediaOutputController(backend, backgroundScope)
        controller.start()
        backend.onSelect = { backend.emit(initial().copy(selectedId = it)) }
        controller.select(headphones.id)
        assertNull(controller.state.value.pendingId)
        advanceTimeBy(20_000)
        runCurrent()
        assertNull(controller.state.value.error)
        controller.stop()
    }

    @Test fun backendLifecycleExceptionsAreContained() = runTest {
        val backend = FakeBackend(initial())
        backend.throwStart = true
        val controller = MediaOutputController(backend, backgroundScope)
        controller.start()
        assertNotNull(controller.state.value.error)
        backend.throwStart = false
        controller.start()
        backend.throwRefresh = true
        controller.refresh()
        assertNotNull(controller.state.value.error)
        backend.throwStop = true
        controller.stop()
        assertNull(controller.state.value.pendingId)
    }

    private class FakeBackend(var snapshot: MediaOutputState) : MediaOutputBackend {
        var starts = 0
        var stops = 0
        var refreshes = 0
        var accept = true
        var throwSelect = false
        var throwStart = false
        var throwRefresh = false
        var throwStop = false
        var onSelect: ((String) -> Unit)? = null
        val selections = mutableListOf<String>()
        lateinit var update: (MediaOutputState) -> Unit
        lateinit var failure: (String) -> Unit
        override fun start(onUpdate: (MediaOutputState) -> Unit, onFailure: (String) -> Unit) {
            starts++
            update = onUpdate
            failure = onFailure
            if (throwStart) error("start failed")
            emit(snapshot)
        }
        override fun refresh() { refreshes++; if (throwRefresh) error("refresh failed") }
        override fun select(id: String): Boolean {
            selections += id
            if (throwSelect) throw SecurityException("permission revoked")
            onSelect?.invoke(id)
            return accept
        }
        override fun stop() { stops++; if (throwStop) error("stop failed") }
        fun emit(state: MediaOutputState) { snapshot = state; update(state) }
        fun fail(id: String) = failure(id)
    }
}
