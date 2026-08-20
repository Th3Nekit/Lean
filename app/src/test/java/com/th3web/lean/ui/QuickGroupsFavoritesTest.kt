package com.th3web.lean.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.th3web.lean.data.StoreData
import com.th3web.lean.data.model.Outbound
import com.th3web.lean.data.model.Profile
import com.th3web.lean.data.model.Subscription

/**
 * Home's quick-pick grouping, with the starred-servers group testers asked for.
 *
 * Pure function over StoreData, so no Android runtime is involved.
 */
class QuickGroupsFavoritesTest {

    private fun server(
        id: String,
        subId: String? = null,
        latency: Int? = null,
        favorite: Boolean = false,
    ) = Profile(
        id = id,
        name = id,
        outbound = Outbound.Vless(server = "$id.example.com", serverPort = 443, uuid = "u-$id"),
        subscriptionId = subId,
        latencyMs = latency,
        favorite = favorite,
    )

    private fun store(vararg profiles: Profile, subs: List<Subscription> = emptyList()) =
        StoreData(profiles = profiles.toList(), subscriptions = subs)

    @Test
    fun `no favourites means no favourites group`() {
        val groups = buildQuickGroups(store(server("a"), server("b")), selectedId = null)
        assertTrue(groups.none { it.id == HomeViewModel.FAVORITES_GROUP_ID })
    }

    @Test
    fun `the favourites group leads and holds exactly the starred servers`() {
        val sub = Subscription(id = "s1", name = "Sub", url = "https://example.com/s")
        val groups = buildQuickGroups(
            store(
                server("plain", subId = "s1", latency = 10),
                server("starred", subId = "s1", latency = 900, favorite = true),
                server("manual-starred", favorite = true),
                subs = listOf(sub),
            ),
            selectedId = null,
        )

        assertEquals(HomeViewModel.FAVORITES_GROUP_ID, groups.first().id)
        assertEquals(
            setOf("starred", "manual-starred"),
            groups.first().servers.map { it.id }.toSet(),
        )
    }

    @Test
    fun `favourites stay pinned first even when another group pings better`() {
        // The whole point of starring: a group that exists because the user chose it must
        // not sink because some other group happens to be faster.
        val sub = Subscription(id = "s1", name = "Fast", url = "https://example.com/s")
        val groups = buildQuickGroups(
            store(
                server("fast", subId = "s1", latency = 5),
                server("slow-but-starred", latency = 800, favorite = true),
                subs = listOf(sub),
            ),
            selectedId = null,
        )
        assertEquals(HomeViewModel.FAVORITES_GROUP_ID, groups.first().id)
    }

    @Test
    fun `favourites lead even over the group holding the selected server`() {
        val sub = Subscription(id = "s1", name = "Sub", url = "https://example.com/s")
        val groups = buildQuickGroups(
            store(
                server("selected", subId = "s1", latency = 50),
                server("starred", favorite = true),
                subs = listOf(sub),
            ),
            selectedId = "selected",
        )
        assertEquals(HomeViewModel.FAVORITES_GROUP_ID, groups.first().id)
        assertEquals("s1", groups[1].id)
    }

    @Test
    fun `a starred server also stays in its own group`() {
        // Favourites is a shortcut, not a move: its source group must not look like it
        // lost a server.
        val sub = Subscription(id = "s1", name = "Sub", url = "https://example.com/s")
        val groups = buildQuickGroups(
            store(
                server("a", subId = "s1"),
                server("b", subId = "s1", favorite = true),
                subs = listOf(sub),
            ),
            selectedId = null,
        )
        val own = groups.first { it.id == "s1" }
        assertEquals(setOf("a", "b"), own.servers.map { it.id }.toSet())
    }

    @Test
    fun `favourites are ordered fastest first like every other group`() {
        val groups = buildQuickGroups(
            store(
                server("slow", latency = 400, favorite = true),
                server("quick", latency = 20, favorite = true),
                server("untested", favorite = true),
            ),
            selectedId = null,
        )
        val fav = groups.first { it.id == HomeViewModel.FAVORITES_GROUP_ID }
        assertEquals(listOf("quick", "slow", "untested"), fav.servers.map { it.id })
    }
}
