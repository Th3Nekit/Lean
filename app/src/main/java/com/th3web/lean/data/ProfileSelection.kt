package com.th3web.lean.data

import com.th3web.lean.data.model.Profile

internal fun resolveProfileSelection(savedId: String?, profiles: List<Profile>): String? {
    if (profiles.isEmpty()) return null
    return when {
        savedId == AUTO_PROFILE_ID -> savedId
        profiles.any { it.id == savedId } -> savedId
        else -> profiles.first().id
    }
}

private const val AUTO_PROFILE_ID = "__auto__"
