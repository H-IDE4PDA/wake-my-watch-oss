package com.h_ide4pda.wakemywatch.phone.settings

import android.content.Context
import com.h_ide4pda.wakemywatch.core.AppSettings
import com.h_ide4pda.wakemywatch.core.AppSettingsStore

typealias PhoneSettings = AppSettings

object PhoneSettingsStore {
    fun load(context: Context): PhoneSettings = AppSettingsStore.load(context)
    fun save(context: Context, settings: PhoneSettings) = AppSettingsStore.save(context, settings)
}
