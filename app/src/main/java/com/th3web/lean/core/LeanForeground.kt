package com.th3web.lean.core

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Whether any of our UI is actually on screen.
 *
 * The tunnel keeps running when the app is not: that is what a VPN is for, but the
 * work done for the UI does not have to. Polling the core's byte counters once a second
 * and tailing its log twice a second is right while someone is watching the traffic
 * graph, and is pure battery burn while the phone is in a pocket: every tick is a JNI
 * round trip, a file read and a state write that nothing observes.
 *
 * Counting started activities rather than pulling in lifecycle-process keeps this to one
 * small object and no new dependency. onStart/onStop is the correct pair: onResume/onPause
 * would drop to zero for a dialog or the recents screen, which is not the same thing as
 * being away.
 */
object LeanForeground {

    @Volatile
    var visible: Boolean = false
        private set

    private var started = 0

    fun track(application: Application) {
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) {
                    started++
                    visible = true
                }

                override fun onActivityStopped(activity: Activity) {
                    started = (started - 1).coerceAtLeast(0)
                    visible = started > 0
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
    }
}
