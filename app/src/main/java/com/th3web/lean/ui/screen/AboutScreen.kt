package com.th3web.lean.ui.screen

import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.th3web.lean.BuildConfig
import com.th3web.lean.R
import com.th3web.lean.data.HwId
import com.th3web.lean.ui.BOOSTY_URL
import com.th3web.lean.core.plugin.NativePlugin
import com.th3web.lean.ui.CREDIT_AWG
import com.th3web.lean.ui.CREDIT_DESIGN
import com.th3web.lean.ui.CREDIT_ENGINE
import com.th3web.lean.ui.CREDIT_LICENSE
import com.th3web.lean.ui.CREDIT_HELPERS
import com.th3web.lean.ui.CREDIT_HELPERS_NO_NAIVE
import com.th3web.lean.ui.CREDIT_PROTOCOLS
import com.th3web.lean.ui.LEAN_BOT_URL
import com.th3web.lean.ui.PRODUCT_DESCRIPTION
import com.th3web.lean.ui.components.LeanGroup
import com.th3web.lean.ui.components.LeanNavItem
import com.th3web.lean.ui.components.rememberLeanClipboard
import com.th3web.lean.ui.icons.LeanIcon
import com.th3web.lean.ui.icons.LeanIconImage
import com.th3web.lean.ui.openUrl
import com.th3web.lean.ui.theme.LeanColors
import com.th3web.lean.ui.theme.LeanCorner
import com.th3web.lean.ui.theme.leanGlass
import com.th3web.lean.ui.tr

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit, onOpenLicenses: () -> Unit = {}) {
    Scaffold(
        containerColor = LeanColors.Background,
        topBar = {
            TopAppBar(
                title = { Text(tr("О программе")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = tr("Назад"),
                            tint = LeanColors.TextPrimary,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = LeanColors.Background,
                    titleContentColor = LeanColors.TextPrimary,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(8.dp))
            // No `fontWeight` override anywhere on this screen: the parameter beats the
            // style, so it would pin these lines while «Жирность» moved everything around
            // them. headlineMedium and titleMedium are already the weights this screen wants.
            Text(
                "Lean",
                color = LeanColors.TextPrimary,
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                tr("Версия %s (%d)").format(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                color = LeanColors.TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                tr(PRODUCT_DESCRIPTION),
                color = LeanColors.TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )

            Spacer(Modifier.height(20.dp))
            // The subscription offer, first thing under the title. Its own card
            // rather than the shared promo banner: that one is gated by «Главный экран →
            // Блоки», and this is the place a user goes to find out how to GET the service,
            // it must not be something an appearance knob can switch off.
            SubscriptionCard()

            Spacer(Modifier.height(16.dp))
            HwidCard()

            Spacer(Modifier.height(24.dp))
            ContactCard()

            Spacer(Modifier.height(24.dp))
            val helpersCredit = if (NativePlugin.Naive.isAvailable(LocalContext.current)) {
                CREDIT_HELPERS
            } else {
                CREDIT_HELPERS_NO_NAIVE
            }
            CreditCard(
                title = tr("Благодарности"),
                lines = listOf(
                    tr(CREDIT_DESIGN),
                    tr(CREDIT_ENGINE),
                    tr(CREDIT_PROTOCOLS),
                    tr(CREDIT_AWG),
                    tr(helpersCredit),
                ),
            )

            Spacer(Modifier.height(16.dp))
            CreditCard(
                title = tr("Лицензии"),
                lines = listOf(
                    tr(CREDIT_LICENSE),
                    tr("Код самого Lean, оригинальный. Сторонние приложения не " +
                        "модифицировались и не распространяются в составе Lean."),
                ),
            )
            Spacer(Modifier.height(8.dp))
            LeanGroup {
                LeanNavItem(
                    icon = LeanIcon.Info,
                    tint = LeanColors.Accent,
                    title = tr("Тексты лицензий"),
                    subtitle = tr("Все компоненты, входящие в сборку"),
                    onClick = onOpenLicenses,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * «Подписка Lean VPN», the one card on this screen that sells something.
 *
 * Carries the bot avatar so it reads as the same product as the promo banner elsewhere,
 * but shares no code with it: that component self-hides on the blocks bitmask, and this
 * card is the answer to "how do I get access", which nothing may hide.
 */
@Composable
private fun SubscriptionCard() {
    if (!BuildConfig.SHOWS_PROMO) return
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .leanGlass(MaterialTheme.shapes.large, MaterialTheme.colorScheme.surfaceContainerLow),
        // Transparent: [leanGlass] paints the card and falls back to exactly this surface
        // when «Стекло» is off, which is also the only way «Плотность стекла» reaches it.
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.lean_vpn_bot_avatar),
                    contentDescription = null,
                    modifier = Modifier.size(44.dp).clip(LeanCorner.Badge),
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        tr("Подписка Lean VPN"),
                        color = LeanColors.TextPrimary,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        tr("Свои серверы в Европе и Швейцарии, оплата и устройства — в боте."),
                        color = LeanColors.TextTertiary,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            FilledTonalButton(
                onClick = { context.openUrl(LEAN_BOT_URL) },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(tr("Оформить подписку"))
            }
            Spacer(Modifier.height(10.dp))
            Text(
                tr("Поддержать разработку"),
                color = LeanColors.TextTertiary,
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.height(8.dp))
            ContactRow(
                icon = LeanIcon.Heart,
                title = tr("Boosty"),
                handle = "boosty.to/th3nekit",
                onClick = { context.openUrl(BOOSTY_URL) },
            )
        }
    }
}

@Composable
private fun HwidCard() {
    val context = LocalContext.current
    val clipboard = rememberLeanClipboard()
    val hwid = remember { HwId.get(context) }
    fun copyHwid() {
        clipboard.copy(hwid)
        // Android 13+ shows its own copy confirmation; only toast below it.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, tr("HWID скопирован"), Toast.LENGTH_SHORT).show()
        }
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .leanGlass(MaterialTheme.shapes.large, MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable { copyHwid() },
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tr("ID устройства (HWID)"),
                    color = LeanColors.TextPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(tr("копировать"), color = LeanColors.Accent, style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(8.dp))
            Text(hwid, color = LeanColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                tr("Передаётся подписке (заголовок x-hwid и токен {hwid} в URL) для привязки устройства."),
                color = LeanColors.TextTertiary,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun ContactCard() {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .leanGlass(MaterialTheme.shapes.large, MaterialTheme.colorScheme.surfaceContainerLow),
        // Transparent: [leanGlass] paints the card and falls back to exactly this surface
        // when «Стекло» is off, which is also the only way «Плотность стекла» reaches it.
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                tr("Связь"),
                color = LeanColors.TextPrimary,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                tr("Вопросы и обратная связь по приложению — в Telegram."),
                color = LeanColors.TextTertiary,
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.height(14.dp))
            ContactRow(
                icon = LeanIcon.Support,
                title = tr("Разработчик"),
                handle = "@th3_nek1t",
                onClick = { context.openUrl("https://t.me/th3_nek1t") },
            )
            Spacer(Modifier.height(12.dp))
            ContactRow(
                icon = LeanIcon.Globe,
                title = tr("Канал проектов"),
                handle = "@th3nek1t_projects",
                onClick = { context.openUrl("https://t.me/th3nek1t_projects") },
            )
        }
    }
}

@Composable
private fun ContactRow(icon: LeanIcon, title: String, handle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LeanIconImage(icon, tint = LeanColors.Accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = LeanColors.TextPrimary,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                handle,
                color = LeanColors.TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun CreditCard(title: String, lines: List<String>) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .leanGlass(MaterialTheme.shapes.large, MaterialTheme.colorScheme.surfaceContainerLow),
        // Transparent: [leanGlass] paints the card and falls back to exactly this surface
        // when «Стекло» is off, which is also the only way «Плотность стекла» reaches it.
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                title,
                color = LeanColors.TextPrimary,
                style = MaterialTheme.typography.titleMedium,
            )
            lines.forEach { line ->
                Spacer(Modifier.height(10.dp))
                Text(line, color = LeanColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
