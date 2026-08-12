package com.h_ide4pda.wakemywatch.watch

import android.app.Application
import com.h_ide4pda.wakemywatch.core.AppSettingsStore
import com.h_ide4pda.wakemywatch.watch.sensors.OffBodyStateMonitor

class WakeMyWatchApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        OffBodyStateMonitor.updateRegistration(this, AppSettingsStore.load(this))
    }
}
