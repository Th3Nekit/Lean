package com.th3web.lean.core

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.th3web.lean.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.th3web.lean.LeanApp
import com.th3web.lean.MainActivity
import com.th3web.lean.data.resolveProfileSelection
import com.th3web.lean.ui.tr

/**
 * Quick Settings tile, one-tap VPN toggle from the notification shade (like NekoBox /
 * v2rayNG). Reflects [CoreManager.state] live while the tile is visible. If the system VPN
 * consent hasn't been granted yet, it opens the app to handle it; afterwards it connects
 * the selected profile directly without opening the UI.
 */
class LeanTileService : TileService() {

    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        // Cancel any prior scope first: some OEM shades fire onStartListening twice
        // without an onStopListening between, which would orphan the previous
        // state-collector and leak the tile alive. On the normal paired path scope is
        // already null (onStopListening cleared it), so this is a no-op.
        scope?.cancel()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { scope = it }
        s.launch { CoreManager.state.collect { render(it) } }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    private fun render(state: VpnState) {
        val tile = qsTile ?: return
        // No profiles and no live session → nothing to toggle: show unavailable so
        // a tap doesn't silently do nothing. Active sessions always stay tappable.
        val active = state is VpnState.Connected || state is VpnState.Connecting ||
            state is VpnState.Stopping
        val hasProfiles = LeanApp.instance.profiles.state.value.profiles.isNotEmpty()
        tile.state = when {
            !active && !hasProfiles -> Tile.STATE_UNAVAILABLE
            active -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = "Lean VPN"
        // Set in code as well as in the manifest: some OEM shades cache the manifest
        // icon and render an inset launcher glyph small in the tile slot.
        tile.icon = Icon.createWithResource(this, R.drawable.ic_mono_mark)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                !active && !hasProfiles -> tr("Нет серверов")
                state is VpnState.Connected -> tr("Подключено")
                state is VpnState.Connecting -> tr("Подключение…")
                state is VpnState.Stopping -> tr("Отключение…")
                state is VpnState.Error -> tr("Ошибка")
                else -> tr("Отключено")
            }
        }
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        when (CoreManager.state.value) {
            is VpnState.Connected, is VpnState.Connecting -> CoreManager.disconnect(this)
            // Session is still settling: a connect issued now races the in-progress
            // teardown and is serialized by LeanVpnService's connection coordinator,
            // leaving the optimistic "Подключение…" paint lying. Ignore the tap.
            is VpnState.Stopping -> {}
            else -> connect()
        }
    }

    private fun connect() {
        // First-time consent must come from an Activity; route through the app.
        if (VpnService.prepare(this) != null) {
            openApp()
            return
        }
        // Synchronous fast path (NekoBox-style): when the tile is tapped from a
        // cold/background process, the system spins up our process, runs
        // Application.onCreate, then this onClick. Read the selected profile from
        // the synchronous, live settings snapshot ([SettingsRepository.state],
        // not [initial], which is cached at process start and would connect the
        // server selected back then, not the one the user last picked) plus the
        // in-memory store, no suspend (settings.flow.first()) that could be cut
        // off when the freshly-started TileService is torn down right after the
        // tap. Both snapshots are pre-warmed in LeanApp.onCreate so this read is a
        // cheap in-memory hit, not a cold disk read on the click path. Then start
        // the foreground VpnService immediately. A QS-tile click is an allowed
        // exemption for starting a foreground service from the background, so this
        // reliably brings the tunnel up with the app closed.
        val app = LeanApp.instance
        val id = resolveProfileSelection(
            savedId = app.settings.state.value.selectedProfileId,
            profiles = app.profiles.state.value.profiles,
        )
        if (id == null) {
            openApp()
            return
        }
        // Flip the tile to its "connecting" visual synchronously, before firing the
        // intent, so the shade reacts on the very same frame as the tap. Otherwise
        // the tile only repaints once CoreManager.setState(Connecting) round-trips
        // back from the freshly-started service, a visible lag on a cold start.
        showConnectingOptimistically()
        CoreManager.connect(this, id)
    }

    /**
     * Optimistic, synchronous tile update to the "connecting" look the moment a
     * connect is committed, independent of the [CoreManager.state] collector,
     * which only fires after the service starts and pushes back Connecting. The
     * live collector's [render] takes over from here as real state arrives.
     */
    private fun showConnectingOptimistically() {
        val tile = qsTile ?: return
        tile.state = Tile.STATE_ACTIVE
        tile.label = "Lean VPN"
        tile.icon = Icon.createWithResource(this, R.drawable.ic_mono_mark)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = tr("Подключение…")
        }
        tile.updateTile()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}
