package com.th3web.lean.ui.screen.appearance

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.th3web.lean.LeanApp
import com.th3web.lean.data.AppearanceProfile
import com.th3web.lean.data.AppearancePresets
import com.th3web.lean.ui.Routes
import com.th3web.lean.ui.components.LeanDivider
import com.th3web.lean.ui.components.LeanGroup
import com.th3web.lean.ui.components.LeanNavItem
import com.th3web.lean.ui.components.LeanToggleItem
import com.th3web.lean.ui.icons.LeanIcon
import com.th3web.lean.ui.screen.AppearanceHeader
import com.th3web.lean.ui.screen.HubScaffold
import com.th3web.lean.ui.screen.KnobHint
import com.th3web.lean.ui.screen.RadioRow
import com.th3web.lean.ui.screen.rememberLook
import com.th3web.lean.ui.theme.LeanColors
import com.th3web.lean.ui.tr

/**
 * «Экспериментально», the three controls that can make the app unreadable or the core
 * chatty, kept behind one more tap and one honest warning.
 *
 * «Сбросить оформление» is duplicated here from the tab root: it is the way back
 * from anything decided on this screen, and it belongs as the last line of the most
 * dangerous one.
 */
@Composable
fun AppearanceLabScreen(onBack: () -> Unit, onNavigate: (String) -> Unit) {
    val repo = LeanApp.instance.settings
    val settings by repo.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val look = rememberLook(settings)
    var showKey by remember { mutableStateOf(false) }
    var pickingLevel by remember { mutableStateOf(false) }
    var showReset by remember { mutableStateOf(false) }

    HubScaffold(
        tr("Экспериментально"),
        onBack,
        header = { AppearanceHeader(look, settings.appearancePreview, debug = showKey) },
    ) {
        KnobHint(tr("Эти настройки могут сделать интерфейс нечитаемым или залить журнал. Сброс внизу возвращает всё на место."))

        Spacer(Modifier.height(14.dp))
        LeanGroup {
            LeanNavItem(
                LeanIcon.Palette, LeanColors.Violet, tr("Цвета по ролям"),
                subtitle = tr("Переопределить десять смысловых цветов"),
                value = if (settings.roleOverrides.isEmpty()) tr("выкл") else tr("%d шт.").format(settings.roleOverrides.size),
            ) { onNavigate(Routes.APPEARANCE_ROLES) }
            LeanDivider()
            LeanNavItem(
                LeanIcon.Layers, LeanColors.TextSecondary, tr("Уровень логов"),
                subtitle = tr("Подробность журнала сетевого ядра"),
                value = settings.logLevel,
            ) { pickingLevel = true }
            LeanDivider()
            LeanToggleItem(
                LeanIcon.Info, LeanColors.Blue, tr("Показать ключ оформления"),
                tr("Печатать активный набор и коды цветов прямо на витрине"), showKey,
            ) { on -> showKey = on }
        }
        KnobHint(tr("Скриншот витрины с включённым ключом заменяет два десятка уточняющих вопросов в поддержке."))

        Spacer(Modifier.height(20.dp))
        LeanGroup {
            LeanNavItem(
                LeanIcon.Refresh, LeanColors.EmberRed, tr("Сбросить оформление"),
                subtitle = tr("Вернуть образ «Сталь·Ночь»"),
            ) { showReset = true }
        }
    }

    if (pickingLevel) {
        AlertDialog(
            onDismissRequest = { pickingLevel = false },
            title = { Text(tr("Уровень логов")) },
            text = {
                Column {
                    LOG_LEVELS.forEach { level ->
                        RadioRow(level, settings.logLevel == level) {
                            // Not an appearance knob: this string is written verbatim into
                            // the sing-box config, so it is not part of a saved look.
                            scope.launch { repo.setLogLevel(level) }
                            pickingLevel = false
                        }
                    }
                    Text(
                        tr("Применяется при следующем подключении. «trace» и «debug» пишут очень много — включайте только для разбора проблемы."),
                        color = LeanColors.TextSecondary,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { pickingLevel = false }) { Text(tr("Отмена"), color = LeanColors.TextSecondary) }
            },
        )
    }
    if (showReset) {
        AlertDialog(
            onDismissRequest = { showReset = false },
            title = { Text(tr("Сбросить оформление")) },
            text = { Text(tr("Все шестьдесят настроек оформления вернутся к образу «Сталь·Ночь». Серверы, подписки и соединение не затрагиваются.")) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { repo.applyAppearance(AppearanceProfile.Default, AppearancePresets.Steel.name) }
                    showReset = false
                }) { Text(tr("Сбросить")) }
            },
            dismissButton = {
                TextButton(onClick = { showReset = false }) { Text(tr("Отмена"), color = LeanColors.TextSecondary) }
            },
        )
    }
}

/** The five levels worth offering; the store accepts sing-box's other two if one arrives. */
private val LOG_LEVELS = listOf("trace", "debug", "info", "warn", "error")
