package com.th3web.lean.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import com.th3web.lean.data.model.Outbound
import com.th3web.lean.data.model.Profile
import java.io.File

/**
 * Pure-JVM (no Context, no Robolectric) coverage of the extracted load/decode/.bak
 * recovery + write path. Runs in the existing testDebugUnitTest gate with no new deps.
 */
class StoreCodecTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun profile(name: String, host: String) = Profile(
        name = name,
        outbound = Outbound.WireGuard(
            server = host, serverPort = 51820,
            privateKey = "a", peerPublicKey = "b", localAddresses = listOf("10.0.0.2/32"),
        ),
    )

    private fun validStore() = StoreData(profiles = listOf(profile("S1", "1.2.3.4")))

    private fun encode(d: StoreData) = Serialization.json.encodeToString(StoreData.serializer(), d)

    // ---- decodeStore ----

    @Test fun decodeStore_null_is_cleanAbsent_notDegraded() {
        val r = StoreCodec.decodeStore(null)
        assertTrue("null text must be flagged absent", r.absent)
        assertFalse("absent (clean first run) must NOT be degraded", r.degraded)
        assertEquals(StoreData(), r.data)
    }

    @Test fun decodeStore_validJson_cleanFastPath_notDegraded() {
        val store = validStore()
        val r = StoreCodec.decodeStore(encode(store))
        assertFalse("a fully-decodable store is the clean fast path", r.degraded)
        assertFalse(r.absent)
        assertEquals(store, r.data)
    }

    @Test fun decodeStore_garbage_isDegraded_notAbsent() {
        val r = StoreCodec.decodeStore("{ this is not json")
        assertTrue("present-but-unreadable bytes must be degraded", r.degraded)
        assertFalse("present bytes are NOT absent", r.absent)
    }

    // ---- loadFrom (.bak recovery) ----

    @Test fun loadFrom_absentBoth_isCleanAbsent_notDegraded() {
        val r = StoreCodec.loadFrom(File(tmp.root, "store.json"), File(tmp.root, "store.json.bak"))
        assertTrue(r.absent)
        assertFalse("clean first run is never degraded", r.degraded)
    }

    @Test fun loadFrom_cleanMain_winsWithoutReadingBak_notDegraded() {
        val main = File(tmp.root, "store.json")
        val bak = File(tmp.root, "store.json.bak")
        main.writeText(encode(validStore()))
        bak.writeText("{ poisoned")
        val r = StoreCodec.loadFrom(main, bak)
        assertFalse("clean main must short-circuit, never consult .bak", r.degraded)
        assertEquals(1, r.data.profiles.size)
    }

    @Test fun loadFrom_tornMain_goodBak_restoresFromBak_notDegraded() {
        val main = File(tmp.root, "store.json")
        val bak = File(tmp.root, "store.json.bak")
        val good = validStore()
        main.writeText(encode(good))
        main.copyTo(bak, overwrite = true)
        main.writeText("{\"profiles\":[{\"na")
        val r = StoreCodec.loadFrom(main, bak)
        assertEquals("good .bak must be decoded BEFORE returning empty+degraded", good, r.data)
        assertFalse("a fully-recovered .bak load is clean", r.degraded)
    }

    @Test fun loadFrom_tornMain_noBak_emptyDegraded() {
        val main = File(tmp.root, "store.json")
        val bak = File(tmp.root, "store.json.bak")
        main.writeText("{ totally broken")
        val r = StoreCodec.loadFrom(main, bak)
        assertTrue("no usable bytes anywhere => degraded", r.degraded)
        assertFalse(r.absent)
    }

    // ---- anti-wipe / degraded decision rules (pure, no IO) ----

    @Test fun shouldPersist_blocksEmptyWriteWhileDegradedAndNeverHadData() {
        assertFalse(StoreCodec.shouldPersist(degraded = true, everHadData = false, data = StoreData()))
    }

    @Test fun shouldPersist_allowsNonEmptyWriteWhileDegraded() {
        assertTrue(StoreCodec.shouldPersist(degraded = true, everHadData = false, data = validStore()))
    }

    @Test fun shouldPersist_allowsEmptyWriteWhenClean() {
        assertTrue(StoreCodec.shouldPersist(degraded = false, everHadData = false, data = StoreData()))
    }

    // M5: a user "delete all" in a degraded session (data WAS present) must persist,
    // else the deleted servers resurrect on the next launch.
    @Test fun shouldPersist_allowsUserDeleteAllInDegradedSessionOnceHadData() {
        assertTrue(StoreCodec.shouldPersist(degraded = true, everHadData = true, data = StoreData()))
    }

    @Test fun shouldSnapshotBak_skipsWhileDegraded() {
        assertFalse(StoreCodec.shouldSnapshotBak(degraded = true))
        assertTrue(StoreCodec.shouldSnapshotBak(degraded = false))
    }

    // ---- end-to-end: degraded load must not clobber the good .bak (real File IO) ----

    @Test fun degradedLoad_thenLatencyBurstWrite_doesNotClobberGoodBak() {
        val main = File(tmp.root, "store.json")
        val bak = File(tmp.root, "store.json.bak")
        val tmpF = File(tmp.root, "store.json.tmp")
        val good = validStore()

        // main lossy AND .bak also lossy -> degraded load.
        main.writeText("{\"profiles\":[{\"na")
        bak.writeText("{\"profiles\":[{\"bad")
        val degraded = StoreCodec.loadFrom(main, bak)
        assertTrue(degraded.degraded)

        // Re-establish a known-good .bak, then act as a degraded latency-burst write.
        bak.writeText(encode(good))
        StoreCodec.writeStore(
            data = degraded.data, file = main, bak = bak, tmp = tmpF,
            snapshotBak = StoreCodec.shouldSnapshotBak(degraded = true), // => false
        )
        assertEquals(
            "degraded latency-burst write must not clobber the good .bak",
            good, StoreCodec.decodeStore(bak.readText()).data,
        )
    }

    @Test fun cleanWrite_snapshotsJustWrittenDataToBak() {
        val main = File(tmp.root, "store.json")
        val bak = File(tmp.root, "store.json.bak")
        val tmpF = File(tmp.root, "store.json.tmp")
        val v1 = validStore()
        val v2 = StoreData(profiles = listOf(profile("S2", "5.6.7.8")))
        main.writeText(encode(v1))
        StoreCodec.writeStore(v2, main, bak, tmpF, snapshotBak = StoreCodec.shouldSnapshotBak(degraded = false))
        assertEquals("clean write installs new data", v2, StoreCodec.decodeStore(main.readText()).data)
        // M4: .bak is snapshotted from the JUST-WRITTEN good data (tmp), never the
        // on-disk main — so it is always a copy of good data (here == v2), never torn.
        assertEquals("clean write snapshots the just-written good data to .bak", v2, StoreCodec.decodeStore(bak.readText()).data)
    }

    // M4: after recovering from a good .bak (main torn), the FIRST write must heal the
    // torn main AND keep the .bak good — never copy the torn main over the good .bak.
    @Test fun recoveryThenWrite_healsMainAndKeepsBakGood() {
        val main = File(tmp.root, "store.json")
        val bak = File(tmp.root, "store.json.bak")
        val tmpF = File(tmp.root, "store.json.tmp")
        val good = validStore()

        main.writeText("{\"profiles\":[{\"tor")       // torn main
        bak.writeText(encode(good))                    // good .bak
        val loaded = StoreCodec.loadFrom(main, bak)
        assertEquals(good, loaded.data)
        assertFalse("recovered-from-bak load is clean", loaded.degraded)

        // First write after recovery (degraded=false -> snapshotBak=true).
        StoreCodec.writeStore(loaded.data, main, bak, tmpF, snapshotBak = StoreCodec.shouldSnapshotBak(degraded = false))
        assertEquals("torn main healed", good, StoreCodec.decodeStore(main.readText()).data)
        assertEquals("good .bak preserved (not clobbered by torn main)", good, StoreCodec.decodeStore(bak.readText()).data)
    }

    /**
     * A store written before folders existed must still decode. The loader falls back to
     * an empty StoreData on any decode failure, so a field added without a default would
     * not throw — it would quietly wipe every server the user had.
     */
    @Test fun decodeStore_fromBeforeFoldersExisted_keepsEverything() {
        val legacy = """{"profiles":[],"subscriptions":[{"id":"s","name":"S","url":"https://s.example"}]}"""
        val decoded = Serialization.json.decodeFromString(StoreData.serializer(), legacy)
        assertEquals(1, decoded.subscriptions.size)
        assertEquals("", decoded.subscriptions.single().folderId)
        assertTrue(decoded.folders.isEmpty())
    }
}
