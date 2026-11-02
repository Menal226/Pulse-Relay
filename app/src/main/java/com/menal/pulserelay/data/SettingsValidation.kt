package com.menal.pulserelay.data

import java.net.URI
import java.net.URISyntaxException

data class ValidSettings(
    val endpoint: String,
    val token: String,
    val intervalSec: Int,
)

sealed interface SettingsValidationResult {
    data class Valid(val settings: ValidSettings) : SettingsValidationResult
    data class Invalid(val message: String) : SettingsValidationResult
}

object SettingsValidation {
    const val MIN_INTERVAL_SEC = 1
    const val MAX_INTERVAL_SEC = 3600

    fun validate(
        endpointRaw: String,
        tokenRaw: String,
        intervalRaw: String,
    ): SettingsValidationResult {
        val endpoint = normalizeEndpoint(endpointRaw)
            ?: return SettingsValidationResult.Invalid("Enter a valid ws:// or wss:// URL")

        val token = tokenRaw.trim()
        if (token.isEmpty()) {
            return SettingsValidationResult.Invalid("Token is required")
        }
        if (token.any { it.isWhitespace() } || token.contains('\r') || token.contains('\n')) {
            return SettingsValidationResult.Invalid("Token must not contain whitespace")
        }

        val interval = intervalRaw.trim().toIntOrNull()
            ?: return SettingsValidationResult.Invalid("Interval must be a whole number")
        if (interval !in MIN_INTERVAL_SEC..MAX_INTERVAL_SEC) {
            return SettingsValidationResult.Invalid(
                "Interval must be between $MIN_INTERVAL_SEC and $MAX_INTERVAL_SEC seconds"
            )
        }

        return SettingsValidationResult.Valid(
            ValidSettings(endpoint = endpoint, token = token, intervalSec = interval)
        )
    }

    /**
     * Returns a canonical ws:// or wss:// URL, or null if the input is not usable.
     * https:// is mapped to wss:// and http:// to ws://.
     * An empty or "/" path becomes "/ws".
     */
    fun normalizeEndpoint(raw: String): String? {
        var text = raw.trim()
        if (text.isEmpty()) return null
        if (!text.contains("://")) {
            text = "wss://$text"
        }

        val parsed = try {
            URI(text)
        } catch (_: URISyntaxException) {
            return null
        }

        if (parsed.fragment != null) return null
        if (parsed.host.isNullOrBlank()) return null
        if (parsed.userInfo != null) return null

        val scheme = when (parsed.scheme?.lowercase()) {
            "ws" -> "ws"
            "wss" -> "wss"
            "http" -> "ws"
            "https" -> "wss"
            else -> return null
        }

        val path = parsed.rawPath?.takeIf { it.isNotBlank() && it != "/" } ?: "/ws"

        return try {
            URI(scheme, null, parsed.host, parsed.port, path, parsed.rawQuery, null).toString()
        } catch (_: URISyntaxException) {
            null
        }
    }
}
