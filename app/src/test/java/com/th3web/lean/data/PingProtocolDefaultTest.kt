package com.th3web.lean.data

import org.junit.Assert.assertEquals
import org.junit.Test
import com.th3web.lean.data.net.Pinger

/**
 * [SettingsDefaults.PING_PROTOCOL] is the value written into DataStore;
 * [Pinger.URL_TEST_PROTOCOL] is what [Pinger.measure]'s `when` actually dispatches on.
 * They are declared in different layers and the compiler cannot relate them, so if one
 * were ever edited alone the default would silently stop matching its own branch and
 * every ping would quietly fall through to the TCP fallback — a real measurement
 * regression with no crash and no failing behaviour to notice.
 */
class PingProtocolDefaultTest {

    @Test
    fun `the default ping protocol is the URL Test value Pinger dispatches on`() {
        assertEquals(Pinger.URL_TEST_PROTOCOL, SettingsDefaults.PING_PROTOCOL)
    }

    @Test
    fun `the default settings object exposes that same protocol`() {
        assertEquals(SettingsDefaults.PING_PROTOCOL, Settings().pingProtocol)
    }
}
