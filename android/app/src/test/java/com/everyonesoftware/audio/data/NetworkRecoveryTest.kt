package com.everyonesoftware.audio.data

import org.junit.Assert.*
import org.junit.Test

class NetworkRecoveryTest {
    @Test fun onlyValidatedNetworkTransitionsTriggerRecovery() {
        var calls = 0
        val recovery = NetworkRecovery { calls++ }
        val report = recovery.start()
        report("wifi", false)
        assertEquals(0, calls)
        report("wifi", true)
        report("wifi", true)
        assertEquals(1, calls)
        report("cell", true)
        report("wifi", false)
        report("cell", true)
        assertEquals("A late loss of the old route must not invalidate the new route", 2, calls)
        report("cell", false)
        report("cell", true)
        assertEquals(3, calls)
    }

    @Test fun stoppedAndPreviousRegistrationCallbacksCannotRecoverANewForeground() {
        var calls = 0
        val recovery = NetworkRecovery { calls++ }
        val old = recovery.start()
        old("wifi", true)
        recovery.stop()
        old("cell", true)
        assertEquals(1, calls)
        val current = recovery.start()
        old("cell", true)
        assertEquals(1, calls)
        current("wifi", true)
        assertEquals(2, calls)
    }
}
