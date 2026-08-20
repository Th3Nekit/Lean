package com.th3web.lean.awg

private sealed interface ConfigNullabilityMarker

private data object NullableConfig : ConfigNullabilityMarker

private data object NonNullConfig : ConfigNullabilityMarker

@JvmName("selectNullableConfig")
private fun selectConfig(value: String?): NullableConfig = NullableConfig

@JvmName("selectNonNullConfig")
private fun selectConfig(value: String): NonNullConfig = NonNullConfig

@Suppress("unused")
private fun requireNullableConfigContract(native: AmneziaWgNative): NullableConfig =
    selectConfig(native.getConfig(0))
