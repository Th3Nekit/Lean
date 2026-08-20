package com.th3web.lean.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.th3web.lean.LeanApp
import com.th3web.lean.core.AppIcon
import com.th3web.lean.data.Backup
import com.th3web.lean.data.BackupPayload
import com.th3web.lean.ui.tr
import com.th3web.lean.ui.components.LeanSectionLabel
import com.th3web.lean.ui.icons.LeanIcon
import com.th3web.lean.ui.icons.LeanIconImage
import com.th3web.lean.ui.theme.LeanColors
import com.th3web.lean.ui.theme.LeanCorner

/**
 * Backup / restore: exports {subscriptions + servers +
 * settings} as a password-encrypted file (AES-GCM, PBKDF2 key) via the Storage
 * Access Framework, and restores it with a Merge / Replace choice. Crypto and
 * (de)serialization live in [Backup]; this screen only drives SAF + dialogs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(onBack: () -> Unit) {
    val app = LeanApp.instance
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store by app.profiles.state.collectAsStateWithLifecycle()

    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    /** Decrypted payload awaiting the Merge / Replace decision. */
    var pendingImport by remember { mutableStateOf<BackupPayload?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            error = null; info = null
            // The password lives in composition state, and the file picker is another
            // activity, if the system recreated this one while the picker was up, the
            // field came back empty. SAF has already created the document by now, so
            // without this the user is left with an empty file and a generic "не удалось
            // сохранить" that gives them no idea the password was the problem.
            if (password.isBlank()) {
                error = tr("Введите пароль ещё раз — копия не сохранена")
                return@launch
            }
            val result = runCatching {
                val settings = app.settings.flow.first()
                // PBKDF2 (100k iterations) is CPU-bound, keep it off the main thread.
                val json = withContext(Dispatchers.Default) {
                    Backup.export(app.profiles.state.value, settings, password)
                }
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(json.toByteArray(Charsets.UTF_8))
                    } ?: throw IllegalStateException("openOutputStream returned null")
                }
            }
            if (result.isSuccess) info = tr("Резервная копия сохранена")
            else error = tr("Не удалось сохранить файл")
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            error = null; info = null
            // Same recreation window as the export path, decrypting with an empty
            // password would just report «Неверный пароль», which is misleading.
            if (password.isBlank()) {
                error = tr("Введите пароль ещё раз")
                return@launch
            }
            val text = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)
                        ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                }
            }.getOrNull()
            if (text == null) {
                error = tr("Не удалось прочитать файл")
                return@launch
            }
            runCatching { withContext(Dispatchers.Default) { Backup.import(text, password) } }
                .onSuccess { pendingImport = it }
                .onFailure { e ->
                    error = when (e) {
                        is Backup.WrongPassword -> tr("Неверный пароль")
                        is Backup.NotALeanBackup -> tr("Это не резервная копия Lean")
                        is Backup.TooNew -> tr("Копия создана более новой версией Lean — обновите приложение")
                        else -> tr("Файл повреждён или не читается")
                    }
                }
        }
    }

    HubScaffold(tr("Резервная копия"), onBack) {
        Spacer(Modifier.height(12.dp))
        Text(
            tr("Экспорт сохранит подписки, серверы и настройки в зашифрованный файл. Импорт восстановит их на этом или другом устройстве."),
            color = LeanColors.TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        LeanSectionLabel(tr("Пароль"))
        OutlinedTextField(
            value = password,
            // Clear stale status so an old "saved"/"error" line doesn't look current
            // when the user edits the password for a different file.
            onValueChange = { password = it; error = null; info = null },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            leadingIcon = { LeanIconImage(LeanIcon.Shield, tint = LeanColors.TextSecondary, modifier = Modifier.size(19.dp)) },
            placeholder = { Text(tr("Пароль"), color = LeanColors.TextTertiary) },
            shape = LeanCorner.Input,
        )
        Text(
            tr("Пароль обязателен — им шифруется файл (AES-256)."),
            color = LeanColors.TextTertiary,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 4.dp, top = 9.dp),
        )

        LeanSectionLabel(tr("Действия"))
        FilledTonalButton(
            onClick = { exportLauncher.launch(Backup.suggestedFileName()) },
            enabled = password.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
        ) {
            LeanIconImage(LeanIcon.Cloud, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(tr("Экспортировать"))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { importLauncher.launch(arrayOf("*/*")) },
            enabled = password.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
        ) {
            LeanIconImage(LeanIcon.Refresh, tint = LeanColors.TextSecondary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(tr("Импортировать"))
        }

        error?.let {
            Spacer(Modifier.height(14.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 4.dp))
        }
        info?.let {
            Spacer(Modifier.height(14.dp))
            Text(it, color = LeanColors.Connected, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 4.dp))
        }

        Spacer(Modifier.height(14.dp))
        Text(
            tr("Сейчас: %d серверов · %d подписок").format(store.profiles.size, store.subscriptions.size),
            color = LeanColors.TextTertiary,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }

    pendingImport?.let { payload ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text(tr("Импорт резервной копии")) },
            text = {
                Column {
                    Text(
                        tr("Серверов: %d · Подписок: %d").format(payload.store.profiles.size, payload.store.subscriptions.size),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        tr("Объединить — добавить к текущим данным. Заменить — полностью перезаписать серверы и настройки."),
                        color = LeanColors.TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingImport = null
                    scope.launch {
                        val added = app.profiles.mergeStore(payload.store)
                        info = tr("Добавлено серверов: %d, подписок: %d")
                            .format(added.addedProfiles, added.addedSubscriptions)
                        error = null
                    }
                }) { Text(tr("Объединить"), color = LeanColors.Accent) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { pendingImport = null }) {
                        Text(tr("Отмена"), color = LeanColors.TextSecondary)
                    }
                    TextButton(onClick = {
                        pendingImport = null
                        scope.launch {
                            // Wrap in runCatching: setAll (DataStore.edit) can throw
                            // IOException → an unwrapped coroutine crash (CrashReporter →
                            // process death) with a half-applied restore. Surface a
                            // visible error instead, mirroring the export/import paths.
                            val ok = runCatching {
                                app.profiles.replaceStore(payload.store)
                                payload.settings?.let {
                                    app.settings.setAll(it, payload.settingsFields)
                                    // setAll persists appIcon but the launcher alias is not
                                    // re-asserted on launch; apply it now so a restored icon
                                    // actually takes effect (Replace is a deliberate action
                                    // with the up-to-date value).
                                    if (payload.settingsFields == null ||
                                        "appIcon" in payload.settingsFields
                                    ) {
                                        AppIcon.apply(context, it.appIcon)
                                    }
                                }
                            }.isSuccess
                            if (ok) { info = tr("Данные заменены"); error = null }
                            else { error = tr("Не удалось применить данные"); info = null }
                        }
                    }) { Text(tr("Заменить"), color = MaterialTheme.colorScheme.error) }
                }
            },
        )
    }
}
