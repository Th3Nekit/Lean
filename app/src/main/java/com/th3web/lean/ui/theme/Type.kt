package com.th3web.lean.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.th3web.lean.R

// Bundled static fonts keep the complete Russian UI typographically stable
// offline and on devices without Google Play Services.
val DisplayFont = FontFamily(
    Font(R.font.unbounded_semibold, FontWeight.SemiBold),
    Font(R.font.unbounded_bold, FontWeight.Bold),
    Font(R.font.unbounded_extrabold, FontWeight.ExtraBold),
)

// The wordmark uses the same display family as every heading, avoiding a third
// visual voice and guaranteeing identical local resource availability.
val BrandFont = DisplayFont

// Body and labels use Onest with matching static weights and Cyrillic coverage.
val BodyFont = FontFamily(
    Font(R.font.onest_regular, FontWeight.Normal),
    Font(R.font.onest_medium, FontWeight.Medium),
    Font(R.font.onest_semibold, FontWeight.SemiBold),
    Font(R.font.onest_bold, FontWeight.Bold),
)

/**
 * The two live voices, «Шрифт заголовков» and «Шрифт текста».
 *
 * Snapshot state on the [LeanColors] pattern: written only from [applyTypography], read
 * inside composables. Nothing in the app has to read them directly (every role below
 * already carries its resolved family); they exist so the fonts screen can render a
 * sample in the family that is actually active, and so "which voice is on" has one answer.
 */
object LeanFonts {

    var display by mutableStateOf<FontFamily>(DisplayFont)
    var body by mutableStateOf<FontFamily>(BodyFont)

    /**
     * The four offered families. `system` and `mono` cost zero bytes of APK and have
     * guaranteed Cyrillic coverage on every device, which is the whole reason the choice
     * stops at four instead of bundling more faces (Unbounded alone is ~423 KB per weight).
     *
     * [key] arrives already normalised by `AppearanceNorm.fontFamily`, so the `else` branch
     * is unreachable in practice and exists to make the function total.
     */
    fun familyFor(key: String): FontFamily = when (key) {
        "onest" -> BodyFont
        "system" -> FontFamily.SansSerif
        "mono" -> FontFamily.Monospace
        else -> DisplayFont
    }
}

/**
 * Tabular figures for every live numeral (latency, traffic, counters,
 * timestamps, expiry), columns must never wobble. Apply via
 * `fontFeatureSettings`. Every live numeral keeps tnum; ink follows the
 * role's MD3 color duty at the call-site.
 *
 * «Табличные цифры» can switch it off; [retuned] then strips the feature from the four
 * roles that carry it, and nothing else in the table is affected.
 */
const val TabularNums = "tnum"

/**
 * The unscaled sizes every role starts from.
 *
 * [applyTypography] always derives the live roles from here, never from the previous
 * result. Deriving from the live roles would compound: two size steps in a row would
 * multiply, and a family swapped once could never be swapped back, because the base table
 * is also what says which of the two voices a role speaks in.
 */
private object BaseRoles {

    /**
     * Carries no brush: the wordmark's two stops follow the palette now (they follow
     * the accent and the canvas), so [applyTypography] paints them on at the end.
     */
    val appTitle = TextStyle(
        fontFamily = BrandFont,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 27.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.5).sp,
    )

    val screenTitle = TextStyle(
        fontFamily = DisplayFont,
        fontWeight = FontWeight.Bold,
        fontSize = 23.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.3).sp,
    )

    val connectStatus = TextStyle(
        fontFamily = DisplayFont,
        fontWeight = FontWeight.Bold,
        fontSize = 19.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
    )

    val heroNumber = TextStyle(
        fontFamily = DisplayFont,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 28.sp,
        fontFeatureSettings = TabularNums,
    )

    val cardName = TextStyle(
        fontFamily = DisplayFont,
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    )

    val hubTitle = TextStyle(
        fontFamily = DisplayFont,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        lineHeight = 20.sp,
    )

    val msReadout = TextStyle(
        fontFamily = DisplayFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        fontFeatureSettings = TabularNums,
    )

    val statValue = TextStyle(
        fontFamily = DisplayFont,
        fontWeight = FontWeight.Bold,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        fontFeatureSettings = TabularNums,
    )

    val rowTitle = TextStyle(
        fontFamily = BodyFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.5.sp,
        lineHeight = 20.sp,
    )

    val meta = TextStyle(
        fontFamily = BodyFont,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 17.sp,
    )

    val chip = TextStyle(
        fontFamily = BodyFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.5.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.1.sp,
    )

    val valuePill = TextStyle(
        fontFamily = BodyFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.1.sp,
        fontFeatureSettings = TabularNums,
    )

    val tagBadge = TextStyle(
        fontFamily = BodyFont,
        fontWeight = FontWeight.Bold,
        fontSize = 10.5.sp,
        lineHeight = 13.sp,
        letterSpacing = 0.2.sp,
    )

    val sectionLabel = TextStyle(
        fontFamily = BodyFont,
        fontWeight = FontWeight.Bold,
        fontSize = 11.5.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.13.em,
    )
}

/**
 * Per-role component styles. These layer on top of
 * [LeanTypography]: Material slots remain the broad fallback; components
 * reference these roles directly for exact editorial control.
 *
 * Colors are intentionally left `Color.Unspecified` where the role maps to a
 * role to a `LeanColors` token, call sites pass the live token (e.g.
 * `LeanColors.TextPrimary`), so the MD3 scheme stays the single source of
 * truth. [appTitle] alone carries a brush.
 *
 * No inline `TextStyle` copies anywhere in the app: every text appearance resolves
 * to one of the roles below.
 *
 * Every role is snapshot state, published once per look change by [applyTypography] and
 * never recomputed on read. A getter would be the obvious alternative and the wrong one: a
 * `TextStyle` is a heap object, these are read inside `LazyColumn` item lambdas, and a
 * fresh instance per read defeats both skipping and the text-layout cache. Publication
 * uses `mutableStateOf`'s structural equality, so re-applying the same look writes nothing
 * and invalidates no reader.
 */
object LeanType {

    /**
     * App title "Lean" wordmark, Unbounded 800, 27/32, −0.5sp, with the palette's
     * gradient ink. There is no longer a light/dark pair: [leanPalette] resolves the two
     * stops for the active canvas (and for «Логотип по акценту»), so this role carries one
     * brush that is already correct for the theme on screen.
     */
    var appTitle by mutableStateOf(BaseRoles.appTitle.copy(brush = wordmarkBrush()))

    /**
     * Screen titles, Unbounded 700, 23/28, −0.3sp. Color = TextPrimary
     * (never gradient ink: that is reserved for the brand wordmark).
     */
    var screenTitle by mutableStateOf(BaseRoles.screenTitle)

    /**
     * Connect status line, Unbounded 700, 19/24, 0 tracking.
     * State-colored: idle = TextSecondary · connecting and
     * disconnecting = Connecting `#A9A9B2` · connected = TextPrimary ·
     * error = label TextPrimary, error string TextSecondary.
     */
    var connectStatus by mutableStateOf(BaseRoles.connectStatus)

    /**
     * Hero number, Unbounded 700, 24/28, tabular. Color = TextSecondary
     * (numerals are always TextSecondary, never ramp-tinted, never white).
     */
    var heroNumber by mutableStateOf(BaseRoles.heroNumber)

    /** Card / server names, Unbounded 700, 15/20. Color = TextPrimary. */
    var cardName by mutableStateOf(BaseRoles.cardName)

    /** Settings-hub tile titles, Unbounded 700, 16/20. Color = TextPrimary. */
    var hubTitle by mutableStateOf(BaseRoles.hubTitle)

    /**
     * Row ms readout, Unbounded 600, 13/16, tabular. Color = always
     * TextSecondary regardless of latency tier: the bars carry quality,
     * the number stays quiet.
     */
    var msReadout by mutableStateOf(BaseRoles.msReadout)

    /**
     * Editorial stat readouts (traffic `41.2 GB / 100 GB`, data blocks),
     * Unbounded 700, 17/22, tabular. Color = TextSecondary (numerals are
     * never TextPrimary).
     */
    var statValue by mutableStateOf(BaseRoles.statValue)

    /** Row titles, Onest 600, 15.5/20. Color = TextPrimary. */
    var rowTitle by mutableStateOf(BaseRoles.rowTitle)

    /**
     * Subtitles / meta, Onest 400, 13/17. Color = TextSecondary or
     * TextTertiary per context; embedded numerals tnum TextSecondary.
     */
    var meta by mutableStateOf(BaseRoles.meta)

    /**
     * Chips / buttons / segmented labels, Onest 600, 12.5/16, +0.1sp.
     * Color = context (OnAccent on the inverted thumb, TextSecondary
     * unselected).
     */
    var chip by mutableStateOf(BaseRoles.chip)

    /**
     * ValuePill text (expiry date, NavItem trailing values), Onest 600,
     * 12/16, +0.1sp, tabular. Color = TextSecondary, even in the dashed
     * expiring/expired state: the border carries urgency, not the ink.
     */
    var valuePill by mutableStateOf(BaseRoles.valuePill)

    /**
     * TagBadge, Onest 700, 10.5/13, +0.2sp, all caps (uppercase at the
     * call site). Ink: ghost variant = TagInk (White @ 0.80), outline
     * variant = White @ 0.60. Words differentiate protocols, never hue.
     */
    var tagBadge by mutableStateOf(BaseRoles.tagBadge)

    /**
     * Section labels, Onest 700 uppercase (uppercase the string at the call
     * site, and only while [LeanOptions.sectionCaps] is on), 11.5/14, +0.13em
     * tracking. Color = TextTertiary. Outside cards, preceded by the 14×2dp
     * gradient tick (the `SectionLabel` composable); inside cards the bare style
     * is reused for micro-labels ("traffic", "expires") without the tick.
     */
    var sectionLabel by mutableStateOf(BaseRoles.sectionLabel)
}

// Brand typography scale, every Material slot filled on the two brand faces
// so stock M3 components (dialogs, chips, segmented buttons, snackbars) never
// fall back to Roboto. Components prefer the LeanType roles above.
//
// Frozen, like [BaseRoles]: [leanTypography] derives the live scale from this table on
// every look change, so the steps never compound.
val LeanTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = DisplayFont,
        fontWeight = FontWeight.Bold,
        fontSize = 57.sp,
        lineHeight = 64.sp,
    ),
    displayMedium = TextStyle(
        fontFamily = DisplayFont,
        fontWeight = FontWeight.Bold,
        fontSize = 45.sp,
        lineHeight = 52.sp,
    ),
    displaySmall = TextStyle(
        fontFamily = DisplayFont,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 44.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = DisplayFont,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 27.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = DisplayFont,
        fontWeight = FontWeight.Bold,
        fontSize = 23.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.3).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = DisplayFont,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = DisplayFont,
        fontWeight = FontWeight.Bold,
        fontSize = 19.sp,
        lineHeight = 24.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = DisplayFont,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        lineHeight = 20.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = BodyFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = BodyFont,
        fontWeight = FontWeight.Normal,
        fontSize = 15.5.sp,
        lineHeight = 20.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = BodyFont,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 17.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = BodyFont,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = BodyFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.5.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = BodyFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.5.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.1.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = BodyFont,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
)

/**
 * Rebuild every [LeanType] role for [spec], family, size step, weight delta,
 * tabular figures.
 *
 * one pass, into snapshot state, called by [LeanAppearance] and by nothing else. See the
 * note on [LeanType] for why this is not a set of read-time getters.
 *
 * Ordering contract: [LeanAppearance] publishes the palette (`LeanColors.adopt`) before it
 * calls this, which is what lets the wordmark read its two stops from the tokens instead
 * of re-deriving a colour here. Swapping those two lines would leave the wordmark one look
 * behind, the only cross-file ordering this file depends on.
 */
internal fun applyTypography(spec: AppearanceSpec) {
    val display = LeanFonts.familyFor(spec.fontDisplay)
    val body = LeanFonts.familyFor(spec.fontBody)
    LeanFonts.display = display
    LeanFonts.body = body

    val scale = spec.textScale / 100f
    val weightDelta = spec.fontWeightDelta
    val tabular = spec.tabularNums
    fun tuned(base: TextStyle) = base.retuned(display, body, scale, weightDelta, tabular)

    LeanType.appTitle = tuned(BaseRoles.appTitle).copy(brush = wordmarkBrush())
    LeanType.screenTitle = tuned(BaseRoles.screenTitle)
    LeanType.connectStatus = tuned(BaseRoles.connectStatus)
    LeanType.heroNumber = tuned(BaseRoles.heroNumber)
    LeanType.cardName = tuned(BaseRoles.cardName)
    LeanType.hubTitle = tuned(BaseRoles.hubTitle)
    LeanType.msReadout = tuned(BaseRoles.msReadout)
    LeanType.statValue = tuned(BaseRoles.statValue)
    LeanType.rowTitle = tuned(BaseRoles.rowTitle)
    LeanType.meta = tuned(BaseRoles.meta)
    LeanType.chip = tuned(BaseRoles.chip)
    LeanType.valuePill = tuned(BaseRoles.valuePill)
    LeanType.tagBadge = tuned(BaseRoles.tagBadge)
    LeanType.sectionLabel = tuned(BaseRoles.sectionLabel)
}

/**
 * The Material typography scale for [spec]. Called from `LeanTheme` inside `remember(spec)`
 * i.e. during composition, before [applyTypography]'s `SideEffect` has run, so it must
 * resolve the families from [spec] itself and never from [LeanFonts]. Reading the published
 * state here would render the first frame of a font change in the previous family.
 *
 * This is also the entire reason «Размер текста» lives in `Typography` rather than in
 * `LocalDensity`: every `AlertDialog` builds its own `AndroidComposeView` and re-declares
 * `LocalDensity` from its own context, but `LocalTypography` is inherited into the dialog's
 * subcomposition, so the app's two dozen dialogs scale with everything else.
 */
internal fun leanTypography(spec: AppearanceSpec): Typography {
    val display = LeanFonts.familyFor(spec.fontDisplay)
    val body = LeanFonts.familyFor(spec.fontBody)
    val scale = spec.textScale / 100f
    val weightDelta = spec.fontWeightDelta
    // No Material slot carries tabular figures, so «Табличные цифры» has nothing to strip
    // here, passing it through would only invite the flag to grow a second meaning.
    fun tuned(base: TextStyle) = base.retuned(display, body, scale, weightDelta, tabular = true)
    return Typography(
        displayLarge = tuned(LeanTypography.displayLarge),
        displayMedium = tuned(LeanTypography.displayMedium),
        displaySmall = tuned(LeanTypography.displaySmall),
        headlineLarge = tuned(LeanTypography.headlineLarge),
        headlineMedium = tuned(LeanTypography.headlineMedium),
        headlineSmall = tuned(LeanTypography.headlineSmall),
        titleLarge = tuned(LeanTypography.titleLarge),
        titleMedium = tuned(LeanTypography.titleMedium),
        titleSmall = tuned(LeanTypography.titleSmall),
        bodyLarge = tuned(LeanTypography.bodyLarge),
        bodyMedium = tuned(LeanTypography.bodyMedium),
        bodySmall = tuned(LeanTypography.bodySmall),
        labelLarge = tuned(LeanTypography.labelLarge),
        labelMedium = tuned(LeanTypography.labelMedium),
        labelSmall = tuned(LeanTypography.labelSmall),
    )
}

/**
 * The wordmark's two stops, live from the palette.
 *
 * Type.kt resolves no colour of its own. [leanPalette] has already decided whether
 * «Логотип по акценту» is on and which canvas is active, and published the answer as
 * [LeanColors.GradientInkStart]/[LeanColors.GradientInkEnd], which is what retires the
 * old pair of frozen brushes, whose light variant ended on Steel's tone-40 no matter which
 * accent the user had chosen.
 */
private fun wordmarkBrush(): Brush =
    Brush.verticalGradient(listOf(LeanColors.GradientInkStart, LeanColors.GradientInkEnd))

/**
 * Apply every type knob to one style from the frozen table.
 *
 * The base table decides which of the two voices a role speaks in; the knobs only decide
 * what those voices are. Recognising the display voice by identity ([BrandFont] is the
 * same instance as [DisplayFont]) keeps that mapping in the table itself instead of in
 * twenty-nine hand-written assignments that would drift the first time a role changed
 * family.
 *
 * At every default this returns a value structurally equal to [base], so the shipping look
 * is reproduced bit-for-bit and the snapshot write is a no-op.
 */
private fun TextStyle.retuned(
    display: FontFamily,
    body: FontFamily,
    scale: Float,
    weightDelta: Int,
    tabular: Boolean,
): TextStyle {
    val family = if (fontFamily == DisplayFont) display else body
    return scaled(scale).copy(
        fontFamily = family,
        fontWeight = fontWeight?.shifted(weightDelta),
        // Roles without tabular figures already hold null here, so this only ever removes
        // tnum from the four numeric ones.
        fontFeatureSettings = if (tabular) fontFeatureSettings else null,
    )
}

/** «Размер текста», one discrete step, applied to the three metrics that are in Sp. */
internal fun TextStyle.scaled(f: Float): TextStyle {
    if (f == 1f) return this
    return copy(
        fontSize = fontSize.scaledBy(f),
        lineHeight = lineHeight.scaledBy(f),
        letterSpacing = letterSpacing.scaledBy(f),
    )
}

/**
 * Scale a metric only if it is absolute.
 *
 * `sectionLabel`'s 0.13em tracking is already a fraction of the font size, so scaling it
 * would apply the step twice; an Unspecified unit would not merely be wrong but throw,
 * `TextUnit` arithmetic rejects it outright.
 */
private fun TextUnit.scaledBy(f: Float): TextUnit = if (isSp) this * f else this

/**
 * «Жирность», move a weight along the 100..900 ladder.
 *
 * The clamp is the honest part; the dishonest part would be pretending the result always
 * lands. Compose silently picks the nearest bundled face, and Unbounded ships only 600/700/800
 * while Onest ships 400..700, so at the ends of the range the knob quietly stops having an
 * effect rather than misbehaving. The tab's own copy says so.
 */
private fun FontWeight.shifted(delta: Int): FontWeight =
    if (delta == 0) this else FontWeight((weight + delta).coerceIn(100, 900))
