package com.th3web.lean.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TunnelHealthCheckTest {

    @Test
    fun `a passing probe asks for nothing`() {
        val health = TunnelHealthCheck()

        assertEquals(TunnelHealthCheck.Action.None, health.onProbe(ok = true))
        assertEquals(0, health.failures)
    }

    @Test
    fun `one failure is not enough to touch the tunnel`() {
        val health = TunnelHealthCheck()

        assertEquals(TunnelHealthCheck.Action.None, health.onProbe(ok = false))
    }

    @Test
    fun `two failures in a row reset the connections`() {
        val health = TunnelHealthCheck()

        health.onProbe(ok = false)

        assertEquals(TunnelHealthCheck.Action.Reset, health.onProbe(ok = false))
    }

    @Test
    fun `the reset is asked for once, not on every later failure`() {
        val health = TunnelHealthCheck()

        health.onProbe(ok = false)
        assertEquals(TunnelHealthCheck.Action.Reset, health.onProbe(ok = false))

        assertEquals(TunnelHealthCheck.Action.None, health.onProbe(ok = false))
    }

    @Test
    fun `a tunnel that stays silent after the reset is restarted`() {
        val health = TunnelHealthCheck()

        repeat(3) { health.onProbe(ok = false) }

        assertEquals(TunnelHealthCheck.Action.Restart, health.onProbe(ok = false))
    }

    @Test
    fun `a restart starts the evidence over, so the new session is judged on its own`() {
        val health = TunnelHealthCheck()
        repeat(4) { health.onProbe(ok = false) }

        assertEquals(0, health.failures)
        // The very next failure must not escalate straight back to a restart.
        assertEquals(TunnelHealthCheck.Action.None, health.onProbe(ok = false))
    }

    @Test
    fun `a single success clears the history`() {
        val health = TunnelHealthCheck()
        health.onProbe(ok = false)

        health.onProbe(ok = true)

        // Were the failure still counted, this one would reset the connections.
        assertEquals(TunnelHealthCheck.Action.None, health.onProbe(ok = false))
    }

    @Test
    fun `reset forgets the history too`() {
        val health = TunnelHealthCheck()
        health.onProbe(ok = false)

        health.reset()

        assertEquals(TunnelHealthCheck.Action.None, health.onProbe(ok = false))
    }

    @Test
    fun `the escalation thresholds are the ones the service relies on`() {
        // The ladder is only meaningful if "reset" comes strictly before "restart" and
        // both are reachable: a misordering would make the invisible fix unreachable and
        // every silent tunnel would be answered with a visible reconnect.
        assertEquals(2, TunnelHealthCheck.FAILURES_BEFORE_RESET)
        assertEquals(4, TunnelHealthCheck.FAILURES_BEFORE_RESTART)
    }
}
