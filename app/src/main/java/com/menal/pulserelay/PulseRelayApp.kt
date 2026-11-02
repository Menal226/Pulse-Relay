package com.menal.pulserelay

import android.app.Application
import com.menal.pulserelay.data.SettingsStore
import com.menal.pulserelay.monitor.MonitorState
import com.menal.pulserelay.notifications.Notifications

class PulseRelayApp : Application() {
    lateinit var settingsStore: SettingsStore
    lateinit var monitorState: MonitorState

    override fun onCreate() {
        super.onCreate()
        settingsStore = SettingsStore(this)
        monitorState = MonitorState()
        Notifications.createChannel(this)
    }
}
