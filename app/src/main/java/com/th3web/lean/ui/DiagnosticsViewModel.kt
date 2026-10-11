package com.th3web.lean.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.th3web.lean.LeanApp
import com.th3web.lean.data.SettingsDefaults
import com.th3web.lean.data.net.DpiProbe

/**
 * Drives «Почему не работает»: runs [DpiProbe], turns the findings into advice, and —
 * when the user asks — applies the settings that follow from them.
 *
 * The advice is derived, never stored: a recommendation that outlives the measurement it
 * came from is how an app ends up telling someone to turn on fragmentation on a network
 * where nothing is being filtered.
 */
class DiagnosticsViewModel : ViewModel() {

    private val app get() = LeanApp.instance
    private val settings get() = app.settings

    private val _findings = MutableStateFlow<List<DpiProbe.Finding>>(emptyList())
    val findings: StateFlow<List<DpiProbe.Finding>> = _findings.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _applied = MutableStateFlow(false)
    val applied: StateFlow<Boolean> = _applied.asStateFlow()

    private var job: Job? = null

    fun start() {
        if (_running.value) return
        job?.cancel()
        job = viewModelScope.launch {
            _running.value = true
            _findings.value = emptyList()
            _applied.value = false
            try {
                DpiProbe.run(app) { finding ->
                    _findings.update { it + finding }
                }
            } finally {
                _running.value = false
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _running.value = false
    }

    /**
     * One piece of advice, with [actionable] telling the screen whether the app can do
     * anything about it or the user simply needs to know.
     */
    data class Advice(val text: String, val actionable: Boolean)

    /**
     * What the findings mean, in the order of what to do first.
     *
     * Each line maps one measured fault to the one setting that addresses it. Nothing is
     * suggested without evidence — an empty list means the link is clean, which is itself
     * worth saying, because it moves the search to the server rather than the network.
     */
    val advice: List<Advice>
        get() {
            val report = DpiProbe.Report(_findings.value)
            if (_findings.value.isEmpty()) return emptyList()
            val list = mutableListOf<Advice>()
            if (report.verdictOf(DpiProbe.ID_TCP) == DpiProbe.Verdict.Blocked) {
                return listOf(
                    Advice(
                        "Сеть не пускает даже обычный HTTPS. Это не DPI, а отсутствие доступа: " +
                            "проверьте Wi-Fi, баланс или тариф.",
                        actionable = false,
                    ),
                )
            }
            if (report.verdictOf(DpiProbe.ID_TLS_SNI) == DpiProbe.Verdict.Blocked) {
                list += Advice(
                    "Провайдер рвёт TLS по имени сайта. Включите фрагментацию и маскировку " +
                        "uTLS — тогда DPI не увидит имя целиком.",
                    actionable = true,
                )
            }
            if (report.verdictOf(DpiProbe.ID_SUSTAINED) == DpiProbe.Verdict.Blocked) {
                list += Advice(
                    "Соединение обрывают, когда пошёл реальный объём данных. Помогает та же " +
                        "фрагментация, а если не помогло — транспорт XHTTP или NaiveProxy.",
                    actionable = true,
                )
            }
            if (report.verdictOf(DpiProbe.ID_DNS) == DpiProbe.Verdict.Blocked) {
                list += Advice(
                    "DNS отвечает не тот, кого спрашивали. Нужен шифрованный DNS в туннеле " +
                        "и маршрутизация запросов через него.",
                    actionable = true,
                )
            }
            if (report.verdictOf(DpiProbe.ID_UDP) == DpiProbe.Verdict.Blocked) {
                list += Advice(
                    "UDP наружу не проходит: Hysteria2, TUIC и WireGuard здесь не заработают " +
                        "никакими настройками. Берите VLESS/Reality, XHTTP или NaiveProxy.",
                    actionable = false,
                )
            }
            if (list.isEmpty()) {
                list += Advice(
                    "Сеть чистая: ни фильтра по имени, ни обрывов, ни подмены DNS. " +
                        "Если сервер не работает — дело в сервере, а не в провайдере.",
                    actionable = false,
                )
            }
            return list
        }

    /** True when there is a settings change to offer. */
    val canApply: Boolean
        get() = !_running.value && advice.any { it.actionable }

    /**
     * Applies exactly what the findings call for, and nothing else.
     *
     * Each write is conditional on its own evidence, so running this on a clean network
     * changes nothing. Values the user has already set to something deliberate are left
     * alone where that is meaningful: the uTLS fingerprint is only set when it is still
     * the default, because someone who picked firefox for a reason should keep it.
     */
    fun applyRecommended() {
        viewModelScope.launch {
            val report = DpiProbe.Report(_findings.value)
            val current = settings.flow.first()
            val sniFiltered = report.verdictOf(DpiProbe.ID_TLS_SNI) == DpiProbe.Verdict.Blocked
            val torn = report.verdictOf(DpiProbe.ID_SUSTAINED) == DpiProbe.Verdict.Blocked
            val dnsForged = report.verdictOf(DpiProbe.ID_DNS) == DpiProbe.Verdict.Blocked

            if (sniFiltered || torn) {
                settings.setFragment(true)
                if (current.utlsFingerprint.isBlank() || current.utlsFingerprint == "none") {
                    settings.setUtlsFingerprint("chrome")
                }
            }
            if (dnsForged) {
                // Only when the user is still on a plain resolver: a DoH endpoint they
                // chose themselves is already the right shape, and overwriting it with
                // ours would be the app deciding who they trust.
                if (!current.remoteDns.startsWith("https://")) {
                    settings.setRemoteDns(SettingsDefaults.REMOTE_DNS)
                }
                // `dnsRouting` is already on for every install and has no switch of its
                // own (only a restored backup can turn it off), so there is nothing to
                // write here — the DoH resolver above is the whole fix.
            }
            _applied.value = true
        }
    }
}
