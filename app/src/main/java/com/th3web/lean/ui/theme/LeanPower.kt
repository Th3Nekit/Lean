package com.th3web.lean.ui.theme

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Whether the device has asked us to be frugal.
 *
 * The expensive parts of the look (the blurred wallpaper and the glass panels) are
 * exactly what a phone in power-save mode should not be spending its remaining battery
 * on. Rather than making the user find and flip a setting, the app follows the system:
 * turn on battery saver and the costly layers switch themselves off; turn it off and they
 * come back, with the user's own settings untouched. Nothing is persisted, because this is
 * not a preference: it is a temporary condition.
 *
 * [frugal] is snapshot state, so a change repaints whatever is on screen with no further
 * plumbing.
 */
object LeanPower {

    var frugal by mutableStateOf(false)
        private set

    private var receiver: BroadcastReceiver? = null

    /** Reads the current mode and keeps it current. Safe to call more than once. */
    fun start(context: Context) {
        if (receiver != null) return
        val app = context.applicationContext
        refresh(app)
        // API 21+ broadcasts this whenever battery saver flips, including when the system
        // turns it on by itself at a low-battery threshold, which is the case that
        // matters, since the user is not in the app at that moment.
        val listener = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = refresh(app)
        }
        val filter = IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.registerReceiver(listener, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                app.registerReceiver(listener, filter)
            }
        }.onSuccess { receiver = listener }
    }

    private fun refresh(context: Context) {
        val power = context.getSystemService(PowerManager::class.java) ?: return
        frugal = runCatching { power.isPowerSaveMode }.getOrDefault(false)
    }
}
