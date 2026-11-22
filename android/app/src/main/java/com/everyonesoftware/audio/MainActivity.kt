package com.everyonesoftware.audio

import android.graphics.Color
import android.content.Intent
import android.app.SearchManager
import android.os.Bundle
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import com.everyonesoftware.audio.ui.AudioApp
import com.everyonesoftware.audio.ui.AudioTheme
import com.everyonesoftware.audio.ui.AudioViewModel
import com.everyonesoftware.audio.data.NetworkRecovery

class MainActivity : ComponentActivity() {
    companion object {
        const val ACTION_OPEN_PLAYER = "com.everyonesoftware.audio.action.OPEN_PLAYER"
    }

    private val model: AudioViewModel by viewModels()
    private val networkRecovery = NetworkRecovery { model.onNetworkRecovered() }
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model.onLaunch()
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            val artwork = remember(state.connection, state.artworkRevision) { { id: String? -> model.artworkUrl(id) } }
            AudioTheme { AudioApp(state, model, artwork) }
        }
        if (savedInstanceState == null) handlePlaybackIntent(intent)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                model.installIntents.collect { request ->
                    if (model.allowInstallerLaunch(request)) {
                        try { startActivity(request) }
                        catch (_: android.content.ActivityNotFoundException) { model.installerLaunchFailed() }
                        catch (_: SecurityException) { model.installerLaunchFailed() }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePlaybackIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        model.onForeground()
        observeNetworkRecovery()
    }

    override fun onStop() {
        networkRecovery.stop()
        networkCallback?.let { callback ->
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
        }
        networkCallback = null
        model.onBackground()
        super.onStop()
    }

    private fun observeNetworkRecovery() {
        if (networkCallback != null) return
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        val report = networkRecovery.start()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                runOnUiThread { report(network, validated) }
            }
            override fun onLost(network: Network) { runOnUiThread { report(network, false) } }
        }
        try {
            manager.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (_: SecurityException) {
            networkRecovery.stop()
        }
    }

    private fun handlePlaybackIntent(intent: Intent) {
        when (intent.action) {
            ACTION_OPEN_PLAYER -> model.openPlayer()
            "android.media.action.MEDIA_PLAY_FROM_SEARCH" -> model.playFromSearch(intent.getStringExtra(SearchManager.QUERY).orEmpty())
        }
    }
}
