package com.th3web.lean.core

sealed interface VpnState {
    data object Disconnected : VpnState
    data object Connecting : VpnState
    data class Connected(val profileId: String) : VpnState
    data object Stopping : VpnState
    data class Error(val message: String) : VpnState
}

data class TrafficStats(
    val uplink: Long = 0,
    val downlink: Long = 0,
    val uplinkTotal: Long = 0,
    val downlinkTotal: Long = 0,
)

data class OutboundNode(
    val tag: String,
    val type: String,
    /**
     * The node's delay as measured through the tunnel, or null when it has never been
     * measured, which is the case for every node until a retest actually runs.
     *
     * Nullable. A non-null Int seeded at connect time makes an untested server
     * arrive as 0, and the UI treats this value as the authoritative "does it really carry
     * traffic" signal, so it would show a green ✓ "0 мс" for a server nobody probed.
     * Absent has to be representable, or "unknown" and "instant" are the same number.
     */
    val delayMs: Int?,
)

data class OutboundGroupState(
    val tag: String,
    val selected: String,
    val items: List<OutboundNode>,
)
