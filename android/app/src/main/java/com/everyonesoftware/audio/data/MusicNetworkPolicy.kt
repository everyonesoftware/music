package com.everyonesoftware.audio.data

import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import java.io.IOException

/** One gate for all music-server traffic, including response bodies already in flight. */
class MusicNetworkPolicy {
    private val lock = Any()
    private val calls = mutableSetOf<Call>()
    @Volatile private var disabled = false
    var offline: Boolean
        get() = disabled
        set(value) = synchronized(lock) {
            disabled = value
            if (value) { calls.toList().forEach(Call::cancel); calls.clear() }
        }
    fun guard(client: OkHttpClient): OkHttpClient = client.newBuilder()
        .eventListener(object : EventListener() {
            override fun callStart(call: Call) = synchronized(lock) {
                if (disabled) call.cancel() else { calls.add(call); Unit }
            }
            override fun callEnd(call: Call) { synchronized(lock) { calls.remove(call) } }
            override fun callFailed(call: Call, ioe: IOException) { synchronized(lock) { calls.remove(call) } }
        })
        .addInterceptor { chain ->
            if (disabled) throw IOException("Offline mode is enabled.")
            chain.proceed(chain.request())
        }.build()
}
