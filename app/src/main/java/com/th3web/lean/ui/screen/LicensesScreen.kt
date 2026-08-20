package com.th3web.lean.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.th3web.lean.ui.components.LeanGroup
import com.th3web.lean.ui.components.LeanNavItem
import com.th3web.lean.ui.components.LeanSectionLabel
import com.th3web.lean.ui.icons.LeanIcon
import com.th3web.lean.ui.icons.LeanIconImage
import com.th3web.lean.ui.theme.LeanColors
import com.th3web.lean.ui.theme.leanBackground
import com.th3web.lean.ui.tr

/**
 * One bundled licence: what it covers, and the asset holding its text.
 *
 * The texts ship inside the APK because the licences require it. GPL-3.0 asks that a
 * copy travel with the program, and BSD asks for the notice to be reproduced in
 * redistributions; a link to a repository is not that.
 */
private data class BundledLicense(
    val component: String,
    val author: String,
    val license: String,
    val asset: String,
)

private val BUNDLED = listOf(
    BundledLicense("Lean", "Th3Nekit", "GPL-3.0-or-later", "lean.txt"),
    BundledLicense("libcore (sing-box)", "MatsuriDayo", "GPL-3.0-or-later", "nekobox-libcore.txt"),
    BundledLicense("sing-box", "nekohasekai", "GPL-3.0-or-later", "sing-box.txt"),
    BundledLicense("AmneziaWG-Go", "Amnezia VPN", "MIT", "amneziawg-go.txt"),
    BundledLicense("amneziawg-android", "Amnezia VPN", "Apache-2.0", "amneziawg-android.txt"),
    BundledLicense("Xray-core", "XTLS", "MPL-2.0", "xray-core.txt"),
    BundledLicense("NaiveProxy", "klzgrad", "BSD-3-Clause", "naiveproxy.txt"),
    BundledLicense("Mieru", "enfein", "GPL-3.0", "mieru.txt"),
    BundledLicense("olcRTC", "OpenLibreCommunity", "WTFPL", "olcrtc.txt"),
    BundledLicense("gomobile", "The Go Authors", "BSD-3-Clause", "gomobile.txt"),
    BundledLicense("Onest", "Nikita Chelombitko", "OFL-1.1", "font-onest.txt"),
    BundledLicense("Unbounded", "Nikolas Type", "OFL-1.1", "font-unbounded.txt"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    var open by remember { mutableStateOf<BundledLicense?>(null) }
    val current = open

    Scaffold(
        containerColor = LeanColors.Background,
        modifier = Modifier.leanBackground(),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { if (current == null) onBack() else open = null }) {
                        LeanIconImage(
                            LeanIcon.Back,
                            tint = LeanColors.TextPrimary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                },
                title = { Text(current?.component ?: tr("Лицензии")) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = LeanColors.Background,
                    scrolledContainerColor = LeanColors.Surface,
                    titleContentColor = LeanColors.TextPrimary,
                    navigationIconContentColor = LeanColors.TextPrimary,
                ),
            )
        },
    ) { padding ->
        if (current == null) {
            LicenseList(padding) { open = it }
        } else {
            LicenseText(current, padding)
        }
    }
}

@Composable
private fun LicenseList(padding: PaddingValues, onOpen: (BundledLicense) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        item {
            Text(
                tr("Тексты лицензий всех компонентов, входящих в сборку."),
                color = LeanColors.TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
        item { LeanSectionLabel(tr("Компоненты")) }
        item {
            LeanGroup {
                BUNDLED.forEach { entry ->
                    LeanNavItem(
                        icon = LeanIcon.Info,
                        tint = LeanColors.TextSecondary,
                        title = entry.component,
                        subtitle = "${entry.author} · ${entry.license}",
                    ) { onOpen(entry) }
                }
            }
        }
        item { Column(Modifier.height(24.dp)) {} }
    }
}

@Composable
private fun LicenseText(entry: BundledLicense, padding: PaddingValues) {
    val context = LocalContext.current
    var text by remember(entry.asset) { mutableStateOf<String?>(null) }
    LaunchedEffect(entry.asset) {
        text = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open("licenses/${entry.asset}").bufferedReader().use { it.readText() }
            }.getOrElse { tr("Не удалось прочитать текст лицензии") }
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text ?: tr("Загрузка…"),
            color = LeanColors.TextSecondary,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth(),
        )
        Column(Modifier.height(24.dp)) {}
    }
}
