package com.th3web.lean.data.net

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks the public release repo (Th3Nekit/Lean) for a newer app version via the
 * GitHub Releases API and reports whether an update is available. Lean has no
 * in-app self-update; this only surfaces "a newer build exists" + a download link
 * (the owner told users an update check was coming).
 *
 * From inside RU the GitHub API is often reachable only through the tunnel, and
 * unauthenticated calls are rate-limited (60/h), so every failure is swallowed
 * and simply reads as "no update info", never an error the user must act on.
 */
object UpdateChecker {

    private const val LATEST_RELEASE_API =
        "https://api.github.com/repos/Th3Nekit/Lean/releases/latest"

    data class UpdateInfo(
        val latestVersion: String,
        val releaseUrl: String,
        val apkUrl: String?,
    )

    /**
     * The outcome of a manual check, which needs one distinction [check]
     * throws away: "you are up to date" and "I could not find out" are the same null there,
     * and telling someone they have the newest build when the request never left the device
     * is simply a false statement. GitHub's API is routinely unreachable from RU without
     * the tunnel, so [Failed] is the common case, not an exotic one.
     */
    sealed interface CheckResult {
        data class Available(val info: UpdateInfo) : CheckResult
        data object UpToDate : CheckResult
        data object Failed : CheckResult
    }

    /** [check] with the up-to-date / unreachable distinction kept. */
    suspend fun checkManually(
        currentVersion: String,
        apiUrl: String = LATEST_RELEASE_API,
        timeoutMs: Int = 10_000,
    ): CheckResult = withContext(Dispatchers.IO) {
        val latest = fetchLatest(apiUrl, timeoutMs) ?: return@withContext CheckResult.Failed
        if (compareVersions(latest.latestVersion, normalizeVersion(currentVersion)) <= 0) {
            CheckResult.UpToDate
        } else {
            CheckResult.Available(latest)
        }
    }

    /**
     * Returns an [UpdateInfo] when the latest published release is strictly newer
     * than [currentVersion] (e.g. BuildConfig.VERSION_NAME), else null (already
     * current, pre-release-only, or the check failed/was blocked). [apiUrl] is the
     * GitHub endpoint, overridable for tests.
     */
    suspend fun check(
        currentVersion: String,
        apiUrl: String = LATEST_RELEASE_API,
        timeoutMs: Int = 10_000,
    ): UpdateInfo? = (checkManually(currentVersion, apiUrl, timeoutMs) as? CheckResult.Available)?.info

    /**
     * Fetches the latest published release, or null when the request could not be
     * completed, a block, a rate-limit, a timeout. Does not compare versions: the caller
     * decides what "no newer build" should mean to the user.
     */
    private suspend fun fetchLatest(apiUrl: String, timeoutMs: Int): UpdateInfo? =
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "Lean-UpdateChecker")
                }
                if (conn.responseCode != 200) return@withContext null
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val tag = json.optString("tag_name").ifEmpty { return@withContext null }
                val apkUrl = json.optJSONArray("assets")?.let { assets ->
                    var universal: String? = null
                    var firstApk: String? = null
                    for (i in 0 until assets.length()) {
                        val a = assets.optJSONObject(i) ?: continue
                        val name = a.optString("name")
                        val u = a.optString("browser_download_url").ifEmpty { null } ?: continue
                        if (!name.endsWith(".apk", ignoreCase = true)) continue
                        if (firstApk == null) firstApk = u
                        if (name.contains("universal", ignoreCase = true)) universal = u
                    }
                    universal ?: firstApk
                }
                UpdateInfo(
                    latestVersion = normalizeVersion(tag),
                    releaseUrl = json.optString("html_url").ifEmpty { "https://github.com/Th3Nekit/Lean/releases" },
                    apkUrl = apkUrl,
                )
            } catch (e: Exception) {
                null
            } finally {
                conn?.disconnect()
            }
        }

    private const val CHANNEL_ID = "lean_updates"
    private const val NOTIF_ID = 4201

    /**
     * Runs [check] and, if a newer build exists, posts a low-priority notification
     * linking to the release (tap → download the APK, or the releases page). Safe to
     * call on launch: failures/blocks are silent, and on Android 13+ without
     * POST_NOTIFICATIONS the notify simply no-ops (the manual check in «О Lean» still
     * works). Call only when the user opted in ([Settings.checkAppUpdates]).
     */
    suspend fun checkAndNotify(context: Context, currentVersion: String) {
        val info = check(currentVersion) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val target = info.apkUrl ?: info.releaseUrl
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Обновления", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Уведомления о новых версиях Lean" },
            )
        }
        val pi = PendingIntent.getActivity(
            context, 0,
            Intent(Intent.ACTION_VIEW, Uri.parse(target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Доступно обновление Lean ${info.latestVersion}")
            .setContentText("Нажмите, чтобы скачать")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIF_ID, n) }
    }

    internal fun mayPostNotification(sdkInt: Int, permissionGranted: Boolean): Boolean =
        sdkInt < Build.VERSION_CODES.TIRAMISU || permissionGranted

    /** Strip a leading `v` and any `-beta`/`-rc…` suffix: `v0.9.4-beta` -> `0.9.4`. */
    internal fun normalizeVersion(raw: String): String =
        raw.trim().removePrefix("v").removePrefix("V").substringBefore('-').trim()

    /**
     * Compare dotted numeric versions (any segment count: `0.9.4` vs `0.9.3.7`).
     * Non-numeric segments count as 0. Returns <0 / 0 / >0.
     */
    internal fun compareVersions(a: String, b: String): Int {
        val pa = a.split('.')
        val pb = b.split('.')
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrNull(i)?.toIntOrNull() ?: 0
            val y = pb.getOrNull(i)?.toIntOrNull() ?: 0
            if (x != y) return x - y
        }
        return 0
    }
}
