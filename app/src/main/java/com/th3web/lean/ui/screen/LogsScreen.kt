package com.th3web.lean.ui.screen

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.th3web.lean.core.CoreManager
import com.th3web.lean.data.net.CrashReporter
import com.th3web.lean.data.net.ManualDiagnosticsResult
import com.th3web.lean.ui.theme.leanGlass
import com.th3web.lean.ui.theme.LeanColors
import com.th3web.lean.ui.tr

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(onBack: () -> Unit) {
    val logs by CoreManager.logs.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sending by remember { mutableStateOf(false) }

    // Keyed on the list identity, not its size: the buffer is capped, so once it
    // saturates the size is a constant forever and a size-keyed effect never fires
    // again, auto-follow died exactly when the log got busy enough to need it, which
    // read as "the log froze" while lines kept arriving.
    LaunchedEffect(logs) {
        if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
    }

    Scaffold(
        containerColor = LeanColors.Background,
        topBar = {
            TopAppBar(
                // tr(), not stringResource(): the app has its own language switch, and an
                // Android string resource follows the system locale instead. With the app
                // set to English on a Russian phone this header alone stayed «Логи».
                title = { Text(tr("Логи")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = tr("Назад"),
                            tint = LeanColors.TextPrimary,
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            sending = true
                            scope.launch {
                                val result = CrashReporter.sendDiagnostics(context)
                                sending = false
                                Toast.makeText(
                                    context,
                                    tr(
                                        if (result == ManualDiagnosticsResult.Sent) {
                                            "Диагностика отправлена"
                                        } else {
                                            "Не удалось отправить диагностику"
                                        },
                                    ),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                        enabled = logs.isNotEmpty() && !sending,
                    ) {
                        Text(tr(if (sending) "Отправка…" else "Отправить"))
                    }
                    IconButton(
                        onClick = {
                            val text = logs.joinToString("\n")
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "Lean logs")
                                putExtra(Intent.EXTRA_TEXT, text)
                            }
                            runCatching {
                                context.startActivity(
                                    Intent.createChooser(send, tr("Поделиться логом")),
                                )
                            }
                        },
                        enabled = logs.isNotEmpty(),
                    ) {
                        Icon(Icons.Outlined.Share, contentDescription = tr("Поделиться логом"), tint = LeanColors.TextSecondary)
                    }
                    IconButton(onClick = { CoreManager.clearLogs() }) {
                        Icon(Icons.Outlined.DeleteSweep, contentDescription = tr("Очистить"), tint = LeanColors.TextSecondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = LeanColors.Background,
                    titleContentColor = LeanColors.TextPrimary,
                ),
            )
        },
    ) { padding ->
        if (logs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(tr("Логи появятся здесь"), color = LeanColors.TextSecondary)
            }
        } else {
            Card(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .leanGlass(MaterialTheme.shapes.large, MaterialTheme.colorScheme.surfaceContainerLow),
                shape = MaterialTheme.shapes.large,
                // Transparent: [leanGlass] above paints it, falling back to this same
                // surface when «Стекло» is off.
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    items(logs) { line ->
                        Text(
                            line,
                            color = LeanColors.TextSecondary,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}
