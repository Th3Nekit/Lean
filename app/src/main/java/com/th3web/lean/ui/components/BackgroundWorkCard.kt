package com.th3web.lean.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.th3web.lean.ui.icons.LeanIcon
import com.th3web.lean.ui.icons.LeanIconImage
import kotlinx.coroutines.launch
import com.th3web.lean.LeanApp
import com.th3web.lean.ui.theme.LeanColors
import com.th3web.lean.ui.theme.leanGlass
import com.th3web.lean.ui.theme.LeanCorner
import com.th3web.lean.ui.tr

/**
 * Whether the system has agreed to stop dozing us.
 *
 * Below M there is no such thing, and the getter can throw on odd ROMs, in both cases we
 * assume the good outcome rather than nagging on a device where nothing is wrong.
 */
fun isIgnoringBatteryOptimisations(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
    val power = context.getSystemService(PowerManager::class.java) ?: return true
    return runCatching { power.isIgnoringBatteryOptimizations(context.packageName) }
        .getOrDefault(true)
}

/**
 * Opens the exemption request. Falls back to the battery-optimisation list when the direct
 * dialog is unavailable, some vendor ROMs remove that activity, and a control that
 * silently does nothing when tapped is worse than one extra step.
 */
fun requestIgnoreBatteryOptimisations(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
    // Fully qualified: several call sites also import our Settings type, where a bare
    // `Settings.` would silently resolve to the wrong one.
    val direct = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        .setData(Uri.parse("package:${context.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { context.startActivity(direct) }.isSuccess) return
    val list = Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(list) }
}

/**
 * dontkillmyapp.com's page for this phone's vendor.
 *
 * Granting the exemption is only half the job on the ROMs people actually complain about:
 * Xiaomi, Huawei, Samsung and the rest each add their own killer on top, with its own
 * buried switch. That site is the maintained guide for exactly those steps, so the card
 * points at the vendor's page and falls back to the index for anything unlisted.
 */
fun dontKillMyAppUrl(): String {
    val vendor = Build.MANUFACTURER.orEmpty().lowercase().trim()
    return if (vendor in DONT_KILL_MY_APP_VENDORS) {
        "https://dontkillmyapp.com/$vendor"
    } else {
        "https://dontkillmyapp.com/"
    }
}

/** The vendors that site keeps a dedicated page for. */
private val DONT_KILL_MY_APP_VENDORS = setOf(
    "asus", "blackview", "blu", "doogee", "google", "hmd global", "honor", "htc",
    "huawei", "infinix", "itel", "lenovo", "lge", "meizu", "motorola", "nokia",
    "oneplus", "oppo", "realme", "samsung", "sharp", "sony", "tecno", "unihertz",
    "vivo", "wiko", "xiaomi", "zte",
)

/**
 * The «работа в фоне» warning, shown on the home screen right under the ping pill.
 *
 * It duplicates a row that already exists in Соединение, and so: the setting
 * that decides whether the tunnel survives being backgrounded was the one nobody found.
 * Users kept reporting the tunnel dying "by itself" while the switch that prevents it sat
 * two screens deep.
 *
 * Shows nothing at all once the exemption is granted: this is a warning, not a permanent
 * fixture, and re-reads the answer on every resume, so it disappears the moment the user
 * comes back from granting it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BackgroundWorkCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val settings by LeanApp.instance.settings.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var exempt by remember { mutableStateOf(isIgnoringBatteryOptimisations(context)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                exempt = isIgnoringBatteryOptimisations(context)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // Two ways out, because the system's answer is not always the truth. On several
    // vendor ROMs isIgnoringBatteryOptimizations keeps saying false after the exemption
    // has been granted, and the whitelist that actually decides whether an app survives
    // is the vendor's own, which no API exposes, so a user who did everything the card
    // asked was left looking at it forever, with no way to say so.
    if (exempt || settings.batteryWarningHidden) return

    Surface(
        // Glass like every other card on Home; the red rim below is what carries the
        // warning, and it stays whatever the density is.
        modifier = modifier.fillMaxWidth().leanGlass(LeanCorner.Card, LeanColors.Surface),
        shape = LeanCorner.Card,
        color = Color.Transparent,
        border = BorderStroke(1.dp, LeanColors.Error.copy(alpha = 0.45f)),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LeanIconImage(LeanIcon.Power, LeanColors.Error, Modifier.size(18.dp))
                Spacer(Modifier.size(10.dp))
                Text(
                    tr("Система может усыпить Lean"),
                    style = MaterialTheme.typography.titleSmall,
                    color = LeanColors.TextPrimary,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                tr("Тогда туннель оборвётся в фоне. Отключите оптимизацию батареи для Lean."),
                style = MaterialTheme.typography.bodySmall,
                color = LeanColors.TextSecondary,
            )
            Spacer(Modifier.height(10.dp))
            // FlowRow, not Row: three labels whose length changes with the language cannot
            // be guaranteed to fit one line. A plain Row hands out width in order and
            // leaves the last child whatever remains, which on a narrow screen was a few
            // dp, enough to render «Уже сделал» one character per line.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CardAction(tr("Отключить оптимизацию"), primary = true) {
                    requestIgnoreBatteryOptimisations(context)
                }
                CardAction(tr("Инструкция"), primary = false) {
                    val site = Intent(Intent.ACTION_VIEW, Uri.parse(dontKillMyAppUrl()))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(site) }
                }
                CardAction(tr("Уже сделал"), primary = false) {
                    scope.launch {
                        LeanApp.instance.settings.setBatteryWarningHidden(true)
                    }
                }
            }
        }
    }
}

@Composable
private fun CardAction(label: String, primary: Boolean, onClick: () -> Unit) {
    Surface(
        shape = LeanCorner.ValuePill,
        color = if (primary) LeanColors.Error.copy(alpha = 0.16f) else LeanColors.SurfaceVariant,
        border = if (primary) BorderStroke(1.dp, LeanColors.Error.copy(alpha = 0.5f)) else null,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (primary) LeanColors.Error else LeanColors.TextSecondary,
            // A button label is one line by definition. Without this a squeezed button
            // wraps its text instead of keeping its own width, and Compose will break a
            // word down to single characters to obey the constraint it was given.
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}
