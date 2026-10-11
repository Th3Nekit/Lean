package com.th3web.lean.core

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.annotation.RequiresApi
import com.th3web.lean.ui.tr

/**
 * Answers "why did the tunnel stop on its own" the next time the app runs.
 *
 * The complaint this exists for is «рандомный дисконнект»: the VPN is up, nobody touches
 * it, and some minutes or hours later it is simply off. Nothing in the app could say
 * anything about it, because the thing that ends the session is not a code path, it is the
 * process disappearing: an out-of-memory kill, a vendor battery layer freezing then
 * killing the app, a native crash inside Go, an ANR. All four leave the same evidence from
 * inside: nothing at all. The service is re-created by START_STICKY (or not), and the log
 * picks up as if a new session had merely started.
 *
 * Two sources are combined here, and neither is sufficient alone:
 *
 *  * **A wanted-flag in SharedPreferences.** Set while a tunnel is supposed to be up,
 *    cleared on a deliberate stop. Finding it still set on the way into a fresh process
 *    means the previous incarnation never got to stop: it was taken down. This works on
 *    every API level and is the only signal that distinguishes "the user turned it off"
 *    from "it died".
 *  * **[ActivityManager.getHistoricalProcessExitReasons] (API 30+).** The system's own
 *    record of how each previous process of this app ended, with a reason code the app
 *    could never infer: LOW_MEMORY, CRASH, CRASH_NATIVE, ANR, SIGNALED, USER_REQUESTED
 *    (which is what a vendor task-killer or a swipe-away reports), EXCESSIVE_RESOURCE_USAGE
 *    (the battery layers). That is the actual answer to the user's question.
 *
 * Both are turned into ordinary log lines, so they land in the log the user can read and
 * in the diagnostics report they send, right where the session ended.
 *
 * Writes go to SharedPreferences rather than DataStore for the same reason the crash-loop
 * guard in [LeanVpnService] does: this is read and written on the way into and out of a
 * foreground service that has seconds to live up to its notification, and a suspending
 * store is the wrong shape for that.
 */
internal class TunnelOutageLog(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val log: (String) -> Unit = CoreManager::appendLog,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /**
     * A tunnel is wanted from here on. Called when a session is established, not when one
     * is requested: a connect that fails has already reported its own failure, and marking
     * a never-established tunnel as "wanted" would blame the next launch for it.
     */
    fun sessionStarted(profileName: String?) {
        prefs.edit()
            .putBoolean(KEY_WANTED, true)
            .putLong(KEY_SINCE, now())
            .putString(KEY_PROFILE, profileName.orEmpty())
            .apply()
    }

    /** The session ended because something asked it to. Nothing to report next time. */
    fun sessionEndedCleanly() {
        prefs.edit()
            .putBoolean(KEY_WANTED, false)
            .remove(KEY_SINCE)
            .remove(KEY_PROFILE)
            .apply()
    }

    /**
     * Reports, once per process, whatever can be said about how the previous session
     * ended. Safe to call when nothing happened: it then says nothing.
     *
     * Order matters in the output: the human-facing statement ("the tunnel was
     * interrupted, it had been up for 2 h 14 min") comes first, then the system's reason
     * if there is one, because the first line is what the user recognises as their
     * problem and the second is what explains it.
     */
    fun reportPreviousOutage() {
        val interrupted = prefs.getBoolean(KEY_WANTED, false)
        val exits = systemExitReasons()
        if (!interrupted && exits.isEmpty()) return
        if (interrupted) {
            val since = prefs.getLong(KEY_SINCE, 0L)
            val profile = prefs.getString(KEY_PROFILE, "").orEmpty()
            val uptime = if (since > 0) now() - since else 0L
            log(
                tr("⚠ прошлая сессия оборвалась не по команде: «%s», продержалась %s")
                    .format(profile.ifBlank { tr("сервер не записан") }, humanDuration(uptime)),
            )
        }
        exits.forEach(log)
        // Consumed: the same outage must not be re-reported on every later launch.
        prefs.edit().putBoolean(KEY_WANTED, false).apply()
    }

    /**
     * The system's own account of how previous processes of this app ended, newest first,
     * for the ones we have not reported yet.
     *
     * Everything here is best-effort: the API is API 30+, it can throw on some vendor
     * builds, and a device that answers nothing simply leaves the wanted-flag line above
     * as the whole report.
     */
    @Suppress("NewApi") // guarded by the SDK_INT check on the first line
    private fun systemExitReasons(): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        val seen = prefs.getLong(KEY_EXIT_SEEN, 0L)
        val infos = runCatching {
            context.getSystemService(ActivityManager::class.java)
                ?.getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXIT_RECORDS)
        }.getOrNull().orEmpty()
        val fresh = infos.filter { it.timestamp > seen }
        if (fresh.isEmpty()) return emptyList()
        prefs.edit().putLong(KEY_EXIT_SEEN, fresh.maxOf { it.timestamp }).apply()
        return fresh
            .sortedByDescending { it.timestamp }
            .mapNotNull { info -> describeExit(info) }
    }

    /**
     * One exit record as a line, or null for the ones that say nothing about an outage.
     *
     * REASON_EXIT_SELF and REASON_USER_STOPPED are dropped: the first is the app's own
     * clean shutdown, the second is the user stopping it from Settings, and neither is a
     * surprise worth a line. Everything else is kept, including the generic ones, since
     * "the system killed us and would not say why" is itself the answer to a disconnect
     * nobody can explain.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun describeExit(info: ApplicationExitInfo): String? {
        val reason = when (info.reason) {
            ApplicationExitInfo.REASON_EXIT_SELF, ApplicationExitInfo.REASON_USER_STOPPED -> return null
            ApplicationExitInfo.REASON_LOW_MEMORY ->
                tr("системе не хватило памяти — приложение выгружено")
            ApplicationExitInfo.REASON_CRASH -> tr("сбой приложения")
            ApplicationExitInfo.REASON_CRASH_NATIVE -> tr("сбой в нативном ядре")
            ApplicationExitInfo.REASON_ANR -> tr("приложение перестало отвечать (ANR)")
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE ->
                tr("система сочла расход ресурсов чрезмерным")
            ApplicationExitInfo.REASON_USER_REQUESTED ->
                tr("приложение закрыли вручную или его закрыл «оптимизатор» производителя")
            ApplicationExitInfo.REASON_SIGNALED ->
                tr("процесс завершён сигналом извне (часто — экономия батареи производителя)")
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> tr("завершился процесс, от которого мы зависели")
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> tr("изменились разрешения приложения")
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> tr("приложение не смогло запуститься")
            ApplicationExitInfo.REASON_OTHER -> tr("прочая причина, названная системой")
            else -> tr("причина не распознана (код %d)").format(info.reason)
        }
        // The importance at the moment of death separates "killed while in the
        // background" from "killed while running a foreground service", and the second
        // is the interesting one: it means the foreground notification did not protect
        // the tunnel, which points at the vendor layer rather than at ordinary memory
        // pressure.
        val wasForeground = info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
        val detail = info.description?.takeIf { it.isNotBlank() }
        return buildString {
            append(tr("⚠ прошлый процесс завершён: %s").format(reason))
            if (wasForeground) append(tr(" (во время работы туннеля)"))
            if (detail != null) append(" · ").append(detail)
        }
    }

    private fun humanDuration(ms: Long): String {
        if (ms <= 0) return tr("неизвестно сколько")
        val minutes = ms / 60_000L
        return when {
            minutes < 1 -> tr("меньше минуты")
            minutes < 60 -> tr("%d мин").format(minutes)
            else -> tr("%d ч %d мин").format(minutes / 60, minutes % 60)
        }
    }

    private companion object {
        const val KEY_WANTED = "tunnel_wanted"
        const val KEY_SINCE = "tunnel_wanted_since"
        const val KEY_PROFILE = "tunnel_wanted_profile"
        const val KEY_EXIT_SEEN = "exit_reason_seen_at"

        /**
         * How many of the system's exit records to read. It keeps at most 16 per app and
         * only the unreported ones are printed, so this is a ceiling on a cold start
         * after a long absence, not a per-launch cost.
         */
        const val MAX_EXIT_RECORDS = 8
    }
}
