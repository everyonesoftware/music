package com.everyonesoftware.audio.playback.output

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class OutputDeviceKind { PHONE, HEADPHONES, BLUETOOTH, SPEAKER, USB, OTHER }

data class OutputDevice(val id: String, val name: String, val subtitle: String, val kind: OutputDeviceKind)

data class MediaOutputState(
    val devices: List<OutputDevice> = emptyList(),
    val selectedId: String? = null,
    val pendingId: String? = null,
    val permissionRequired: Boolean = false,
    val systemPickerRequired: Boolean = false,
    val error: String? = null,
) {
    val selected: OutputDevice? get() = devices.firstOrNull { it.id == selectedId }
}

interface MediaOutputBackend {
    fun start(onUpdate: (MediaOutputState) -> Unit, onFailure: (String) -> Unit)
    fun refresh()
    fun select(id: String): Boolean
    fun stop()
}

/** Invoke on the same foreground/main dispatcher used by the backend callbacks. */
class MediaOutputController(private val backend: MediaOutputBackend, private val scope: CoroutineScope) {
    private val mutableState = MutableStateFlow(MediaOutputState())
    val state: StateFlow<MediaOutputState> = mutableState.asStateFlow()
    private var started = false
    private var generation = 0L
    private var timeout: Job? = null

    fun start() {
        if (started) return
        started = true
        val session = ++generation
        try {
            backend.start(
                onUpdate = { snapshot -> if (started && generation == session) accept(snapshot) },
                onFailure = { failedRouteId ->
                    if (started && generation == session && state.value.pendingId == failedRouteId) {
                        fail("Couldn't switch audio output. Try again.")
                    }
                },
            )
        } catch (_: Exception) {
            started = false
            generation++
            runCatching { backend.stop() }
            fail("Media outputs are unavailable. Try again.")
        }
    }

    fun refresh() {
        if (!started) return
        try { backend.refresh() } catch (_: Exception) { fail("Couldn't refresh audio outputs. Try again.") }
    }

    fun select(id: String) {
        if (!started || state.value.pendingId != null) return
        val current = state.value
        if (current.permissionRequired) {
            fail("Allow Nearby devices access to switch audio output.")
            return
        }
        if (current.systemPickerRequired) {
            fail("Use system audio settings to switch output.")
            return
        }
        if (current.devices.none { it.id == id }) {
            fail("That audio output is no longer available.")
            return
        }
        if (id == current.selectedId) return
        mutableState.value = current.copy(pendingId = id, error = null)
        try {
            if (!backend.select(id)) {
                fail("That audio output is no longer available. Try again.")
                return
            }
        } catch (_: Exception) {
            fail("Couldn't switch audio output. Try again.")
            return
        }
        // A backend may confirm synchronously; do not resurrect an already finished request.
        if (state.value.pendingId != id) return
        timeout = scope.launch {
            delay(10_000)
            if (state.value.pendingId == id) fail("Output switch timed out. Try again.")
        }
    }

    fun stop() {
        if (!started) return
        started = false
        generation++
        clearTimeout()
        mutableState.value = state.value.copy(pendingId = null)
        runCatching { backend.stop() }
    }

    private fun accept(snapshot: MediaOutputState) {
        val pending = state.value.pendingId
        val missing = pending != null && snapshot.devices.none { it.id == pending }
        val finished = pending != null && (snapshot.selectedId == pending || missing ||
            snapshot.permissionRequired || snapshot.systemPickerRequired || snapshot.error != null)
        if (finished) clearTimeout()
        mutableState.value = snapshot.copy(
            pendingId = if (finished) null else pending,
            error = if (missing) "That audio output disconnected." else snapshot.error,
        )
    }

    private fun fail(message: String) {
        clearTimeout()
        mutableState.value = state.value.copy(pendingId = null, error = message)
    }

    private fun clearTimeout() {
        timeout?.cancel()
        timeout = null
    }
}
