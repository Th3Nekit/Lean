package com.th3web.lean.data.net

internal enum class ManualDiagnosticsResult {
    Sent,
    Failed,
}

internal class ManualDiagnosticsSender(
    private val factory: CrashPayloadFactory,
    private val transport: CrashTransport,
) {
    fun send(): ManualDiagnosticsResult =
        runCatching {
            transport.post(CrashCodec.encodePayload(factory.createManual()))
        }.fold(
            onSuccess = {
                if (isAcceptedCrashResponse(it)) {
                    ManualDiagnosticsResult.Sent
                } else {
                    ManualDiagnosticsResult.Failed
                }
            },
            onFailure = { ManualDiagnosticsResult.Failed },
        )
}
