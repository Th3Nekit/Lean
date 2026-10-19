package com.th3web.lean.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.th3web.lean.LeanApp
import com.th3web.lean.data.ProfileRepository
import com.th3web.lean.data.net.Http
import com.th3web.lean.data.net.SubscriptionHwidRequired
import com.th3web.lean.data.model.Subscription
import com.th3web.lean.ui.theme.LeanCorner
import com.th3web.lean.ui.theme.LeanType
import com.th3web.lean.ui.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal fun hwidSubscriptionName(retry: ProfileRepository.HwidRetry, stored: Subscription?): String =
    retry.name.takeIf { it.isNotBlank() && it != retry.url }
        ?: stored?.displayName?.takeIf { it.isNotBlank() && it != stored.url }
        ?: runCatching { java.net.URI(retry.url).host }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: tr("Без названия")

/** The existing HWID notice, shared by Home and Servers with a targeted retry. */
@Composable
fun SubscriptionHwidHint(showGeneralHint: Boolean = false) {
    val app = LeanApp.instance
    val settings by app.settings.flow.collectAsStateWithLifecycle(initialValue = app.settings.state.value)
    val store by app.profiles.state.collectAsStateWithLifecycle()
    val failures by app.profiles.hwidFailures.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var dismissedLegacy by remember { mutableStateOf(emptySet<String>()) }
    // Old stores can already contain the panel's placeholder from before rejection
    // detection. Offer the same action without deleting a saved URL or real servers.
    val legacy = store.subscriptions.firstOrNull { sub ->
        val profiles = store.profiles.filter { it.subscriptionId == sub.id }
        sub.id !in dismissedLegacy && profiles.isNotEmpty() &&
            profiles.all { SubscriptionHwidRequired.isRejectionName(it.name) }
    }
    val retry = failures.values.firstOrNull() ?: legacy?.let {
        ProfileRepository.HwidRetry(it.id, it.name, it.url)
    }
    if (retry == null && !busy && !error && !(showGeneralHint && !settings.sendHwid && !settings.hwidHintHidden)) return
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(LeanCorner.Card).background(scheme.errorContainer)
            .border(1.dp, scheme.error, LeanCorner.Card).padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            retry?.let { request -> tr("Сервер подписки «%s» отклонил запрос").format(
                hwidSubscriptionName(request, store.subscriptions.firstOrNull { it.id == request.subscriptionId }),
            ) }
                ?: tr("В подписке «Приложение не поддерживается»?"),
            style = LeanType.rowTitle, color = scheme.onErrorContainer,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            tr("Некоторые панели требуют идентификатор устройства. Отправка HWID передаст " +
                "серверу подписки идентификатор этого устройства, модель телефона и версию Android. " +
                "Если лимит устройств исчерпан или приложение не разрешено провайдером, обратитесь к нему."),
            style = LeanType.meta, color = scheme.onErrorContainer,
        )
        if (error) {
            Spacer(Modifier.height(6.dp))
            Text(tr("Подписку загрузить не удалось. Проверьте доступ и повторите."),
                style = LeanType.meta, color = scheme.onErrorContainer)
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    error = false
                    scope.launch {
                        try {
                            app.settings.setSendHwid(true)
                            // The settings collector mirrors this asynchronously; this
                            // explicit retry must use the setting the user just chose.
                            Http.sendHwid = true
                            val results = if (retry != null) {
                                listOf(app.profiles.retryHwidFailure(retry))
                            } else {
                                store.subscriptions.map { app.profiles.updateSubscription(it.id) }
                            }
                            error = results.any { it.isFailure }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = true
                        } finally {
                            busy = false
                        }
                    }
                },
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(containerColor = scheme.error, contentColor = scheme.onError),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(tr(if (busy) "Обновление…" else if (settings.sendHwid) "Повторить загрузку"
                    else "Включить HWID и повторить"), style = LeanType.meta)
            }
            Spacer(Modifier.width(8.dp))
            TextButton(enabled = !busy, onClick = {
                if (retry != null) {
                    app.profiles.dismissHwidFailure(retry.subscriptionId)
                    dismissedLegacy = dismissedLegacy + retry.subscriptionId
                }
                error = false
                scope.launch { app.settings.setHwidHintHidden(true) }
            }) {
                Text(tr("Не сейчас"), style = LeanType.meta, color = scheme.onErrorContainer)
            }
        }
    }
    Spacer(Modifier.height(12.dp))
}
