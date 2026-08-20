package com.th3web.lean.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The share code goes through android.util.Base64, which is a throwing stub on the bare
 * android.jar — Robolectric, SDK 34, per the project convention.
 *
 * A code is untrusted input by design: it arrives from a chat message. So the contract
 * under test is not "it round-trips" but "nothing a stranger can type gets past the
 * decoder unclamped".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppearanceProfileRobolectricTest {

    @Test
    fun `a code round-trips a look exactly`() {
        val profile = AppearancePresets.Terminal.profile

        val code = profile.encode()

        assertTrue(code.startsWith(APPEARANCE_CODE_PREFIX))
        assertEquals(profile, decodeAppearance(code))
    }

    @Test
    fun `a code carries only what the look changes`() {
        // encodeDefaults = false is what makes this pasteable: an all-defaults look
        // encodes an empty object. Assert the property, not a byte count that drifts
        // with every knob added.
        val default = AppearanceProfile.Default.encode()
        val terminal = AppearancePresets.Terminal.profile.encode()

        assertTrue(default.length < 32)
        assertTrue(default.length < terminal.length)
        assertTrue(terminal.length < 256)
    }

    @Test
    fun `line wrapping inserted by a chat client does not break a code`() {
        val profile = AppearancePresets.Paper.profile
        val code = profile.encode()

        val wrapped = code.substring(0, 16) + "\n " + code.substring(16)

        assertEquals(profile, decodeAppearance(wrapped))
    }

    @Test
    fun `foreign and damaged codes decode to null instead of throwing`() {
        assertNull(decodeAppearance(""))
        assertNull(decodeAppearance("hello"))
        assertNull(decodeAppearance("LEAN2:AAAA"))
        assertNull(decodeAppearance(APPEARANCE_CODE_PREFIX))
        // Not base64 at all.
        assertNull(decodeAppearance(APPEARANCE_CODE_PREFIX + "!!!!"))
        // Valid base64, not a deflate stream.
        assertNull(decodeAppearance(APPEARANCE_CODE_PREFIX + "A".repeat(64)))
        // Half-copied out of a chat.
        val truncated = AppearancePresets.Warm.profile.encode()
        assertNull(decodeAppearance(truncated.take(truncated.length / 2)))
    }

    @Test
    fun `a hand-edited code is clamped, never trusted`() {
        val hostile = AppearanceProfile(
            themeMode = "neon",
            contrastLevel = 99,
            surfaceTint = -40,
            textScale = 400,
            fontWeightDelta = 9_000,
            quickPeek = 1_000,
            selectionWash = -3,
            homeBlocks = 4_096,
            themeSchedFrom = -5,
            latT1 = 5_000,
            latT2 = 10,
            latT3 = 20,
            cornerStyle = "sphere",
            haptics = "brutal",
            fontBody = "comic",
            roleOverrides = mapOf(
                AppearanceRoles.BACKGROUND to 0x00112233L,
                "not_a_role" to 0xFF000000L,
            ),
        )

        val decoded = decodeAppearance(hostile.encode())!!

        assertEquals("dark", decoded.themeMode)
        assertEquals(AppearanceRanges.CONTRAST_MAX, decoded.contrastLevel)
        assertEquals(AppearanceRanges.SURFACE_TINT_MIN, decoded.surfaceTint)
        assertEquals(120, decoded.textScale)
        assertEquals(100, decoded.fontWeightDelta)
        assertEquals(AppearanceRanges.QUICK_PEEK_MAX, decoded.quickPeek)
        assertEquals(AppearanceRanges.SELECTION_WASH_MIN, decoded.selectionWash)
        assertEquals(AppearanceRanges.HOME_BLOCKS_MAX, decoded.homeBlocks)
        assertEquals(AppearanceRanges.MINUTE_MIN, decoded.themeSchedFrom)
        assertEquals("normal", decoded.cornerStyle)
        assertEquals("normal", decoded.haptics)
        // Unknown family falls back per ROLE, not to one shared default.
        assertEquals("onest", decoded.fontBody)
        // Thresholds come back strictly ascending even though they arrived reversed.
        assertTrue(decoded.latT1 < decoded.latT2)
        assertTrue(decoded.latT2 < decoded.latT3)
        // Unknown slot dropped; a translucent override forced opaque.
        assertEquals(mapOf(AppearanceRoles.BACKGROUND to 0xFF112233L), decoded.roleOverrides)
    }

    @Test
    fun `the first preset reproduces the shipping look and no preset needs sanitizing`() {
        assertEquals(AppearanceProfile(), AppearancePresets.Steel.profile)
        assertEquals(7, AppearancePresets.all.size)
        AppearancePresets.all.forEach {
            assertEquals(it.name, it.profile, it.profile.sanitized())
        }
    }

    @Test
    fun `the saved-look library round-trips and stays capped`() {
        val many = (1..15).map { NamedAppearance("Образ $it", AppearanceProfile(textScale = 110)) }

        val decoded = decodeCustomPresets(encodeCustomPresets(many))

        assertEquals(AppearanceRanges.CUSTOM_PRESET_MAX, decoded.size)
        assertEquals("Образ 1", decoded.first().name)
        assertEquals(110, decoded.first().profile.textScale)
        assertEquals(emptyList<NamedAppearance>(), decodeCustomPresets(null))
        assertEquals(emptyList<NamedAppearance>(), decodeCustomPresets("{not json"))
    }

    @Test
    fun `recent accents keep order, drop duplicates and stay opaque`() {
        val csv = formatRecentAccents(listOf(0x0098D1A6L, 0xFF98D1A6L, 0xFF9FD2CBL))

        assertEquals(listOf(0xFF98D1A6L, 0xFF9FD2CBL), parseRecentAccents(csv))
        assertEquals(emptyList<Long>(), parseRecentAccents(null))
        assertEquals(emptyList<Long>(), parseRecentAccents("zzz"))
    }

    @Test
    fun `mergeInto replaces the look and nothing else`() {
        val current = Settings(
            selectedProfileId = "keep-me",
            remoteDns = "https://keep.example/dns-query",
            themeMode = "light",
        )

        val merged = AppearancePresets.Terminal.profile.mergeInto(current)

        assertEquals("keep-me", merged.selectedProfileId)
        assertEquals("https://keep.example/dns-query", merged.remoteDns)
        assertEquals(AppearancePresets.Terminal.profile, merged.toAppearanceProfile())
    }
}
