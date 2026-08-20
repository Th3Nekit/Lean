package com.th3web.lean.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.th3web.lean.data.model.Outbound
import com.th3web.lean.data.model.Profile

class ProfileSelectionTest {

    private val profiles = listOf(
        Profile(
            id = "first",
            name = "First",
            outbound = Outbound.Vless(server = "first.example", serverPort = 443, uuid = "first"),
        ),
        Profile(
            id = "second",
            name = "Second",
            outbound = Outbound.Vless(server = "second.example", serverPort = 443, uuid = "second"),
        ),
    )

    @Test
    fun existingSavedProfileIsKept() {
        assertEquals("second", resolveProfileSelection("second", profiles))
    }

    @Test
    fun autoIsKeptWhenProfilesExist() {
        assertEquals("__auto__", resolveProfileSelection("__auto__", profiles))
    }

    @Test
    fun staleSavedProfileFallsBackToFirst() {
        assertEquals("first", resolveProfileSelection("deleted", profiles))
    }

    @Test
    fun missingSavedProfileFallsBackToFirst() {
        assertEquals("first", resolveProfileSelection(null, profiles))
    }

    @Test
    fun emptyProfilesAlwaysResolveToNull() {
        assertNull(resolveProfileSelection("__auto__", emptyList()))
        assertNull(resolveProfileSelection("deleted", emptyList()))
    }
}
