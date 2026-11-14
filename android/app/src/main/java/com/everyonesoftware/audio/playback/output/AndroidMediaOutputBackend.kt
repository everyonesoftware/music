package com.everyonesoftware.audio.playback.output

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRoute2Info
import android.media.MediaRouter
import android.media.MediaRouter2
import android.media.RouteDiscoveryPreference
import android.os.Build
import androidx.annotation.RequiresApi

/** Foreground-only observer. All methods must be called on the main thread. */
class AndroidMediaOutputBackend(context: Context) : MediaOutputBackend {
    private val context = context.applicationContext
    private var delegate: MediaOutputBackend? = null
    private var update: ((MediaOutputState) -> Unit)? = null
    private var failure: ((String) -> Unit)? = null

    override fun start(onUpdate: (MediaOutputState) -> Unit, onFailure: (String) -> Unit) {
        if (update != null) return
        update = onUpdate
        failure = onFailure
        connect()
    }

    private fun connect() {
        val onUpdate = update ?: return
        val onFailure = failure ?: return
        try {
            val backend = if (Build.VERSION.SDK_INT >= 34) ModernBackend(context) else LegacyBackend(context)
            delegate = backend
            backend.start(onUpdate, onFailure)
        } catch (_: Exception) {
            runCatching { delegate?.stop() }
            delegate = null
            onUpdate(MediaOutputState(
                permissionRequired = Build.VERSION.SDK_INT >= 34 && !hasRoutingPermissions(context),
                systemPickerRequired = true,
                error = "Media outputs are unavailable. Use system audio settings or try again.",
            ))
        }
    }

    override fun refresh() {
        if (update == null) return
        // Permission changes do not necessarily update an existing router registration.
        // Remove every callback before re-registering the app router with its new permissions.
        delegate?.stop()
        delegate = null
        connect()
    }

    override fun select(id: String): Boolean = delegate?.select(id) ?: false

    override fun stop() {
        update = null
        failure = null
        delegate?.stop()
        delegate = null
    }
}

@RequiresApi(34)
private fun hasRoutingPermissions(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED

@RequiresApi(34)
private class ModernBackend(private val context: Context) : MediaOutputBackend {
    private val router = MediaRouter2.getInstance(context)
    private var active = false
    private var update: ((MediaOutputState) -> Unit)? = null
    private var failure: ((String) -> Unit)? = null
    private val routes = object : MediaRouter2.RouteCallback() {
        override fun onRoutesUpdated(routes: List<MediaRoute2Info>) = publish()
    }
    private val transfers = object : MediaRouter2.TransferCallback() {
        override fun onTransfer(oldController: MediaRouter2.RoutingController, newController: MediaRouter2.RoutingController) = publish()
        override fun onStop(controller: MediaRouter2.RoutingController) = publish()
        override fun onTransferFailure(route: MediaRoute2Info) {
            if (!active) return
            publish()
            failure?.invoke(route.id)
        }
    }
    private val controllers = object : MediaRouter2.ControllerCallback() {
        override fun onControllerUpdated(controller: MediaRouter2.RoutingController) = publish()
    }

    override fun start(onUpdate: (MediaOutputState) -> Unit, onFailure: (String) -> Unit) {
        if (active) return
        active = true
        update = onUpdate
        failure = onFailure
        router.registerRouteCallback(context.mainExecutor, routes, RouteDiscoveryPreference.Builder(
            listOf(MediaRoute2Info.FEATURE_LIVE_AUDIO, LOCAL_PLAYBACK_FEATURE), false,
        ).build())
        router.registerTransferCallback(context.mainExecutor, transfers)
        router.registerControllerCallback(context.mainExecutor, controllers)
        publish()
    }

    override fun refresh() = publish()

    override fun select(id: String): Boolean {
        if (!active || !hasRoutingPermissions(context)) return false
        // Do not retain callback snapshots: framework routes can change before delivery.
        val route = router.routes.firstOrNull { it.isSystemRoute && it.id == id } ?: return false
        router.transferTo(route)
        // A request is not confirmation. Only selectedRoutes read from callbacks confirms it.
        return true
    }

    private fun publish() {
        if (!active) return
        try {
            val allowed = hasRoutingPermissions(context)
            val selected = router.systemController.selectedRoutes.filter { it.isSystemRoute }
            val visible = if (allowed) {
                (router.routes.filter { it.isSystemRoute } + selected).distinctBy { it.id }
            } else {
                // Unprivileged app routers expose a single default route, not real choices.
                selected.take(1)
            }
            update?.invoke(MediaOutputState(
                devices = visible.map { it.toOutputDevice() },
                selectedId = selected.firstOrNull()?.id,
                permissionRequired = !allowed,
            ))
        } catch (_: Exception) {
            update?.invoke(MediaOutputState(
                permissionRequired = !hasRoutingPermissions(context),
                systemPickerRequired = true,
                error = "Couldn't read audio outputs. Use system audio settings or try again.",
            ))
        }
    }

    override fun stop() {
        if (!active) return
        active = false
        update = null
        failure = null
        // Unregister separately so an OEM exception cannot leak the other callbacks.
        runCatching { router.unregisterRouteCallback(routes) }
        runCatching { router.unregisterTransferCallback(transfers) }
        runCatching { router.unregisterControllerCallback(controllers) }
    }

    companion object {
        // AOSP's LOCAL_PLAYBACK feature is a string protocol value, not a public SDK field.
        // No hidden API access/reflection: RouteDiscoveryPreference accepts arbitrary strings.
        private const val LOCAL_PLAYBACK_FEATURE = "android.media.route.feature.LOCAL_PLAYBACK"
    }
}

@RequiresApi(34)
private fun MediaRoute2Info.toOutputDevice(): OutputDevice {
    val kind = when (type) {
        MediaRoute2Info.TYPE_BUILTIN_SPEAKER -> OutputDeviceKind.PHONE
        MediaRoute2Info.TYPE_WIRED_HEADSET, MediaRoute2Info.TYPE_WIRED_HEADPHONES -> OutputDeviceKind.HEADPHONES
        MediaRoute2Info.TYPE_BLUETOOTH_A2DP, MediaRoute2Info.TYPE_BLE_HEADSET,
        MediaRoute2Info.TYPE_HEARING_AID -> OutputDeviceKind.BLUETOOTH
        MediaRoute2Info.TYPE_USB_DEVICE, MediaRoute2Info.TYPE_USB_ACCESSORY,
        MediaRoute2Info.TYPE_USB_HEADSET -> OutputDeviceKind.USB
        MediaRoute2Info.TYPE_DOCK, MediaRoute2Info.TYPE_HDMI, MediaRoute2Info.TYPE_HDMI_ARC,
        MediaRoute2Info.TYPE_HDMI_EARC, MediaRoute2Info.TYPE_LINE_ANALOG,
        MediaRoute2Info.TYPE_LINE_DIGITAL, MediaRoute2Info.TYPE_AUX_LINE -> OutputDeviceKind.SPEAKER
        else -> OutputDeviceKind.OTHER
    }
    val subtitle = when (kind) {
        OutputDeviceKind.PHONE -> "This phone"
        OutputDeviceKind.HEADPHONES -> "Wired headphones"
        OutputDeviceKind.BLUETOOTH -> "Bluetooth audio"
        OutputDeviceKind.USB -> "USB audio"
        OutputDeviceKind.SPEAKER -> "Connected audio output"
        OutputDeviceKind.OTHER -> description?.toString()?.takeIf { it.isNotBlank() } ?: "System audio output"
    }
    return OutputDevice(id, name.toString(), subtitle, kind)
}

@Suppress("DEPRECATION")
private class LegacyBackend(context: Context) : MediaOutputBackend {
    private val router = context.getSystemService(Context.MEDIA_ROUTER_SERVICE) as MediaRouter
    private var active = false
    private var update: ((MediaOutputState) -> Unit)? = null
    private val callback = object : MediaRouter.SimpleCallback() {
        override fun onRouteSelected(router: MediaRouter, type: Int, info: MediaRouter.RouteInfo) = publish()
        override fun onRouteUnselected(router: MediaRouter, type: Int, info: MediaRouter.RouteInfo) = publish()
        override fun onRouteAdded(router: MediaRouter, info: MediaRouter.RouteInfo) = publish()
        override fun onRouteRemoved(router: MediaRouter, info: MediaRouter.RouteInfo) = publish()
        override fun onRouteChanged(router: MediaRouter, info: MediaRouter.RouteInfo) = publish()
    }

    override fun start(onUpdate: (MediaOutputState) -> Unit, onFailure: (String) -> Unit) {
        if (active) return
        active = true
        update = onUpdate
        // No PERFORM_ACTIVE_SCAN flag: framework callback never requests active scans.
        router.addCallback(MediaRouter.ROUTE_TYPE_LIVE_AUDIO, callback, 0)
        publish()
    }
    override fun refresh() = publish()
    override fun select(id: String): Boolean = false // Never use legacy optimistic selectRoute.

    private fun publish() {
        if (!active) return
        try {
            val route = router.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO)
            val kind = when (route.deviceType) {
                MediaRouter.RouteInfo.DEVICE_TYPE_BLUETOOTH -> OutputDeviceKind.BLUETOOTH
                MediaRouter.RouteInfo.DEVICE_TYPE_SPEAKER -> OutputDeviceKind.SPEAKER
                else -> OutputDeviceKind.OTHER
            }
            val device = OutputDevice(
                "legacy:${System.identityHashCode(route)}", route.name.toString(),
                if (kind == OutputDeviceKind.BLUETOOTH) "Bluetooth audio" else "Current system audio output", kind,
            )
            update?.invoke(MediaOutputState(listOf(device), device.id, systemPickerRequired = true))
        } catch (_: Exception) {
            update?.invoke(MediaOutputState(systemPickerRequired = true, error = "Couldn't read the current audio output."))
        }
    }

    override fun stop() {
        if (!active) return
        active = false
        update = null
        runCatching { router.removeCallback(callback) }
    }
}
