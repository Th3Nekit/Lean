package com.th3web.lean.core.tun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunLeaseTest {
    @Test
    fun `borrow keeps Java ownership and close is idempotent`() {
        val handle = FakeHandle(42)
        val lease = TunLease(handle)

        assertEquals(42, lease.borrowFd())
        assertEquals(42, lease.borrowFd())
        assertFalse(handle.detached)

        lease.close()
        lease.close()
        assertEquals(1, handle.closeCalls)
    }

    @Test
    fun `detach transfers ownership exactly once`() {
        val handle = FakeHandle(73)
        val lease = TunLease(handle)

        assertEquals(73, lease.detachFd())
        assertTrue(handle.detached)
        assertFails { lease.detachFd() }

        lease.close()
        assertEquals(0, handle.closeCalls)
    }

    @Test
    fun `borrow after close fails`() {
        val lease = TunLease(FakeHandle(9))
        lease.close()
        assertFails { lease.borrowFd() }
    }

    private fun assertFails(block: () -> Unit) {
        checkNotNull(runCatching(block).exceptionOrNull())
    }

    private class FakeHandle(override val fd: Int) : TunHandle {
        var detached = false
        var closeCalls = 0

        override fun detachFd(): Int {
            check(!detached)
            detached = true
            return fd
        }

        override fun close() {
            closeCalls++
        }
    }
}
