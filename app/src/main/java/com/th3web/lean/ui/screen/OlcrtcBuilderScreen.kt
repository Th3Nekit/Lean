package com.th3web.lean.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.th3web.lean.LeanApp
import com.th3web.lean.data.model.Outbound
import com.th3web.lean.data.model.Profile
import com.th3web.lean.data.parse.ShareLinks
import com.th3web.lean.ui.components.LeanGroup
import com.th3web.lean.ui.components.LeanSectionLabel
import com.th3web.lean.ui.components.SegmentedControl
import com.th3web.lean.ui.components.rememberLeanClipboard
import com.th3web.lean.ui.theme.LeanColors
import com.th3web.lean.ui.theme.LeanCorner
import com.th3web.lean.ui.tr

/**
 * Builds one olcRTC server, either from a pasted line or by hand.
 *
 * It gets a screen of its own because olcRTC is the one protocol here that cannot be
 * described by «host:port + credentials». There is no server to dial at all: the client
 * joins a meeting room on Jitsi / Telemost / WB Stream and meets the far side inside, so
 * what has to be entered is a room, a shared key, a provider and a transport, four
 * fields the generic add-server sheet has no shape for.
 *
 * The paste box on top and the fields below are the same data,: pasting
 * fills the fields, so a line from a bot can be checked and corrected before it is saved
 * rather than being an opaque blob. The link notation is upstream's published one, so a
 * line minted anywhere works here.
 */
@Composable
fun OlcrtcBuilderScreen(onBack: () -> Unit) {
    val app = LeanApp.instance
    val scope = rememberCoroutineScope()
    val clipboard = rememberLeanClipboard()

    var link by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var provider by rememberSaveable { mutableStateOf(PROVIDERS.first()) }
    var transport by rememberSaveable { mutableStateOf(TRANSPORTS.first()) }
    var room by rememberSaveable { mutableStateOf("") }
    var key by rememberSaveable { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }

    fun applyLink(raw: String) {
        val parsed = ShareLinks.parse(raw.trim())?.outbound as? Outbound.Olcrtc
        if (parsed == null) {
            status = tr("Строка не разобрана — проверьте формат.")
            return
        }
        provider = parsed.provider.takeIf { it in PROVIDERS } ?: provider
        transport = parsed.transport.takeIf { it in TRANSPORTS } ?: transport
        room = parsed.roomId
        key = parsed.key
        name = ShareLinks.parse(raw.trim())?.name.orEmpty()
        status = tr("Готово — проверьте поля ниже и сохраните.")
    }

    // A key is 32 bytes of hex and the room is what identifies the meeting; without
    // either there is nothing to save, and saying so up front beats a profile that fails
    // at connect time.
    val keyLooksRight = key.length == KEY_HEX_LENGTH && key.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
    val canSave = room.isNotBlank() && keyLooksRight

    HubScaffold(tr("Новый сервер olcRTC"), onBack) {
        LeanSectionLabel(tr("Одной строкой"))
        LeanGroup {
            Column(Modifier.padding(14.dp)) {
                Text(
                    tr("Вставьте строку из бота — поля ниже заполнятся сами."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LeanColors.TextSecondary,
                )
                Spacer(Modifier.height(10.dp))
                LeanField(
                    value = link,
                    onValueChange = { link = it },
                    placeholder = "olcrtc://jitsi?datachannel@…",
                    minLines = 2,
                )
                Spacer(Modifier.height(10.dp))
                Row {
                    OutlinedButton(
                        onClick = {
                            clipboard.paste {
                                link = it
                                applyLink(it)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = LeanCorner.Button,
                        border = BorderStroke(1.dp, LeanColors.Outline),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = LeanColors.TextPrimary),
                    ) { Text(tr("Из буфера")) }
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    FilledTonalButton(
                        onClick = { applyLink(link) },
                        enabled = link.isNotBlank(),
                        modifier = Modifier.weight(1f),
                        shape = LeanCorner.Button,
                    ) { Text(tr("Разобрать")) }
                }
                status?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.labelMedium, color = LeanColors.TextSecondary)
                }
            }
        }

        LeanSectionLabel(tr("Или вручную"))
        LeanGroup {
            Column(Modifier.padding(14.dp)) {
                Text(tr("Сервис звонков"), style = MaterialTheme.typography.labelLarge, color = LeanColors.TextSecondary)
                Spacer(Modifier.height(6.dp))
                SegmentedControl(
                    options = PROVIDERS.map { it.replaceFirstChar(Char::titlecase) },
                    selectedIndex = PROVIDERS.indexOf(provider).coerceAtLeast(0),
                    onSelect = { provider = PROVIDERS[it] },
                )

                Spacer(Modifier.height(14.dp))
                Text(tr("Транспорт"), style = MaterialTheme.typography.labelLarge, color = LeanColors.TextSecondary)
                Spacer(Modifier.height(6.dp))
                SegmentedControl(
                    options = TRANSPORTS.map { it.removeSuffix("channel") },
                    selectedIndex = TRANSPORTS.indexOf(transport).coerceAtLeast(0),
                    onSelect = { transport = TRANSPORTS[it] },
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    tr("datachannel — обычный выбор. Остальные прячут данные в видеопоток: медленнее, но незаметнее."),
                    style = MaterialTheme.typography.labelMedium,
                    color = LeanColors.TextTertiary,
                )

                Spacer(Modifier.height(14.dp))
                Text(tr("Комната"), style = MaterialTheme.typography.labelLarge, color = LeanColors.TextSecondary)
                Spacer(Modifier.height(6.dp))
                LeanField(
                    value = room,
                    onValueChange = { room = it },
                    placeholder = if (provider == "jitsi") "https://meet.example.org/room" else "room-01",
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (provider == "jitsi") {
                        tr("Для Jitsi — полная ссылка на комнату. Должна совпадать с серверной.")
                    } else {
                        tr("Идентификатор комнаты. Должен совпадать с серверным.")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = LeanColors.TextTertiary,
                )

                Spacer(Modifier.height(14.dp))
                Text(tr("Ключ шифрования"), style = MaterialTheme.typography.labelLarge, color = LeanColors.TextSecondary)
                Spacer(Modifier.height(6.dp))
                LeanField(
                    value = key,
                    onValueChange = { key = it.trim() },
                    placeholder = tr("64 символа hex"),
                )
                if (key.isNotEmpty() && !keyLooksRight) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        tr("Ключ — ровно 64 hex-символа. Сейчас: %d.").format(key.length),
                        style = MaterialTheme.typography.labelMedium,
                        color = LeanColors.Error,
                    )
                }

                Spacer(Modifier.height(14.dp))
                Text(tr("Название"), style = MaterialTheme.typography.labelLarge, color = LeanColors.TextSecondary)
                Spacer(Modifier.height(6.dp))
                LeanField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = tr("Необязательно"),
                )
            }
        }

        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            FilledTonalButton(
                onClick = {
                    val profile = Profile(
                        name = name.ifBlank { "olcRTC ${provider.replaceFirstChar(Char::titlecase)}" },
                        outbound = Outbound.Olcrtc(
                            server = room.substringAfter("://").substringBefore('/').substringBefore(':')
                                .ifBlank { room },
                            provider = provider,
                            transport = transport,
                            roomId = room.trim(),
                            key = key.trim(),
                        ),
                    )
                    scope.launch {
                        app.profiles.addProfiles(listOf(profile))
                        onBack()
                    }
                },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth(),
                shape = LeanCorner.Button,
            ) { Text(tr("Сохранить сервер")) }
        }
    }
}

/** The app's text field, so this screen matches every other input in it. */
@Composable
private fun LeanField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    minLines: Int = 1,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        minLines = minLines,
        maxLines = if (minLines > 1) 4 else 2,
        placeholder = { Text(placeholder, color = LeanColors.TextTertiary) },
        shape = LeanCorner.Input,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = LeanColors.Accent,
            unfocusedBorderColor = LeanColors.Outline,
            focusedTextColor = LeanColors.TextPrimary,
            unfocusedTextColor = LeanColors.TextPrimary,
            cursorColor = LeanColors.Accent,
        ),
    )
}

/** Auth providers olcrtc ships. Order is the order the segmented control shows. */
private val PROVIDERS = listOf("jitsi", "telemost", "wbstream")

/** Transports, `datachannel` first because upstream recommends starting there. */
private val TRANSPORTS = listOf("datachannel", "vp8channel", "seichannel", "videochannel")

/** 32 bytes, hex, what `openssl rand -hex 32` produces. */
private const val KEY_HEX_LENGTH = 64
