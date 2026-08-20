package com.th3web.lean.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import java.math.BigInteger
import java.security.MessageDigest

/**
 * stable per-device hardware id sent with subscription requests so the panel can
 * bind/limit the device. Uses ANDROID_ID (stable per device + app-signing key
 * since Android 8, survives reinstalls), falling back to a Build fingerprint hash.
 *
 * [get] is the default id, an uppercase hex string (this is also the shape
 * v2rayTun sends, e.g. `BD5C67B7D945C961`). When Lean impersonates another client
 * (Provider hub UA picker → [ClientSpoof]), it must send an id in that client's
 * shape, so [happHwid] / [happUaId] derive Happ-shaped ids deterministically from
 * the same ANDROID_ID base (stable across launches; the exact algorithm is Lean's
 * own, panels care about the id being stable+unique+correctly-shaped, not about
 * reproducing Happ's private generator).
 *
 * No other client's id algorithm is reproduced here, and none needs to be: a panel
 * only requires the id to be stable, unique and the right shape. What is aligned with
 * other clients is when the header is sent and what User-Agent goes with it
 * (see Http.sendHwid / LeanApp / ClientSpoof).
 */
object HwId {
    @Volatile private var cachedBase: String? = null

    /** Raw ANDROID_ID (or a Build-fingerprint fallback), unnormalised. */
    @SuppressLint("HardwareIds")
    private fun base(context: Context): String {
        cachedBase?.let { return it }
        val androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()
        // 9774d56d682e549c is a known broken/duplicated ANDROID_ID on some devices.
        val raw = if (!androidId.isNullOrBlank() && androidId != "9774d56d682e549c") {
            androidId
        } else {
            Integer.toHexString((Build.FINGERPRINT + Build.MODEL + Build.ID).hashCode())
        }
        return raw.also { cachedBase = it }
    }

    private fun sha256(s: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))

    /** Default id: uppercase hex, Lean's own default and v2rayTun's shape. */
    fun get(context: Context): String = base(context).uppercase()

    /**
     * Happ-shaped `X-HWID` value: lowercase alphanumeric, 15 chars (e.g. `bobfod302052870`).
     * Base36 of a salted SHA-256 of the ANDROID_ID base, so it is stable per device.
     */
    fun happHwid(context: Context): String =
        BigInteger(1, sha256(base(context) + ":happ-hwid")).toString(36).padStart(15, '0').take(15)

    /**
     * Happ-shaped id embedded in Happ's User-Agent (`Happ/<ver>/<platform>/<id>`).
     *
     * The shape is not the same across platforms, which is why [digits] is a parameter.
     * Real values seen in a panel's request log:
     *   Happ/4.1.0/Android/17860741775021899510   ← 20 digits, a millisecond clock plus a tail
     *   Happ/5.2.0/ios/2607201202535              ← 13 digits, reads as YYMMDDhhmmss + 1
     *   Happ/3.3.6/Windows/2607171516500          ← 13 digits, same shape as ios
     * Lean emits a stable device-derived decimal of the matching length: the panel sees a
     * plausible id that never changes for this install, and no real device is described.
     */
    fun happUaId(context: Context, digits: Int): String =
        BigInteger(1, sha256(base(context) + ":happ-ua"))
            .mod(BigInteger.TEN.pow(digits))
            .toString()
            .padStart(digits, '0')

    /** Short device label for the User-Agent / panel device list. */
    fun deviceLabel(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
}
