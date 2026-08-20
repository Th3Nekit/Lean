package com.th3web.lean.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The product copy is the only place a user is told what is inside Lean, so these tests
 * guard the CLAIMS rather than the prose. Pinning exact sentences only produced a test that
 * had to be edited in lockstep with every wording change — which is what let the runtime
 * line keep saying "sing-box" for weeks after the engine had become NekoBox's libcore.
 */
class ProductCopyTest {

    /** Every constant shown to the user, so a new one cannot silently skip translation. */
    private val userFacingCopy = listOf(
        PRODUCT_DESCRIPTION,
        SETTINGS_RUNTIME_VERSION,
        AWG_EDITOR_DESCRIPTION,
        CREDIT_ENGINE,
        CREDIT_PROTOCOLS,
        CREDIT_AWG,
        CREDIT_HELPERS,
        CREDIT_HELPERS_NO_NAIVE,
        CREDIT_DESIGN,
        CREDIT_LICENSE,
    )

    @Test
    fun `every piece of product copy has an English pair`() {
        val untranslated = userFacingCopy.filter { EN[it].isNullOrBlank() }
        assertEquals("untranslated product copy: $untranslated", emptyList<String>(), untranslated)
    }

    @Test
    fun `the runtime line names the core that is actually linked in`() {
        // libcore is the artifact; the sing-box inside it is MatsuriDayo's fork, not
        // upstream, so naming sing-box alone would be wrong in both directions — it hides
        // NekoBox and implies stock behaviour.
        assertTrue(SETTINGS_RUNTIME_VERSION.contains("libcore"))
        assertTrue(SETTINGS_RUNTIME_VERSION.contains("NekoBox"))
        assertTrue(SETTINGS_RUNTIME_VERSION.contains("AmneziaWG-Go"))
        assertTrue(SETTINGS_RUNTIME_VERSION.contains("%s"))
        assertTrue(EN.getValue(SETTINGS_RUNTIME_VERSION).contains("%s"))
    }

    @Test
    fun `the credits name every component the build actually ships`() {
        assertTrue(CREDIT_ENGINE.contains("NekoBox"))
        assertTrue(CREDIT_ENGINE.contains("sing-box"))
        // Both halves of the engine are GPL-3.0; stating a licence is the point of a
        // credit, and getting it wrong is worse than omitting it.
        assertTrue(CREDIT_ENGINE.contains("GPL-3.0"))
        assertTrue(CREDIT_AWG.contains("AmneziaWG-Go"))
        assertTrue(CREDIT_AWG.contains("MIT"))
        assertTrue(CREDIT_AWG.contains("Apache-2.0"))
        // WireGuard runs on the separate native core, NOT on the sing-box side. The old
        // copy credited it to sing-box, which was simply untrue after the split.
        assertTrue(CREDIT_AWG.contains("WireGuard"))
        assertFalse(CREDIT_PROTOCOLS.contains("WireGuard"))
        // The four helper binaries ship in the APK and run as processes, so each one is a
        // component the user is entitled to see named — with its licence, which for two of
        // them (GPL-3.0, MPL-2.0) carries obligations the credit is part of meeting.
        listOf("NaiveProxy", "BSD-3-Clause", "Mieru", "GPL-3.0", "olcRTC", "WTFPL", "Xray-core", "MPL-2.0")
            .forEach { assertTrue("credits omit \"$it\"", CREDIT_HELPERS.contains(it)) }
        // The build without NaiveProxy names the three helpers it does carry, and says
        // outright that the fourth is absent rather than quietly dropping it: a reader
        // comparing the two builds should not have to diff two lists to notice.
        listOf("Mieru", "GPL-3.0", "olcRTC", "WTFPL", "Xray-core", "MPL-2.0")
            .forEach { assertTrue("foss credits omit \"$it\"", CREDIT_HELPERS_NO_NAIVE.contains(it)) }
        assertTrue(CREDIT_HELPERS_NO_NAIVE.contains("NaiveProxy"))
    }

    @Test
    fun `support links point at the owner's real destinations`() {
        assertEquals("https://boosty.to/th3nekit", BOOSTY_URL)
        assertEquals("https://t.me/VPN_Lean_bot", LEAN_BOT_URL)
    }
}
