package com.th3web.lean.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.th3web.lean.LeanApp
import com.th3web.lean.core.CoreManager
import com.th3web.lean.core.VpnState
import com.th3web.lean.data.net.ServiceCheck

/**
 * Drives «Что работает сейчас».
 *
 * Results are published one at a time as they land, because the probes are the slow part:
 * ten services over a mobile link take a few seconds, and filling the list progressively
 * is the difference between a screen that is working and a screen that looks stuck.
 */
class ServiceCheckViewModel : ViewModel() {

    private val app get() = LeanApp.instance

    private val _results = MutableStateFlow<List<ServiceCheck.Status>>(emptyList())
    val results: StateFlow<List<ServiceCheck.Status>> = _results.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** Whether the run was made with a tunnel up, which decides what the rows can claim. */
    private val _hadTunnel = MutableStateFlow(false)
    val hadTunnel: StateFlow<Boolean> = _hadTunnel.asStateFlow()

    private var job: Job? = null

    /** Services still waiting for a verdict, for the row placeholders. */
    val pending: StateFlow<Set<String>> get() = _pending.asStateFlow()
    private val _pending = MutableStateFlow<Set<String>>(emptySet())

    fun start() {
        if (_running.value) return
        job?.cancel()
        job = viewModelScope.launch {
            _running.value = true
            _results.value = emptyList()
            _pending.value = ServiceCheck.services.map { it.id }.toSet()
            _hadTunnel.value = CoreManager.state.value is VpnState.Connected
            try {
                ServiceCheck.checkAll(app) { status ->
                    _results.update { current ->
                        // Keep the declared order rather than arrival order: a list that
                        // reshuffles while it fills is unreadable.
                        (current + status).sortedBy { entry ->
                            ServiceCheck.services.indexOfFirst { it.id == entry.service.id }
                        }
                    }
                    _pending.update { it - status.service.id }
                }
            } finally {
                _running.value = false
                _pending.value = emptySet()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _running.value = false
        _pending.value = emptySet()
    }

    /** "8 из 10 работают", and how many of those only because of the tunnel. */
    val summary: Summary
        get() {
            val all = _results.value
            return Summary(
                total = ServiceCheck.services.size,
                working = all.count { it.verdict != ServiceCheck.Verdict.Unreachable &&
                    it.verdict != ServiceCheck.Verdict.BrokenByTunnel },
                onlyViaVpn = all.count { it.verdict == ServiceCheck.Verdict.BlockedWithoutVpn },
                brokenByTunnel = all.count { it.verdict == ServiceCheck.Verdict.BrokenByTunnel },
            )
        }

    data class Summary(
        val total: Int,
        val working: Int,
        val onlyViaVpn: Int,
        val brokenByTunnel: Int,
    )
}
