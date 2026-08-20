package com.th3web.lean.ui

/**
 * The one line under the app name on «О программе», the first thing a new user reads.
 *
 * Written from the reader's side. The previous wording ("Собственный клиент … к личной
 * инфраструктуре") was written from the author's: "собственный" and "личной" describe
 * whose project this is, which tells the person holding the phone nothing about what the
 * app does for them, and «личная инфраструктура» actively reads as "you need your own
 * servers", which is wrong (a subscription link is the normal path).
 */
internal const val PRODUCT_DESCRIPTION =
    "Клиент для подключения к VPN-серверам: подписки и свои конфиги, обход блокировок, " +
        "выбор быстрейшего сервера."

/**
 * Shown under «О Lean» in the settings grid.
 *
 * Names the runtime that is actually linked in. This read "sing-box" long after the engine
 * had moved to NekoBox's libcore, a fork with its own behaviour and its own version string
 * so the one line a user would quote in a bug report was wrong.
 */
internal const val SETTINGS_RUNTIME_VERSION =
    "Версия %s · libcore (NekoBox) + AmneziaWG-Go"

internal const val AWG_EDITOR_DESCRIPTION =
    "Использует отдельное нативное ядро AmneziaWG-Go. Junk-пакеты Jc/Jmin/Jmax маскируют " +
        "рукопожатие от DPI; S1–S4, H1–H4 и I1–I5 должны совпадать с настройками сервера."

// ---- «О программе» ----

/** The subscription bot, same handle the promo banner opens, kept in one place. */
internal const val LEAN_BOT_URL = "https://t.me/VPN_Lean_bot"
internal const val BOOSTY_URL = "https://boosty.to/th3nekit"

/**
 * Runtime credits.
 *
 * Every claim here is checked against `native/versions.lock` and `THIRD_PARTY_NOTICES.md`,
 * which pin the exact revisions CI compiles. This screen is the only place a user sees what
 * is inside, so a stale line is a false statement about someone else's work, not a
 * cosmetic slip.
 */
internal const val CREDIT_ENGINE =
    "Сетевое ядро — libcore из NekoBox for Android (MatsuriDayo), собранное из исходников; " +
        "внутри — форк sing-box того же автора. Оба под GPL-3.0."

internal const val CREDIT_PROTOCOLS =
    "Протоколы VLESS/Reality, VMess, Trojan, Shadowsocks, Hysteria2 и TUIC обеспечиваются " +
        "этим ядром."

internal const val CREDIT_HELPERS =
    "Отдельными процессами внутри приложения работают NaiveProxy (klzgrad, BSD-3-Clause), " +
        "Mieru (enfein, GPL-3.0), olcRTC (OpenLibreCommunity, WTFPL) и Xray-core " +
        "(XTLS, MPL-2.0) — последний нужен только для VLESS поверх XHTTP."

/**
 * The same line for a build that carries no NaiveProxy.
 *
 * The F-Droid variant compiles every helper from source, and NaiveProxy is a Chromium
 * fork rather than a Go program, so it is not there. Naming it anyway would make this
 * screen state something the build does not contain, and this screen is precisely where
 * a user goes to check what is inside. [AboutScreen] picks between the two by asking
 * whether the binary is actually present, so the wording is right on a device that
 * simply has no build of it for its ABI as well.
 */
internal const val CREDIT_HELPERS_NO_NAIVE =
    "Отдельными процессами внутри приложения работают Mieru (enfein, GPL-3.0), olcRTC " +
        "(OpenLibreCommunity, WTFPL) и Xray-core (XTLS, MPL-2.0) — последний нужен только " +
        "для VLESS поверх XHTTP. NaiveProxy в эту сборку не входит."

internal const val CREDIT_AWG =
    "WireGuard и AmneziaWG — отдельное нативное ядро AmneziaWG-Go (Amnezia VPN, MIT) " +
        "с JNI-обвязкой из amneziawg-android (Apache-2.0)."

internal const val CREDIT_DESIGN =
    "Дизайн и идеи интерфейса вдохновлены приложениями Happ и Incy."

internal const val CREDIT_LICENSE =
    "Сам Lean распространяется под GPL-3.0-or-later: приложение линкуется с sing-box и " +
        "libcore, а они под GPL, поэтому и сборка целиком под ней же. Тексты лицензий " +
        "лежат ниже, точные ревизии зафиксированы в native/versions.lock."

