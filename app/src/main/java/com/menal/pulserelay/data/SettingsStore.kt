package com.menal.pulserelay.data

import android.annotation.SuppressLint
import android.content.Context

data class StoredSettings(
    val endpoint: String,
    val token: String,
    val intervalSec: Int,
    val enabled: Boolean,
)

class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "pulse_relay_settings",
        Context.MODE_PRIVATE,
    )

    fun load(): StoredSettings = StoredSettings(
        endpoint = prefs.getString(KEY_ENDPOINT, "").orEmpty(),
        token = prefs.getString(KEY_TOKEN, "").orEmpty(),
        intervalSec = prefs.getInt(KEY_INTERVAL, 1).coerceIn(
            SettingsValidation.MIN_INTERVAL_SEC,
            SettingsValidation.MAX_INTERVAL_SEC,
        ),
        enabled = prefs.getBoolean(KEY_ENABLED, false),
    )

    fun save(settings: ValidSettings) {
        prefs.edit()
            .putString(KEY_ENDPOINT, settings.endpoint)
            .putString(KEY_TOKEN, settings.token)
            .putInt(KEY_INTERVAL, settings.intervalSec)
            .apply()
    }

    @SuppressLint("ApplySharedPref")
    fun setEnabled(enabled: Boolean) {
        // commit so a reboot receiver sees the latest state immediately
        prefs.edit().putBoolean(KEY_ENABLED, enabled).commit()
    }

    fun validatedOrNull(): ValidSettings? {
        val current = load()
        return when (
            val result = SettingsValidation.validate(
                current.endpoint,
                current.token,
                current.intervalSec.toString(),
            )
        ) {
            is SettingsValidationResult.Valid -> result.settings
            is SettingsValidationResult.Invalid -> null
        }
    }

    private companion object {
        const val KEY_ENDPOINT = "endpoint"
        const val KEY_TOKEN = "token"
        const val KEY_INTERVAL = "interval_sec"
        const val KEY_ENABLED = "enabled"
    }
}
