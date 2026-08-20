package com.th3web.lean.ui.screen.appearance

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.th3web.lean.LeanApp
import com.th3web.lean.ui.components.LeanDivider
import com.th3web.lean.ui.components.LeanGroup
import com.th3web.lean.ui.components.LeanToggleItem
import com.th3web.lean.ui.icons.LeanIcon
import com.th3web.lean.ui.screen.AppearanceHeader
import com.th3web.lean.ui.screen.HubScaffold
import com.th3web.lean.ui.screen.KnobHint
import com.th3web.lean.ui.screen.KnobSegments
import com.th3web.lean.ui.screen.rememberAppearanceEditor
import com.th3web.lean.ui.screen.rememberLook
import com.th3web.lean.ui.theme.LeanColors
import com.th3web.lean.ui.tr

/**
 * «Движение», how much the interface animates, and whether the system gets a veto.
 *
 * The showcase reacts to «Анимации» immediately: at `off` its two infinite transitions are
 * not merely stopped, they are never created, so the knob is visible as the sweeping arc
 * going still the moment it is tapped.
 */
@Composable
fun AppearanceMotionScreen(onBack: () -> Unit) {
    val repo = LeanApp.instance.settings
    val settings by repo.state.collectAsStateWithLifecycle()
    val editor = rememberAppearanceEditor(settings)
    val look = rememberLook(settings)

    HubScaffold(
        tr("Движение"),
        onBack,
        header = { AppearanceHeader(look, settings.appearancePreview) },
    ) {
        KnobSegments(
            tr("Анимации"),
            listOf(tr("Выкл"), tr("Спокойные"), tr("Обычные"), tr("Живые")),
            MOTION_LEVELS.indexOf(settings.motionLevel).coerceAtLeast(0),
        ) { i -> editor.edit { setMotionLevel(MOTION_LEVELS[i]) } }
        KnobHint(tr("Множитель длительности всех переходов. «Выкл» убирает и сами анимации, и постоянные перерисовки, которые они заказывают."))

        KnobSegments(
            tr("Плавные переходы цвета"),
            listOf(tr("Включены"), tr("Выключены")),
            if (settings.colorCrossfade == "off") 1 else 0,
        ) { i -> editor.edit { setColorCrossfade(if (i == 1) "off" else "on") } }
        KnobHint(tr("Цвета кнопки подключения и списка перетекают, а не переключаются рывком."))

        KnobSegments(
            tr("Вибро-отклик"),
            listOf(tr("Нет"), tr("Лёгкий"), tr("Обычный")),
            HAPTICS.indexOf(settings.haptics).coerceAtLeast(0),
        ) { i -> editor.edit { setHaptics(HAPTICS[i]) } }

        Spacer(Modifier.height(14.dp))
        LeanGroup {
            LeanToggleItem(
                LeanIcon.Power, LeanColors.Accent, tr("Учитывать системные настройки анимаций"),
                tr("Если выключить, приложение будет анимировать даже в режиме энергосбережения"),
                settings.respectSystemAnimations,
            ) { on -> editor.edit { setRespectSystemAnimations(on) } }
            LeanDivider()
            LeanToggleItem(
                LeanIcon.Refresh, LeanColors.Violet, tr("Переливание баннера"),
                tr("Бегущий блик на баннере Telegram"), settings.bannerSheen,
            ) { on -> editor.edit { setBannerSheen(on) } }
        }
    }
}

private val MOTION_LEVELS = listOf("off", "calm", "normal", "lively")
private val HAPTICS = listOf("none", "light", "normal")
