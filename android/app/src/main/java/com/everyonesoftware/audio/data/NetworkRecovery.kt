package com.everyonesoftware.audio.data

/** Main-thread foreground gate for validated default-network transitions. */
internal class NetworkRecovery(private val recovered: () -> Unit) {
    private var generation = 0L
    private var validatedNetwork: Any? = null

    fun start(): (Any, Boolean) -> Unit {
        val ticket = ++generation
        validatedNetwork = null
        return { network, validated ->
            if (ticket == generation) {
                if (validated && validatedNetwork != network) {
                    validatedNetwork = network
                    recovered()
                } else if (!validated && validatedNetwork == network) {
                    validatedNetwork = null
                }
            }
        }
    }

    fun stop() { generation++; validatedNetwork = null }
}
