package com.th3web.lean.ui.screen

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.th3web.lean.LeanApp
import com.th3web.lean.data.PerAppMode
import com.th3web.lean.data.Settings
import com.th3web.lean.ui.components.SegmentedControl
import com.th3web.lean.ui.tr
import com.th3web.lean.ui.icons.AppIcon
import com.th3web.lean.ui.icons.LeanIcon
import com.th3web.lean.ui.icons.LeanIconImage
import com.th3web.lean.ui.theme.LeanColors
import com.th3web.lean.ui.theme.LeanCorner
import com.th3web.lean.ui.theme.leanGlass
import com.th3web.lean.ui.theme.LeanType

private data class AppEntry(val pkg: String, val label: String, val system: Boolean)

private suspend fun loadAppEntries(context: Context): List<AppEntry> = withContext(Dispatchers.IO) {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val launcherPackages = pm.queryIntentActivities(intent, 0)
        .mapNotNull { it.activityInfo?.packageName }
        .toHashSet()
    pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        .asSequence()
        .filter { packageInfo ->
            val hasInternet =
                packageInfo.requestedPermissions?.contains(android.Manifest.permission.INTERNET) == true
            packageInfo.packageName != context.packageName &&
                (hasInternet || packageInfo.packageName in launcherPackages)
        }
        .map { packageInfo ->
            val applicationInfo = packageInfo.applicationInfo
            AppEntry(
                pkg = packageInfo.packageName,
                label = applicationInfo?.loadLabel(pm)?.toString() ?: packageInfo.packageName,
                system = applicationInfo != null &&
                    (applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
            )
        }
        .distinctBy(AppEntry::pkg)
        .sortedBy { it.label.lowercase() }
        .toList()
}

/**
 * Per-app split tunnel. Lists every installed package holding the internet
 * permission (QUERY_ALL_PACKAGES in the manifest, background/companion apps
 * generate traffic without a launcher entry), unioned with launcher apps, and
 * writes the selection into [PerAppMode]/perAppPackages, which
 * [com.th3web.lean.core.SingBoxConfig] emits as include_package/exclude_package and
 * the VpnService applies via add(Allowed|Disallowed)Application.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerAppScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = LeanApp.instance.settings
    // The HOT state, like every other settings screen, not repo.flow with a synthetic
    // Settings() default. The cold flow's first frames rendered a fabricated "Выкл /
    // весь трафик через VPN", the opposite of the live routing, and a checkbox tapped in
    // that window did a read-modify-write over the fake empty set, wiping real picks.
    val settings by repo.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }

    // Re-enumerate installed packages when the screen resumes so an app
    // installed/removed while it was backgrounded is reflected.
    var refreshTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refreshTick++ }

    var apps by remember { mutableStateOf(emptyList<AppEntry>()) }
    LaunchedEffect(refreshTick) {
        apps = loadAppEntries(context.applicationContext)
    }

    val mode = settings.perAppMode
    val selected = settings.perAppPackages
    val modes = listOf(tr("Выкл"), tr("Только"), tr("Кроме"))
    val modeIndex = when (mode) {
        PerAppMode.OFF -> 0
        PerAppMode.INCLUDE -> 1
        PerAppMode.EXCLUDE -> 2
    }
    val filtered = apps.filter {
        query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true)
    }

    Scaffold(
        containerColor = LeanColors.Background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        LeanIconImage(LeanIcon.Back, tint = LeanColors.TextPrimary, modifier = Modifier.size(22.dp))
                    }
                },
                // No `fontWeight` override: the parameter beats the style, so it would pin
                // this line while «Жирность» moved everything around it. TopAppBar's own
                // titleLarge is already the bold display face this asked for.
                title = { Text(tr("Раздельный туннель")) },
                actions = {
                    if (mode != PerAppMode.OFF) {
                        // Select every currently-visible (filtered) app, merged with the
                        // existing selection so a search filter doesn't drop hidden picks.
                        TextButton(
                            onClick = {
                                val next = selected + filtered.map { it.pkg }
                                scope.launch { repo.setPerAppPackages(next) }
                            },
                            enabled = filtered.isNotEmpty(),
                        ) {
                            Text(tr("Выбрать все"), color = LeanColors.Accent)
                        }
                        // Mirror of «Выбрать все»: scoped to what is visible. It used to
                        // clear the entire selection, so searching for one app and tapping
                        // it destroyed every other pick, with no confirmation and no undo,
                        // silently changing which apps get tunnelled on the next connect.
                        val visible = remember(filtered) { filtered.map { it.pkg }.toSet() }
                        TextButton(
                            onClick = { scope.launch { repo.setPerAppPackages(selected - visible) } },
                            enabled = selected.any { it in visible },
                        ) {
                            Text(tr("Снять все"), color = LeanColors.TextSecondary)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = LeanColors.Background,
                    scrolledContainerColor = LeanColors.Surface,
                    titleContentColor = LeanColors.TextPrimary,
                    navigationIconContentColor = LeanColors.TextPrimary,
                    actionIconContentColor = LeanColors.TextSecondary,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
        ) {
            SegmentedControl(modes, modeIndex, { i ->
                scope.launch {
                    repo.setPerAppMode(
                        when (i) {
                            1 -> PerAppMode.INCLUDE
                            2 -> PerAppMode.EXCLUDE
                            else -> PerAppMode.OFF
                        },
                    )
                }
            })
            Spacer(Modifier.height(10.dp))
            if (mode == PerAppMode.OFF) {
                Text(
                    tr("Весь трафик идёт через VPN. Выберите режим, чтобы настроить отдельные приложения."),
                    color = LeanColors.TextTertiary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                val hint = if (mode == PerAppMode.INCLUDE) tr("Через VPN пойдут только отмеченные приложения")
                else tr("Отмеченные приложения пойдут мимо VPN")
                Text(
                    hint + " · " + tr("выбрано: %d").format(selected.size),
                    color = LeanColors.TextSecondary,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { LeanIconImage(LeanIcon.Search, tint = LeanColors.TextSecondary, modifier = Modifier.size(18.dp)) },
                    placeholder = { Text(tr("Поиск приложений…"), color = LeanColors.TextTertiary) },
                    shape = LeanCorner.Input,
                )
                Spacer(Modifier.height(10.dp))
                if (apps.isEmpty()) {
                    Text(tr("Загрузка списка приложений…"), color = LeanColors.TextTertiary, style = MaterialTheme.typography.bodyMedium)
                }
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Keyed by package so a row keeps its identity across a search or a
                    // selection change, without it every list update re-creates the rows,
                    // and each one re-fetches an icon it already had.
                    items(filtered.size, key = { filtered[it].pkg }) { idx ->
                        val a = filtered[idx]
                        AppRow(a.label, a.pkg, a.system, a.pkg in selected) {
                            val next = if (a.pkg in selected) selected - a.pkg else selected + a.pkg
                            scope.launch { repo.setPerAppPackages(next) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRow(label: String, pkg: String, system: Boolean, checked: Boolean, onToggle: () -> Unit) {
    val rowShape = MaterialTheme.shapes.medium
    Card(
        // Painted by [leanGlass], so the Card's own container goes transparent: it falls
        // back to exactly this fill when «Стекло» is off, and honours «Плотность стекла»
        // when it is on. Filling the container here left the whole list opaque at every
        // density, on a screen that can be hundreds of rows long.
        modifier = Modifier.fillMaxWidth().leanGlass(rowShape, LeanColors.Surface),
        shape = rowShape,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Loaded per row rather than with the list: several hundred packages qualify
            // for this screen, and decoding every icon up front is what would make it slow
            // to open and expensive to keep open. See [AppIcons].
            AppIcon(pkg)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                // rowTitle rather than bodyLarge + a hardcoded Medium: same family and size,
                // and it is the role every other list row in the app titles itself with,
                // which is also what lets «Жирность» reach this line at all.
                Text(label, color = LeanColors.TextPrimary, style = LeanType.rowTitle)
                Text(
                    if (system) pkg + " · " + tr("системное") else pkg,
                    color = LeanColors.TextTertiary,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Spacer(Modifier.width(10.dp))
            Checkbox(checked = checked, onCheckedChange = null)
        }
    }
}
