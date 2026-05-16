/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.providers

import java.util.Locale

object ProviderIsrc {
    private val ISRC_REGEX = Regex("[A-Z]{2}[A-Z0-9]{3}[0-9]{7}")

    fun normalize(value: String?): String? {
        val compact = value
            ?.uppercase(Locale.US)
            ?.replace(Regex("[^A-Z0-9]"), "")
            ?: return null
        return ISRC_REGEX.find(compact)?.value
    }

    fun firstOf(vararg values: String?): String? =
        values.firstNotNullOfOrNull(::normalize)
}
