package com.th3web.lean.data.net

import com.th3web.lean.data.net.PhysicalNetwork.Link
import com.th3web.lean.data.net.PhysicalNetwork.rank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which link the app measures when it has to bypass the tunnel.
 *
 * There used to be no choice made at all: the first network `allNetworks` happened to
 * list won, and that list has no defined order. A phone with Wi-Fi connected and mobile
 * data enabled offers both, so «Скорость» compared a tunnel running over Wi-Fi at 250
 * Мбит/с against a "direct" measurement of the cellular radio at 10–15 — the same 10–15
 * against every target, because the target was never what limited it. A tunnel cannot be
 * twenty times faster than the link it runs over, which is how the report read.
 *
 * The same pick is used by «Проверка сервиса», the DPI probes and the crash reporter, all
 * of which were testing a radio the user was not browsing on.
 */
class PhysicalNetworkRankTest {

    @Test
    fun `wifi wins over a cellular radio that is also up`() {
        assertTrue(
            "Wi-Fi ${rank(Link.Wifi, validated = true)} против " +
                "мобильной ${rank(Link.Cellular, validated = true)}",
            rank(Link.Wifi, validated = true) > rank(Link.Cellular, validated = true),
        )
    }

    @Test
    fun `a wired link wins over wifi`() {
        assertTrue(rank(Link.Ethernet, validated = true) > rank(Link.Wifi, validated = true))
    }

    @Test
    fun `a validated radio beats a wifi that cannot reach anything`() {
        // A captive portal carries no bytes, so the transport order must not be allowed to
        // pick it: a worse link that works is the better stand-in for the default network.
        assertTrue(
            rank(Link.Cellular, validated = true) > rank(Link.Wifi, validated = false),
        )
    }

    @Test
    fun `an unknown transport is a last resort, not a first guess`() {
        assertTrue(rank(Link.Other, validated = true) < rank(Link.Cellular, validated = true))
        assertTrue(rank(Link.Other, validated = true) > rank(Link.Ethernet, validated = false))
    }

    @Test
    fun `no two kinds of link score the same`() {
        // A tie is a coin toss decided by list order, which is the bug this replaced.
        val scores = Link.entries.flatMap { link ->
            listOf(rank(link, validated = true), rank(link, validated = false))
        }
        assertEquals(scores.size, scores.distinct().size)
    }
}
