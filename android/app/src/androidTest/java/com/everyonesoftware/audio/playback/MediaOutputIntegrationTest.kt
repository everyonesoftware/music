package com.everyonesoftware.audio.playback

import android.Manifest
import android.app.Application
import android.os.Build
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.everyonesoftware.audio.AudioGraph
import com.everyonesoftware.audio.PlaybackSnapshot
import com.everyonesoftware.audio.data.*
import com.everyonesoftware.audio.playback.output.*
import com.everyonesoftware.audio.ui.AudioViewModel
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaOutputIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun until(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20)
        while (!predicate()) { check(System.nanoTime() < deadline) { "Timed out waiting for output state" }; Thread.sleep(30) }
    }
    private val permissions = listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)

    @Test fun realPlatformReportsCurrentRouteAndRefreshesWithoutInventingDevices() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var controller: MediaOutputController
        try {
            onMain { controller = MediaOutputController(AndroidMediaOutputBackend(app), scope); controller.start() }
            until { controller.state.value.selected != null }
            val snapshot = controller.state.value
            assertTrue(snapshot.selected!!.name.isNotBlank())
            assertTrue(snapshot.devices.any { it.id == snapshot.selectedId })
            assertEquals(Build.VERSION.SDK_INT < 34, snapshot.systemPickerRequired)
            onMain { controller.refresh() }
            until { controller.state.value.selected != null }
            assertNull(controller.state.value.pendingId)
            assertNull(controller.state.value.error)
        } finally { onMain { controller.stop(); scope.cancel() } }
    }

    @Test fun modernPlatformPermissionDenialAndGrantAreReflectedLive() {
        assumeTrue(Build.VERSION.SDK_INT >= 34)
        // Run on a fresh task-owned emulator. Revoking our own permission here would kill instrumentation.
        assumeTrue(permissions.any { ContextCompat.checkSelfPermission(app, it) != PackageManager.PERMISSION_GRANTED })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var controller: MediaOutputController
        var initialized = false
        try {
            onMain { controller = MediaOutputController(AndroidMediaOutputBackend(app), scope); initialized = true; controller.start() }
            until { controller.state.value.permissionRequired }
            assertNull(controller.state.value.pendingId)
            permissions.forEach { instrumentation.uiAutomation.grantRuntimePermission(app.packageName, it) }
            onMain { controller.refresh() }
            until { !controller.state.value.permissionRequired && controller.state.value.selected != null }
            assertFalse(controller.state.value.systemPickerRequired)
            assertNull(controller.state.value.error)
        } finally {
            onMain { if (initialized) controller.stop(); scope.cancel() }

        }
    }

    @Test fun viewModelRoutesConfirmedDeviceChangesWithoutReplacingOrRestartingSession() = runBlocking {
        val graph = AudioGraph(app)
        val id = "output-viewmodel-fixture"
        val owner = ViewModelStore()
        val backend = FixtureBackend()
        val queue = ListeningSession(id, "output-playlist", "Output fixture", listOf(Song("one", "First", "Fixture"), Song("two", "Second", "Fixture")), index = 1, positionMs = 24000)
        lateinit var model: AudioViewModel
        try {
            graph.store.saveConnection(Credentials(Connection(id, "Output fixture", "https://example.invalid", "output-fixture"), "test-only-password"))
            graph.store.saveSession(queue)
            // Keep the graph initialized before constructing the model: startup may complete
            // inline on Main.immediate, so its refresh must not join an unassigned Job.
            graph.activate(id)
            graph.playback.value = PlaybackSnapshot(playing = true, positionMs = queue.positionMs, durationMs = 120000)
            onMain { model = AudioViewModel(app, graph, false, backend); owner.put("output", model) }
            until { !model.state.value.booting }
            onMain { model.onForeground() }
            until { model.state.value.mediaOutput.selectedId == "phone" }
            onMain { model.selectMediaOutput("headphones") }
            until { model.state.value.mediaOutput.pendingId == "headphones" }
            assertEquals("phone", model.state.value.mediaOutput.selectedId)
            onMain { backend.confirm("headphones") }
            until { model.state.value.mediaOutput.selectedId == "headphones" }
            assertEquals(listOf("headphones"), backend.selected)
            assertEquals(queue, model.state.value.session)
            assertEquals(queue, graph.session.value)
            assertTrue(model.state.value.playing)
            assertEquals(24000L, model.state.value.positionMs)
            onMain { model.onBackground() }
            assertEquals(1, backend.stops)
            onMain { model.onForeground() }
            assertEquals(2, backend.starts)
        } finally { onMain { owner.clear() }; graph.store.removeConnection(id) }
    }

    private class FixtureBackend : MediaOutputBackend {
        val selected = mutableListOf<String>()
        var starts = 0
        var stops = 0
        private var update: ((MediaOutputState) -> Unit)? = null
        private var snapshot = MediaOutputState(listOf(
            OutputDevice("phone", "This phone", "Built-in speaker", OutputDeviceKind.PHONE),
            OutputDevice("headphones", "Fixture headphones", "Bluetooth", OutputDeviceKind.HEADPHONES)), "phone")
        override fun start(onUpdate: (MediaOutputState) -> Unit, onFailure: (String) -> Unit) { starts++; update = onUpdate; onUpdate(snapshot) }
        override fun refresh() { update?.invoke(snapshot) }
        override fun select(id: String): Boolean { selected += id; return true }
        override fun stop() { stops++; update = null }
        fun confirm(id: String) { snapshot = snapshot.copy(selectedId = id); update?.invoke(snapshot) }
    }
}
