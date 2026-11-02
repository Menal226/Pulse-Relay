package com.menal.pulserelay

import com.menal.pulserelay.data.SettingsValidation
import com.menal.pulserelay.data.SettingsValidationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsValidationTest {
    @Test
    fun `maps https to wss and adds default path`() {
        assertEquals(
            "wss://hr.example.com/ws",
            SettingsValidation.normalizeEndpoint("https://hr.example.com"),
        )
    }

    @Test
    fun `keeps an explicit websocket path`() {
        assertEquals(
            "ws://192.168.1.5:8080/custom",
            SettingsValidation.normalizeEndpoint("ws://192.168.1.5:8080/custom"),
        )
    }

    @Test
    fun `rejects invalid endpoints`() {
        assertEquals(null, SettingsValidation.normalizeEndpoint(""))
        assertEquals(null, SettingsValidation.normalizeEndpoint("ftp://example.com"))
        assertEquals(null, SettingsValidation.normalizeEndpoint("wss:///missing-host"))
    }

    @Test
    fun `validates token and interval`() {
        val valid = SettingsValidation.validate("example.com", "secret", "30")
        assertTrue(valid is SettingsValidationResult.Valid)
        valid as SettingsValidationResult.Valid
        assertEquals("wss://example.com/ws", valid.settings.endpoint)
        assertEquals(30, valid.settings.intervalSec)

        assertTrue(SettingsValidation.validate("example.com", "", "1") is SettingsValidationResult.Invalid)
        assertTrue(SettingsValidation.validate("example.com", "a b", "1") is SettingsValidationResult.Invalid)
        assertTrue(SettingsValidation.validate("example.com", "secret", "0") is SettingsValidationResult.Invalid)
        assertTrue(SettingsValidation.validate("example.com", "secret", "3601") is SettingsValidationResult.Invalid)
    }
}
