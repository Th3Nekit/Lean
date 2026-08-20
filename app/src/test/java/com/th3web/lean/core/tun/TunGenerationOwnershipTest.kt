package com.th3web.lean.core.tun

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunGenerationOwnershipTest {
    @Test
    fun `detached tun is one shot and remains active for handover`() {
        val ownership = TunGenerationOwnership()
        ownership.begin(7)
        ownership.requireMayEstablish(7, borrowedLeaseExists = false)
        ownership.markDetached(7)

        assertTrue(ownership.hasActiveTun(borrowedLeaseExists = false))
        checkNotNull(
            runCatching {
                ownership.requireMayEstablish(7, borrowedLeaseExists = false)
            }.exceptionOrNull(),
        )
    }

    @Test
    fun `closing old generation cannot clear detached marker of newer generation`() {
        val ownership = TunGenerationOwnership()
        ownership.begin(8)
        ownership.markDetached(8)
        assertTrue(ownership.closeGeneration(8))

        ownership.begin(9)
        ownership.markDetached(9)

        assertFalse(ownership.closeGeneration(8))
        assertTrue(ownership.hasActiveTun(borrowedLeaseExists = false))
        ownership.requireCurrent(9)
    }
}
