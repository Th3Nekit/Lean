package com.th3web.lean.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalResolverTest {
    @Test
    fun `cancel interrupts lookup and suppresses a late native callback`() {
        var interrupts = 0
        var reports = 0
        val completion = DnsLookupCompletion { interrupts++ }

        completion.cancel()
        val reported = completion.report { reports++ }
        completion.cancel()

        assertEquals(1, interrupts)
        assertEquals(0, reports)
        assertFalse(reported)
    }

    @Test
    fun `completed lookup suppresses a late cancellation`() {
        var interrupts = 0
        var reports = 0
        val completion = DnsLookupCompletion { interrupts++ }

        assertTrue(completion.report { reports++ })
        completion.cancel()

        assertEquals(1, reports)
        assertEquals(0, interrupts)
    }

    /**
     * The resolver is called by the core across JNI, so nothing may escape it — an
     * exception on that thread is a process death with no Java stack. The last-resort
     * report added for that must go through the SAME guard as the normal path, or a
     * failure thrown after a successful answer would answer the same request twice.
     */
    @Test
    fun `a last-resort failure cannot answer an already answered lookup`() {
        var answers = 0
        var failures = 0
        val completion = DnsLookupCompletion { }

        completion.report { answers++ }
        val late = completion.report { failures++ }

        assertEquals(1, answers)
        assertEquals(0, failures)
        assertFalse("the second report must be refused outright", late)
    }
}
