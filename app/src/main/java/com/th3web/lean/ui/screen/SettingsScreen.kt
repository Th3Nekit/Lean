package com.th3web.lean.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.th3web.lean.ui.Routes
import com.th3web.lean.ui.tr
import com.th3web.lean.ui.components.LeanBadge
import com.th3web.lean.ui.components.LeanSectionLabel
import com.th3web.lean.ui.icons.LeanIcon
import com.th3web.lean.ui.icons.LeanIconImage
import com.th3web.lean.ui.theme.LeanColors
import com.th3web.lean.ui.theme.LeanCorner
import com.th3web.lean.ui.theme.LeanMetrics
import com.th3web.lean.ui.theme.leanGlass

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onNavigate: (String) -> Unit, onBack: () -> Unit) {
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
                title = { Text(tr("Настройки")) },
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
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(2.dp))
            LeanSectionLabel(tr("Разделы"))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                HubTile(LeanIcon.Palette, LeanColors.Accent, tr("Внешний вид"), tr("Образы · Цвет · Шрифты")) { onNavigate(Routes.HUB_APPEARANCE) }
                HubTile(LeanIcon.Cable, LeanColors.Ember, tr("Соединение"), tr("Маршруты · Туннель · Защита")) { onNavigate(Routes.HUB_CONNECTION) }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                HubTile(LeanIcon.Cloud, LeanColors.Blue, tr("Провайдер и пинг"), tr("Подписка · Пинг · TCP")) { onNavigate(Routes.HUB_PROVIDER) }
                HubTile(LeanIcon.Info, LeanColors.EmberRed, tr("О Lean"), tr("Версия · Благодарности")) { onNavigate(Routes.HUB_ABOUT) }
            }

            // A second tier, not a fifth hub. These four are the screens people come to
            // Settings for, per-app routing before a banking app, DNS when something
            // will not resolve, the log when support asks for it, the language once,
            // and each of them was two taps deep inside a hub that is otherwise about
            // something else. Shorter tiles, so the four above keep being the structure
            // and these read as shortcuts into it rather than as more of the same.
            Spacer(Modifier.height(2.dp))
            LeanSectionLabel(tr("Быстрый доступ"))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                QuickTile(LeanIcon.Apps, LeanColors.Violet, tr("Приложения")) { onNavigate(Routes.PER_APP) }
                QuickTile(LeanIcon.Lan, LeanColors.Blue, tr("DNS")) { onNavigate(Routes.DNS) }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                QuickTile(LeanIcon.Dots, LeanColors.TextSecondary, tr("Логи")) { onNavigate(Routes.LOGS) }
                QuickTile(LeanIcon.Lang, LeanColors.Ember, tr("Язык")) { onNavigate(Routes.LANGUAGE) }
            }
        }
    }
}

/** The hairline «Контуры» is set to, or none when that width is zero. */
@Composable
private fun tileBorder(): BorderStroke? {
    val width = LeanMetrics.outlineWidth
    return if (width <= 0.dp) null else BorderStroke(width, LeanColors.Outline)
}

/**
 * A shortcut into one screen: badge, name, nothing else.
 *
 * Half the height of a [HubTile] and without a subtitle: the hubs are the
 * structure of this screen and have to stay the loudest thing on it.
 */
@Composable
private fun RowScope.QuickTile(icon: LeanIcon, accent: Color, title: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = LeanCorner.TopBar,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = tileBorder(),
        modifier = Modifier
            .weight(1f)
            .height(72.dp)
            .leanGlass(LeanCorner.TopBar, LeanColors.Surface),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LeanBadge(icon, accent, size = 32.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                title,
                color = LeanColors.TextPrimary,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
            )
        }
    }
}

/**
 * MD3 hub tile: a quiet surfaceContainer card, hue lives only in the badge
 * glyph tint (accent-budget contract); no colored washes or rims.
 */
@Composable
private fun RowScope.HubTile(icon: LeanIcon, accent: Color, title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = LeanCorner.TopBar,
        // Transparent because [leanGlass] paints the tile, it falls back to exactly this
        // fill when «Стекло» is off, so the default look is unchanged, and it is the one
        // place «Плотность стекла» is honoured. Painting the container here instead left
        // the four hub tiles opaque at every density, which is a knob whose own hint
        // promises it works on any background.
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        // Rimmed like every other surface in the app. These four were the one place a
        // panel floated with no edge at all, which on the true-black canvas left them
        // reading as four holes rather than four cards.
        border = tileBorder(),
        modifier = Modifier
            .weight(1f)
            .height(150.dp)
            .leanGlass(LeanCorner.TopBar, LeanColors.Surface),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            LeanBadge(icon, accent, size = 42.dp)
            Column {
                Text(title, color = LeanColors.TextPrimary, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(3.dp))
                Text(subtitle, color = LeanColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
