package com.th3web.lean.data

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import com.th3web.lean.data.model.Profile
import com.th3web.lean.data.model.Subscription
import java.io.File
import java.io.FileOutputStream

/**
 * Outcome of loading the on-disk store. [degraded] = the bytes existed but could
 * not be fully decoded (torn write / forward-compat / one bad entry), callers
 * must not overwrite the file with an empty store while degraded. [absent] =
 * neither the main file nor the .bak existed (clean first run), not degraded.
 */
data class LoadedStore(
    val data: StoreData,
    val degraded: Boolean,
    val absent: Boolean,
)

/**
 * Context-free load / decode / .bak-recovery + write for the JSON store. Pure:
 * String + java.io.File only, so it is unit-testable on the JVM (TemporaryFolder)
 * with no Android Context / Robolectric. Extracted from [ProfileRepository] so the
 * data-loss-critical recovery logic can be tested directly.
 */
object StoreCodec {

    /**
     * Decode raw store text. null text => clean-absent (empty, not degraded).
     * Whole-store decode is the fast path (degraded=false). On failure, decode
     * entries one-by-one (degraded=true), so one bad entry drops only itself.
     */
    fun decodeStore(text: String?): LoadedStore {
        if (text == null) return LoadedStore(StoreData(), degraded = false, absent = true)
        runCatching { Serialization.json.decodeFromString<StoreData>(text) }
            .onSuccess { return LoadedStore(it, degraded = false, absent = false) }
        val resilient = runCatching { decodeResilient(text) }.getOrNull()
        return LoadedStore(resilient ?: StoreData(), degraded = true, absent = false)
    }

    private fun decodeResilient(text: String): StoreData {
        val root = Serialization.json.parseToJsonElement(text).jsonObject
        val profiles = (root["profiles"] as? JsonArray)?.mapNotNull { el ->
            runCatching { Serialization.json.decodeFromJsonElement<Profile>(el) }.getOrNull()
        } ?: emptyList()
        val subs = (root["subscriptions"] as? JsonArray)?.mapNotNull { el ->
            runCatching { Serialization.json.decodeFromJsonElement<Subscription>(el) }.getOrNull()
        } ?: emptyList()
        return StoreData(profiles, subs)
    }

    /**
     * Load from [file], recovering from [bak]. Tries the main file first; if its
     * bytes are missing/unreadable or present-but-undecodable, falls back to the
     * .bak before giving up. Returns degraded=true whenever the bytes that did
     * decode came from a resilient (lossy) decode.
     */
    fun loadFrom(file: File, bak: File): LoadedStore {
        val mainText = runCatching { if (file.exists()) file.readText() else null }.getOrNull()
        if (mainText != null) {
            val main = decodeStore(mainText)
            if (!main.degraded) return main // clean main wins, no .bak read
            // .bak fallback on a torn-but-readable main, before accepting empty+degraded.
            val bakText = runCatching { if (bak.exists()) bak.readText() else null }.getOrNull()
            if (bakText != null) {
                val recovered = decodeStore(bakText)
                if (!recovered.degraded) return recovered // good .bak fully restores; not degraded
            }
            return main // both lossy: keep main's resilient remnant, stay degraded
        }
        val bakText = runCatching { if (bak.exists()) bak.readText() else null }.getOrNull()
            ?: return LoadedStore(StoreData(), degraded = false, absent = true)
        return decodeStore(bakText)
    }

    /**
     * Never let a degraded, load-glitch empty overwrite a good store (false => skip).
     * The guard fires only when we are degraded and have never seen real data this
     * session ([everHadData] = false): that empty is a decode glitch, not the user's
     * doing. Once the store has held real data (loaded non-empty, or a non-empty write
     * happened), a subsequent empty is a legitimate user "delete all" and must persist,
     * otherwise the deleted servers resurrect on the next launch.
     */
    fun shouldPersist(degraded: Boolean, everHadData: Boolean, data: StoreData): Boolean =
        !(degraded && !everHadData && data.profiles.isEmpty() && data.subscriptions.isEmpty())

    /** While degraded, the last-good .bak must not be overwritten by the torn main. */
    fun shouldSnapshotBak(degraded: Boolean): Boolean = !degraded

    /**
     * Atomic-ish write: temp (the good data) -> snapshot .bak from tmp (only when
     * [snapshotBak]) -> rename tmp to the main file (rename fallback = in-place rewrite).
     * Pure File IO, no Context.
     *
     * The .bak is snapshotted from **tmp** (the just-written good in-memory data), not
     * from the on-disk main file: after a `.bak` recovery the main file is still torn,
     * and copying that over the good `.bak` (the old behaviour) left both torn if a
     * crash hit before the rename. Snapshotting from tmp keeps the `.bak` always a
     * copy of good data, and the rename below heals the torn main on this very write.
     */
    fun writeStore(data: StoreData, file: File, bak: File, tmp: File, snapshotBak: Boolean) {
        writeDurably(tmp, Serialization.json.encodeToString(data))
        if (snapshotBak) runCatching { tmp.copyTo(bak, overwrite = true) }
        if (!tmp.renameTo(file)) {
            writeDurably(file, tmp.readText())
            tmp.delete()
        }
    }

    /**
     * Writes and waits for the bytes to actually reach storage.
     *
     * The rename below is only atomic with respect to what has been written. A plain
     * write leaves the content in the page cache, so a power loss or a kernel-level kill
     * between the write and the rename can publish a file that exists, is named correctly,
     * and is half a JSON document, the torn store this whole class is built to survive.
     * Syncing first costs one flush per save and removes that window.
     */
    private fun writeDurably(target: File, text: String) {
        FileOutputStream(target).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            // Not every filesystem implements it; a refusal is not a reason to fail a save
            // that has otherwise been written.
            runCatching { out.fd.sync() }
        }
    }
}
