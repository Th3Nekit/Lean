package com.th3web.lean.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.th3web.lean.data.AppearanceProfile
import com.th3web.lean.data.Settings
import com.th3web.lean.data.mergeInto
import com.th3web.lean.ui.screen.appearance.AppearancePreview
import com.th3web.lean.ui.screen.appearance.PreviewConnected
import com.th3web.lean.ui.screen.appearance.PreviewScene
import com.th3web.lean.ui.screen.appearance.PreviewThumbHeight
import com.th3web.lean.ui.theme.AppearanceSpec
import com.th3web.lean.ui.theme.LeanColors
import com.th3web.lean.ui.theme.LeanCorner
import com.th3web.lean.ui.theme.LeanType
import com.th3web.lean.ui.theme.appearanceSpec
import com.th3web.lean.ui.theme.leanPalette

/**
 * One entry in the «Готовые образы» / «Мои образы» carousel: a live miniature of the look
 * plus its name.
 *
 * The thumbnail is a real [AppearancePreview] fed that preset's own palette, not a stack
 * of colour swatches, and not a screenshot. It costs nothing extra (the resolver is pure
 * and memoised per spec), and it is the only honest way to answer "what will this actually
 * look like": a swatch stack cannot show corner radius, font, density or outline weight,
 * which is most of what a preset moves.
 *
 * The card's own chrome (the selection ring, the name) reads the live theme.
 * It is a control in the current interface, not part of the sample, and a card that
 * restyled itself to match its own contents would make the row unreadable.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PresetCard(
    name: String,
    spec: AppearanceSpec,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    width: Dp = PresetCardWidth,
) {
    val palette = remember(spec) { leanPalette(spec) }
    Column(
        modifier = modifier
            .width(width)
            .clip(LeanCorner.Card)
            // Not glass: this card previews a different look, so showing
            // the current wallpaper through it would misrepresent the preset it offers.
            .background(LeanColors.Surface)
            .border(
                width = if (selected) SelectedRim else IdleRim,
                color = if (selected) LeanColors.Accent else LeanColors.Outline,
                shape = LeanCorner.Card,
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(PresetCardPad),
    ) {
        AppearancePreview(
            palette = palette,
            spec = spec,
            // Never animated: a carousel of seven cards would otherwise put seven infinite
            // transitions on a settings screen, each requesting frames for as long as it
            // is composed, and the LazyRow keeps recomposing them as it scrolls.
            scene = PreviewScene.HOME,
            // Connected, see PreviewConnected: the idle hero is all neutrals,
            // so an at-rest thumbnail would show none of the accent the preset is picked for.
            state = PreviewConnected,
            animate = false,
            compact = true,
            height = PreviewThumbHeight,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            name,
            color = if (selected) LeanColors.Accent else LeanColors.TextPrimary,
            style = LeanType.chip,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * A stored [AppearanceProfile] as the resolved look to draw it in.
 *
 * Resolved against DEFAULT settings, not the live ones, because [AppearanceProfile.mergeInto]
 * overwrites every field the resolver reads, so the base contributes nothing, and keying the
 * memo on the live snapshot would only rebuild seven palettes each time an unrelated setting
 * (`selectedProfileId` moves on every tap in the server list) emitted.
 *
 * `nightNow = false`: a thumbnail must show the look the card promises. Folding
 * the night schedule in would repaint half the carousel identically after 23:00 and leave
 * the user unable to tell the presets apart at exactly the hour they are most likely to be
 * shopping for a darker one.
 */
@Composable
fun rememberPresetSpec(profile: AppearanceProfile, systemDark: Boolean): AppearanceSpec =
    remember(profile, systemDark) {
        profile.mergeInto(PresetResolutionBase)
            .appearanceSpec(systemDark = systemDark, nightNow = false, wallpaperSeed = null)
    }

private val PresetResolutionBase = Settings()

val PresetCardWidth = 132.dp
private val PresetCardPad = 8.dp

// Fixed rather than read from LeanMetrics.outlineWidth: at «Контуры → нет» the cards would
// lose the only thing separating one look from the next in the row, and this rim belongs to
// the control, not to the sample it frames.
private val IdleRim = 1.dp
private val SelectedRim = 1.5.dp
