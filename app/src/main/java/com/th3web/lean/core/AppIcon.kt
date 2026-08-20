package com.th3web.lean.core

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Launcher-icon switching over the manifest's `<activity-alias>` set.
 *
 * MainActivity itself never flips enabled state: the VPN notification
 * (LeanVpnService), the QS tile (LeanTileService), and shortcuts.xml all address
 * it with explicit component intents, and a disabled component kills those.
 * The MAIN/LAUNCHER entry therefore lives on the aliases below, IconDefault is
 * manifest-enabled, the alternates manifest-disabled, and switching re-points
 * the single enabled alias.
 *
 * Alias names in the merged manifest expand against the com.th3web.lean
 * namespace (not the applicationId, which carries a .debug suffix on debug
 * builds), so the class half of each ComponentName is the literal name below
 * while the package half is the runtime [Context.getPackageName].
 */
object AppIcon {

    const val DEFAULT = "default"

    /** Setting value → fully-qualified alias name; keep in sync with AndroidManifest. */
    private val ALIASES: List<Pair<String, String>> = listOf(
        DEFAULT to "com.th3web.lean.IconDefault",
        "accent" to "com.th3web.lean.IconAccent",
        "pack03" to "com.th3web.lean.IconPack03",
        "pack04" to "com.th3web.lean.IconPack04",
        "outline" to "com.th3web.lean.IconOutline",
        "pack06" to "com.th3web.lean.IconPack06",
        "sunset" to "com.th3web.lean.IconSunset",
        "pack08" to "com.th3web.lean.IconPack08",
        "pack09" to "com.th3web.lean.IconPack09",
        "obsidian" to "com.th3web.lean.IconObsidian",
        "frost" to "com.th3web.lean.IconFrost",
        "neon" to "com.th3web.lean.IconNeon",
        "pack13" to "com.th3web.lean.IconPack13",
        "pack14" to "com.th3web.lean.IconPack14",
        "pack15" to "com.th3web.lean.IconPack15",
        "pack16" to "com.th3web.lean.IconPack16",
        "pack17" to "com.th3web.lean.IconPack17",
        "pack18" to "com.th3web.lean.IconPack18",
        "pack19" to "com.th3web.lean.IconPack19",
        "pack20" to "com.th3web.lean.IconPack20",
        "pack21" to "com.th3web.lean.IconPack21",
        "pack23" to "com.th3web.lean.IconPack23",
        "black" to "com.th3web.lean.IconBlack",
    )

    /** The fully-qualified alias for [variant], or null when this build has no such one. */
    internal fun aliasFor(variant: String): String? =
        ALIASES.firstOrNull { it.first == variant }?.second

    /** Every variant this build can switch to, default first. */
    internal fun variants(): List<String> = ALIASES.map { it.first }

    /**
     * Enables exactly the [variant]'s launcher alias and disables the rest.
     * The chosen alias is enabled first so there is never a moment with zero
     * launcher entries; DONT_KILL_APP keeps the process (and the running VPN)
     * alive. Components already in the wanted state are skipped, so re-applying
     * the same choice is free. MainActivity does not re-assert this on
     * launch (it would read a stale `initial` snapshot and revert a live
     * in-process change), so a backup restore that changes appIcon must call
     * [apply] explicitly. An unknown [variant] normalizes to [DEFAULT].
     */
    fun apply(context: Context, variant: String) {
        val pm = context.packageManager
        val pkg = context.packageName
        val target = ALIASES.firstOrNull { it.first == variant } ?: ALIASES.first()
        setState(pm, ComponentName(pkg, target.second), enabled = true, manifestEnabled = target.first == DEFAULT)
        ALIASES.forEach { (key, alias) ->
            if (alias != target.second) {
                setState(pm, ComponentName(pkg, alias), enabled = false, manifestEnabled = key == DEFAULT)
            }
        }
    }

    /**
     * Writes the wanted component state, treating COMPONENT_ENABLED_STATE_DEFAULT
     * as the alias's manifest android:enabled value, a fresh install on the
     * default icon stays write-free (no launcher-refresh churn).
     */
    private fun setState(pm: PackageManager, component: ComponentName, enabled: Boolean, manifestEnabled: Boolean) {
        val inSync = when (pm.getComponentEnabledSetting(component)) {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> manifestEnabled == enabled
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> enabled
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> !enabled
            else -> false
        }
        if (inSync) return
        // Defensive: a bad ComponentName (manifest/alias drift) must never crash the
        // app over a cosmetic icon switch.
        runCatching {
            pm.setComponentEnabledSetting(
                component,
                if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}
