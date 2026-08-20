package com.th3web.lean.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.th3web.lean.data.model.Profile
import com.th3web.lean.ui.formatBytes
import com.th3web.lean.ui.icons.LeanIcon
import com.th3web.lean.ui.icons.LeanIconImage
import com.th3web.lean.ui.theme.LeanColors
import com.th3web.lean.ui.theme.LeanCorner
import com.th3web.lean.ui.theme.LeanMetrics
import com.th3web.lean.ui.theme.LeanOptions
import com.th3web.lean.ui.theme.LeanType
import com.th3web.lean.ui.theme.depthShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import com.th3web.lean.ui.theme.leanGlass
import com.th3web.lean.ui.tr

/**
 * A subscription header, rebuilt as an idiomatic MD3 [Card] (`shapes.large` /
 * 22dp, `surfaceContainer`): a header (38dp neutral [LeanBadge] cloud tile with
 * a primary-tinted glyph, provider name, a [meta] sub-line that wraps fully
 * (uncapped) for long provider descriptions, an optional «проверить пинг»
 * [IconButton] (only when [onPing] is non-null, Home's grouped quick-pick wires
 * it to ping just this group's servers), stock refresh [IconButton] that
 * spins while [refreshing], an optional ⋮ overflow menu, 0↔90° collapse
 * chevron), an optional provider [announce] banner (also uncapped, wraps
 * fully, never ellipsizes), an Incy traffic/expiry
 * strip (limited plans get days-left + used/total + bar; unlimited plans only
 * the days-left pill, or nothing (see [TrafficStrip]), and) when [expanded]
 * and [isEmpty], a quiet "no servers" placeholder.
 *
 * The subscription's server rows are no longer nested inside this card: the
 * owning screen flattens them into its outer `LazyColumn` as their own lazy
 * items so the list can virtualize them (only on-screen rows are composed).
 * This composable renders only the always-on chrome; the rows are emitted right
 * after it by the screen, in the nested ([ServerRow] `nested = true`) inset
 * style, which keeps the visual grouping under the card.
 *
 * Expand/collapse is hoisted: [expanded] is owned by the screen (so it can
 * decide whether to emit the row items), and [onToggleExpanded] flips it. The
 * chevron animates off [expanded]; tapping the header row calls
 * [onToggleExpanded].
 *
 * The card always carries a 1dp hairline: `outlineVariant` at rest, flipping
 * to a quiet `primary @ 0.40` when this subscription hosts the currently
 * selected server ([active]), the only selection cue at card level; the nested
 * row itself carries the tonal `secondaryContainer` wash.
 *
 * Overflow menu: a [LeanIcon.Dots] IconButton between
 * the refresh button and the chevron, rendered only when [onEdit], [onCopyUrl]
 * or [onDelete] is non-null, opening a [DropdownMenu] with «Изменить» /
 * «Скопировать ссылку» / «Удалить» (error tint). All these params are trailing
 * + defaulted.
 *
 * Home's grouped quick-pick keeps a 3-server peek attached under the header
 * even while collapsed: [attachedBelow] forces the attached chrome (top-only
 * rounding, no border) in both states there, and [expandable] = false hides
 * the chevron and disables the header toggle for a group whose peek already
 * shows every server. Both default to the all-or-nothing behaviour the Servers tab
 * uses.
 */
@Composable
fun SubscriptionCard(
    name: String,
    meta: String,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    active: Boolean,
    isEmpty: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    announce: String = "",
    usedBytes: Long? = null,
    totalBytes: Long? = null, // 0 = unlimited
    expireEpochSec: Long? = null, // Unix seconds
    onEdit: (() -> Unit)? = null, // «Изменить» (sub-level ⋮ menu)
    onCopyUrl: (() -> Unit)? = null, // «Скопировать ссылку» (sub URL)
    onDelete: (() -> Unit)? = null, // «Удалить» (error tint)
    /** «Переместить в папку» — opens the folder picker. Null on screens without folders. */
    onMoveToFolder: (() -> Unit)? = null,
    refreshing: Boolean = false, // spin the refresh glyph while the coroutine runs
    // «проверить пинг» for this group's servers, an extra IconButton before the
    // refresh glyph, rendered only when non-null (Home's grouped quick-pick sets
    // it; the Servers tab leaves it null and gets no per-group ping button, its
    // ping-all lives elsewhere). Reuses the LeanIcon.Pulse glyph the Servers tab
    // and the Home connection-check button already use for "ping".
    onPing: (() -> Unit)? = null,
    /**
     * «Не участвует в тесте скорости» for the whole subscription, and whether it is set.
     *
     * Excluding servers one at a time is the only thing that existed, and on a
     * subscription of sixty that is sixty long-press menus, so in practice a
     * subscription somebody did not want measured got measured anyway, and every sweep
     * lit all of them up at once.
     */
    excludedFromTest: Boolean = false,
    onToggleExcludedFromTest: (() -> Unit)? = null,
    // True while this group's ping burst is in flight: the glyph pulses, and a second
    // tap stops the run rather than being swallowed. A sweep over a provider with fifty
    // servers is long enough that "wait it out" is not an answer.
    pinging: Boolean = false,
    /**
     * True when the subscription has never been fetched successfully.
     *
     * Different from merely empty: a provider that answered with an empty list is a
     * different situation from one that could not be reached, and telling the second
     * "Серверов нет" invites the user to blame the app.
     */
    fetchFailed: Boolean = false,
    // False when the group cannot expand/collapse (Home: its peek already shows
    // every server), hides the chevron and drops the header toggle, so there is
    // no tap-with-ripple that visibly changes nothing.
    expandable: Boolean = true,
    // True when the owning screen renders server rows directly under this card
    // even while not [expanded] (Home's 3-server peek): keeps the top-only
    // rounding + borderless chrome in both states so those rows continue the
    // same surfaceContainer. Defaults to [expanded], all-or-nothing screens
    // (the Servers tab) keep the standalone collapsed card.
    attachedBelow: Boolean = expanded,
) {
    val scheme = MaterialTheme.colorScheme
    val outline = LeanMetrics.outlineWidth
    // LeanIcon.Chev points right: 0° = collapsed, 90° = expanded/down.
    val chevRot by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "subChev",
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .depthShadow(LeanCorner.Card)
            .leanGlass(if (attachedBelow) LeanCorner.CardTop else LeanCorner.Card, scheme.surfaceContainer),
        // While rows continue below ([attachedBelow], expanded, or Home's
        // always-on peek), round only the top corners and drop the all-around
        // border: the server rows + footer below continue the same
        // surfaceContainer so the header + rows read as one grouped card (not a
        // header floating above background-less rows). Detached = a normal
        // fully-rounded standalone card.
        //
        // LeanCorner.CardTop, not a 22dp literal: this was one of the four raw radii in
        // the app, and after «Скругление» it would have been one of four visible seams
        // where a card ignored the ladder everything around it follows.
        shape = if (attachedBelow) LeanCorner.CardTop else MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = if (attachedBelow || outline <= 0.dp) {
            null
        } else {
            BorderStroke(
                outline,
                if (active) scheme.primary.copy(alpha = LeanMetrics.accentBorderAlpha) else scheme.outlineVariant,
            )
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (expandable) Modifier.clickable { onToggleExpanded() } else Modifier)
                .padding(16.dp),
            // Top-aligned (not centered): a wrapping meta line grows DOWN from
            // the name, so the badge and the action icons stay anchored to the
            // first line instead of drifting to the middle of a taller header.
            verticalAlignment = Alignment.Top,
        ) {
            // Neutral SurfaceElevated tile, glyph tinted primary.
            LeanBadge(LeanIcon.Cloud, tint = LeanColors.Accent)
            // 16dp pad + badge + 8dp gap, the hanging rule LeanDivider indents to. That
            // indent is derived from the badge size for exactly this reason: at
            // «Плотность → просторная» a fixed 62dp would leave the text column behind.
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    color = LeanColors.TextPrimary,
                    style = LeanType.cardName,
                    // A larger text scale eats the width the single-line ellipsis relied
                    // on, so the provider name gets the second line it then needs.
                    maxLines = if (LeanOptions.textScale >= 110) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                // Provider descriptions can be long: the column takes the full
                // weighted width between badge and icons. The header Row is
                // Alignment.Top, so the compact trailing icon cluster (refresh /
                // ⋮ / chevron) never forces a fixed height; the meta line is
                // free to wrap onto as many lines as it needs and grow the header
                // DOWN. No maxLines cap so a long description wraps fully instead
                // of clipping; softWrap explicit to make that intent self-evident.
                Text(
                    meta,
                    color = LeanColors.TextSecondary,
                    style = LeanType.meta,
                    softWrap = true,
                )
            }
            Spacer(Modifier.width(4.dp))
            // «проверить пинг» for this group only, sits just before the refresh
            // glyph (Pulse = the shared "ping" glyph across the app). Rendered
            // only when the screen wires it (Home); null on the Servers tab.
            onPing?.let { cb ->
                IconButton(onClick = cb) {
                    PingGlyph(pinging)
                }
            }
            // Disabled while refreshing: no caller pre-checks, so a second tap starts a
            // second fetch, and whichever finishes first clears the spinner while the
            // other is still writing.
            IconButton(onClick = onRefresh, enabled = !refreshing) {
                RefreshGlyph(refreshing)
            }
            // ⋮ overflow, subscription-level actions.
            if (onEdit != null || onCopyUrl != null || onDelete != null ||
                onToggleExcludedFromTest != null || onMoveToFolder != null
            ) {
                var menuOpen by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        LeanIconImage(
                            LeanIcon.Dots,
                            tint = LeanColors.TextSecondary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        onEdit?.let { cb ->
                            DropdownMenuItem(
                                text = { Text(tr("Изменить")) },
                                onClick = {
                                    menuOpen = false
                                    cb()
                                },
                            )
                        }
                        onCopyUrl?.let { cb ->
                            DropdownMenuItem(
                                text = { Text(tr("Скопировать ссылку")) },
                                onClick = {
                                    menuOpen = false
                                    cb()
                                },
                            )
                        }
                        onToggleExcludedFromTest?.let { cb ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (excludedFromTest) {
                                            tr("Вернуть в тест скорости")
                                        } else {
                                            tr("Исключить из теста скорости")
                                        },
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    cb()
                                },
                            )
                        }
                        onMoveToFolder?.let { cb ->
                            DropdownMenuItem(
                                text = { Text(tr("Переместить в папку")) },
                                onClick = {
                                    menuOpen = false
                                    cb()
                                },
                            )
                        }
                        onDelete?.let { cb ->
                            DropdownMenuItem(
                                text = { Text(tr("Удалить"), color = scheme.error) },
                                onClick = {
                                    menuOpen = false
                                    cb()
                                },
                            )
                        }
                    }
                }
            }
            // Collapse chevron, 16dp, TextTertiary, 0↔90° rotation. The 16dp
            // top padding centres it on the 48dp icon-button glyph line
            // (24dp − 8dp half-height) now that the header row is top-aligned.
            // Hidden entirely when the group can't expand ([expandable] = false):
            // a chevron pointing at nothing is a broken promise.
            if (expandable) {
                LeanIconImage(
                    LeanIcon.Chev,
                    tint = LeanColors.TextTertiary,
                    modifier = Modifier
                        .padding(top = 16.dp)
                        .size(16.dp)
                        .rotate(chevRot),
                )
            }
        }
        // Provider announce, parsed from the subscription headers or directives. It
        // wraps fully rather than clipping: providers routinely ship a long description
        // in this field, so the strip grows the card down. softWrap is explicit to make
        // that intent self-evident.
        if (announce.isNotBlank()) {
            LeanDivider()
            Text(
                announce.trim(),
                color = LeanColors.Accent,
                style = LeanType.meta,
                softWrap = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp),
            )
        }
        // Incy traffic/expiry strip, always visible (independent of collapse).
        // A limited plan earns it, an expiry date earns it, and so does known usage on
        // its own, an unlimited plan still spends gigabytes and the figure is worth
        // showing without a quota to measure it against. None of the three, no strip.
        if ((totalBytes != null && totalBytes > 0L) || expireEpochSec != null || usedBytes != null) {
            LeanDivider()
            TrafficStrip(usedBytes, totalBytes, expireEpochSec)
        }
        // Empty subscription (failed parse / nothing yet), a quiet placeholder
        // instead of a blank gap. The populated case is emitted by the screen as
        // separate lazy items right after this card (so the list virtualizes).
        if (expanded && isEmpty) {
            LeanDivider()
            Text(
                if (fetchFailed) {
                    tr("Не удалось получить эту подписку. Ссылка сохранена — попробуйте обновить её.")
                } else {
                    tr("Серверов нет — обновите подписку")
                },
                color = LeanColors.TextSecondary,
                style = LeanType.meta,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            )
        }
    }
}

/**
 * The shared «проверить пинг» glyph, breathing while a ping burst runs.
 *
 * A ping burst takes as long as its slowest probe, and the ViewModel silently
 * drops bursts requested while one is already running, so without a visible
 * "busy" state the button looks tappable, absorbs every tap and appears to do
 * nothing. Scale+alpha rather than [RefreshGlyph]'s spin: the Pulse waveform has
 * a clear horizontal reading direction and looks wrong rotating.
 *
 * Callers must also pass `enabled = !active` to the owning button; this only
 * renders the state.
 *
 * The transition is created only while a burst runs, the same rule [RefreshGlyph]
 * already follows and this glyph did not. An infinite transition drives a frame callback
 * for as long as it is composed, not for as long as its output is used, so every visible
 * subscription header was requesting animation frames throughout every fling.
 */
@Composable
internal fun PingGlyph(active: Boolean, size: Dp = 20.dp) {
    var scale = 1f
    var alpha = 1f
    if (active) {
        val pulse = rememberInfiniteTransition(label = "pingPulse")
        val pulseScale by pulse.animateFloat(
            initialValue = 1f,
            targetValue = 1.18f,
            animationSpec = infiniteRepeatable(tween(620, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "pingPulseScale",
        )
        val pulseAlpha by pulse.animateFloat(
            initialValue = 1f,
            targetValue = 0.45f,
            animationSpec = infiniteRepeatable(tween(620, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "pingPulseAlpha",
        )
        scale = pulseScale
        alpha = pulseAlpha
    }
    LeanIconImage(
        LeanIcon.Pulse,
        tint = if (active) LeanColors.Accent else LeanColors.TextSecondary,
        modifier = Modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            },
    )
}

/**
 * The refresh glyph. crucial for scroll performance: the spinning
 * [rememberInfiniteTransition] is created only while [refreshing] is true. An
 * infinite transition drives a per-frame callback for as long as it is composed,
 * so creating it unconditionally and gating only its output leaves every on-screen
 * subscription header requesting animation frames for as long as the list is scrolling.
 * Gating its existence means an idle header renders a static glyph that subscribes to
 * nothing.
 *
 * The glyph is mirrored horizontally (always, so it never pops mid-toggle):
 * [LeanIcon.Refresh] draws its arrowhead pointing counter-clockwise (left, at
 * the top of the ring), so a clockwise spin would lead tail-first. Other
 * screens use the icon statically, hence the shared vector stays untouched and
 * only this spinning instance flips. graphicsLayer composes scale before
 * rotation, so positive [rot] still reads as a clockwise spin on the mirrored
 * glyph, arrowhead chasing its own tail in the direction of motion.
 */
@Composable
private fun RefreshGlyph(refreshing: Boolean) {
    val rot = if (refreshing) {
        val spin = rememberInfiniteTransition(label = "subRefreshSpin")
        val angle by spin.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
            label = "subRefreshAngle",
        )
        angle
    } else {
        0f
    }
    LeanIconImage(
        LeanIcon.Refresh,
        tint = LeanColors.TextSecondary,
        modifier = Modifier
            .size(20.dp)
            .graphicsLayer {
                scaleX = -1f
                rotationZ = rot
            },
    )
}

/**
 * One virtualized subscription server row, emitted by the screen as its own
 * lazy item directly under its [SubscriptionCard]. Mirrors the look the rows had
 * when they were nested inside the card: a [LeanDivider] hairline at the 62dp
 * hanging indent followed by a flat ([ServerRow] `nested = true`) inset row. The
 * per-server long-press callbacks are forwarded exactly as before; nested rows
 * never get a delete action.
 */
@Composable
fun SubscriptionServerRow(
    profile: Profile,
    selected: Boolean,
    onSelect: () -> Unit,
    onPing: (() -> Unit)? = null,
    onRename: (() -> Unit)? = null,
    onCopyLink: (() -> Unit)? = null,
    onToggleFavorite: (() -> Unit)? = null,
    onToggleExcludeFromTest: (() -> Unit)? = null,
    onEditAwg: (() -> Unit)? = null,
    liveDelayMs: Int? = null,
    /** This row is being measured right now, see [ServerRow]. */
    probing: Boolean = false,
) {
    // surfaceContainer background so the (otherwise transparent) nested rows sit
    // on the subscription card surface (continuing the header above) instead of
    // floating on the bare screen background now that they're flattened into the
    // outer LazyColumn for virtualization.
    Column(Modifier.fillMaxWidth().leanGlass(RectangleShape, MaterialTheme.colorScheme.surfaceContainer)) {
        LeanDivider()
        ServerRow(
            probing = probing,
            profile = profile,
            selected = selected,
            onClick = onSelect,
            nested = true,
            onPing = onPing,
            onRename = onRename,
            onCopyLink = onCopyLink,
            onToggleFavorite = onToggleFavorite,
            onToggleExcludeFromTest = onToggleExcludeFromTest,
            onEditAwg = onEditAwg,
            liveDelayMs = liveDelayMs,
        )
    }
}

/**
 * traffic/expiry data block, following Incy's presentation:
 * a limited plan (real `totalBytes > 0`) gets the full block, caps «Трафик»
 * micro-label, days-left pill (Incy `traffic_days_left` = "%d days left"), a
 * tnum "used / total" readout and a slim 6dp progress bar (custom Box, zero
 * API risk). An unlimited plan (`totalBytes == 0` → Incy's "∞"/
 * `traffic_unlimited`, or no reported total) has nothing meaningful to chart,
 * so no readout and no bar, just the days-left pill under a quieter
 * «Подписка» label (the caller skips the strip entirely when such a plan has
 * no expiry either).
 *
 * Depletion warning at fraction ≥ 0.9 or when expired (bar flips to error);
 * the pill turns urgent (errorContainer) at ≤3 days left or "истекла".
 */
@Composable
private fun TrafficStrip(usedBytes: Long?, totalBytes: Long?, expireEpochSec: Long?) {
    val scheme = MaterialTheme.colorScheme
    val limited = totalBytes != null && totalBytes > 0L
    // Clamp expiry to 32e9 s (matches normalizeExpire): *1000 on a corrupt huge value
    // overflows Long and would read a far-future expiry as "expired".
    val expMs = expireEpochSec?.takeIf { it in 1L..32_000_000_000L }?.times(1000)
    val expired = expMs != null && expMs < System.currentTimeMillis()
    val daysLeft = expMs?.let {
        ((it - System.currentTimeMillis()).coerceAtLeast(0L) / 86_400_000L).toInt()
    }
    // Readout + fill fraction, only meaningful against a real quota. The
    // explicit null/zero checks repeat [limited] because smart casts don't
    // propagate through a derived Boolean.
    val fraction = if (usedBytes != null && totalBytes != null && totalBytes > 0L)
        (usedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else null
    // Against a quota the readout is "spent of allowance"; without one it is simply what
    // has been spent. The bar below still needs a quota to mean anything, so it stays
    // absent, a full-width bar on an unlimited plan would imply a limit that isn't there.
    val readout = when {
        usedBytes != null && totalBytes != null && totalBytes > 0L ->
            "${formatBytes(usedBytes)} / ${formatBytes(totalBytes)}"
        usedBytes != null -> formatBytes(usedBytes)
        else -> null
    }
    val warn = expired || (fraction ?: 0f) >= 0.9f

    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                // «ТРАФИК» heads the block whenever it states traffic, with a quota or
                // just the amount spent. Only an expiry-only strip is «ПОДПИСКА», which
                // is what that heading honestly describes.
                leanSectionText(if (limited || usedBytes != null) tr("Трафик") else tr("Подписка")),
                color = LeanColors.TextSecondary,
                style = leanSectionStyle(),
                modifier = Modifier.weight(1f),
            )
            if (expireEpochSec != null) {
                val urgent = expired || (daysLeft ?: 99) <= 3
                // Expiry pill (ValuePill corner 9): neutral SurfaceElevated, or
                // errorContainer/onErrorContainer when urgent. No borders.
                Box(
                    Modifier
                        .clip(LeanCorner.ValuePill)
                        .background(if (urgent) scheme.errorContainer else LeanColors.SurfaceElevated)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        if (expired) tr("истекла") else tr("осталось %d дн.").format(daysLeft ?: 0),
                        color = if (urgent) scheme.onErrorContainer else LeanColors.TextSecondary,
                        style = LeanType.valuePill,
                    )
                }
            }
        }
        if (readout != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                readout,
                color = LeanColors.TextPrimary,
                style = LeanType.statValue,
            )
        }
        if (fraction != null) {
            Spacer(Modifier.height(8.dp))
            // Custom 6dp bar kept, avoids the M3 1.3.x
            // LinearProgressIndicator gap/stop-dot pitfalls.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(LeanCorner.BarFill)
                    .background(LeanColors.BarUnlit),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(6.dp)
                        .clip(LeanCorner.BarFill)
                        .background(if (warn) LeanColors.Error else LeanColors.Accent),
                )
            }
        }
    }
}
