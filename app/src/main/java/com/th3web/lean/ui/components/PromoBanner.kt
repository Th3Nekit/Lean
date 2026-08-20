package com.th3web.lean.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.th3web.lean.R
import com.th3web.lean.ui.icons.LeanIcon
import com.th3web.lean.ui.icons.LeanIconImage
import com.th3web.lean.ui.screen.TELEGRAM_BOT_BODY
import com.th3web.lean.ui.screen.TELEGRAM_BOT_CTA
import com.th3web.lean.ui.screen.TELEGRAM_BOT_TITLE
import androidx.compose.ui.graphics.Color
import com.th3web.lean.ui.theme.LeanColors
import com.th3web.lean.ui.theme.LeanCorner
import com.th3web.lean.ui.theme.LeanMotion
import com.th3web.lean.ui.theme.LeanOptions
import com.th3web.lean.ui.theme.LeanType
import com.th3web.lean.ui.theme.leanGlass
import com.th3web.lean.ui.theme.motionAllowed
import com.th3web.lean.ui.tr

/**
 * The «Lean VPN в Telegram» promo card, shown at the top of the Servers screen,
 * where subscriptions are actually managed, so "оформите подписку" lands next to
 * the thing it talks about instead of crowding the connect button on Home.
 *
 * The title owns the full width of its column: sharing the row with a text call to
 * action squeezes it to an ellipsis on a normal phone, so the action is a compact
 * trailing chip instead.
 */
@Composable
fun TelegramPromoBanner(onClick: () -> Unit, modifier: Modifier = Modifier) {
    // «Блоки → Баннер Telegram». Honoured here rather than at the call site: the banner
    // is one component wherever it is placed, and a gate left behind on a screen it has
    // moved away from is a knob that silently stops working.
    if (!LeanOptions.showBannerBlock) return
    val animate = motionAllowed() && LeanOptions.bannerSheen
    Surface(
        onClick = onClick,
        // Glass like every other panel on this screen; the Surface's own fill goes
        // transparent so it does not sit on top of the backdrop the glass just drew.
        // leanGlass falls back to exactly this colour when there is no wallpaper.
        modifier = modifier.fillMaxWidth().leanGlass(LeanCorner.Card, LeanColors.Surface),
        shape = LeanCorner.Card,
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .iridescentSheen(animate)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(R.drawable.lean_vpn_bot_avatar),
                contentDescription = null,
                modifier = Modifier
                    .size(48.dp)
                    .clip(LeanCorner.Badge),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = tr(TELEGRAM_BOT_TITLE),
                    color = LeanColors.TextPrimary,
                    style = LeanType.cardName,
                    // Two lines, not one: the EN string ("Lean VPN on Telegram") and
                    // a large system font scale both need the room, and the card is
                    // free to grow, nothing below it depends on a fixed height.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = tr(TELEGRAM_BOT_BODY),
                    color = LeanColors.TextSecondary,
                    style = LeanType.meta,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            // The call to action as a glyph, costing a chip's width rather than a
            // label's. The whole card is clickable, so this is an affordance and not the
            // only hit target, hence contentDescription rather than a visible label.
            val cta = tr(TELEGRAM_BOT_CTA)
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .semantics { contentDescription = cta },
                    contentAlignment = Alignment.Center,
                ) {
                    LeanIconImage(
                        LeanIcon.Chev,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }
    }
}

/**
 * A slow iridescent band drifting across the card, the promo's only decoration,
 * drawn under the content so it never touches text contrast.
 *
 * Colours come from the live MD3 scheme (primary → tertiary → secondary), so the
 * sheen follows the user's palette pick instead of hardcoding a brand gradient.
 * Alphas stay under ~0.22 to keep it a sheen rather than a second background.
 * With motion off it degrades to a single static pass of the same gradient, the
 * card still reads as "special", nothing moves.
 */
@Composable
private fun Modifier.iridescentSheen(animate: Boolean): Modifier {
    val scheme = MaterialTheme.colorScheme
    // The transition exists only while it is wanted. Animating to a constant
    // target still leaves an infinite transition composed, and that asks the
    // frame clock for a callback forever: this is the app's only permanently
    // running driver outside the connect hero, so «Анимации → выкл» has to remove
    // it, not merely freeze its output. Same rule as `RefreshGlyph`.
    val phase = if (animate) {
        val transition = rememberInfiniteTransition(label = "promoSheen")
        val drift by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = LeanMotion.loop(5_200, LinearEasing),
            label = "promoSheenPhase",
        )
        drift
    } else {
        0f
    }
    val bands = listOf(
        scheme.primary.copy(alpha = 0f),
        scheme.primary.copy(alpha = 0.22f),
        scheme.tertiary.copy(alpha = 0.20f),
        scheme.secondary.copy(alpha = 0.14f),
        scheme.primary.copy(alpha = 0f),
    )
    return drawBehind {
        // The band is wider than the card and travels a full card+band span, so
        // the sweep enters from off-screen left and fully exits right, no visible
        // jump when the linear animation wraps back to 0.
        val span = size.width * 1.5f
        val head = phase * (size.width + span) - span
        drawRect(
            brush = Brush.linearGradient(
                colors = bands,
                start = Offset(head, 0f),
                end = Offset(head + span, size.height),
            ),
        )
    }
}
