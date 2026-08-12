package com.h_ide4pda.wakemywatch.phone.permissions

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.h_ide4pda.wakemywatch.core.AppSettingsStore

object PermissionProbe {
    data class Snapshot(
        val manufacturer: String,
        val brand: String,
        val model: String,
        val androidRelease: String,
        val sdkInt: Int,
        val notificationListenerGranted: Boolean,
        val postNotificationsGranted: Boolean,
        val dndPolicyAccessGranted: Boolean,
        val batteryOptimizationIgnored: Boolean,
        val exactAlarmAllowed: Boolean?,
        val writeSecureSettingsGranted: Boolean,
        val currentInterruptionFilter: Int,
        val nativeOHealthPhone: Boolean,
        /** Raw manufacturer/brand check, unaffected by forceSoftwareDndSync — lets UI decide
         * whether to offer the software-sync toggle regardless of its current on/off state. */
        val rawNativeOHealthPhone: Boolean,
    ) {
        val dndSyncAvailable: Boolean get() = !nativeOHealthPhone
        val dndSyncStatus: String get() = if (nativeOHealthPhone) {
            "disabled_native_ohealth_phone"
        } else if (notificationListenerGranted && dndPolicyAccessGranted) {
            "ready_phone_permissions"
        } else {
            "missing_phone_permissions"
        }

        val criticalIssues: Int get() = listOf(
            notificationListenerGranted,
            postNotificationsGranted,
        ).count { !it }

        val warningIssues: Int get() = buildList {
            if (!batteryOptimizationIgnored) add("battery")
            if (dndSyncAvailable && !dndPolicyAccessGranted) add("dnd_policy")
            if (exactAlarmAllowed == false) add("exact_alarm")
        }.size
    }

    fun snapshot(context: Context): Snapshot {
        val app = context.applicationContext
        val notificationManager = app.getSystemService(NotificationManager::class.java)
        val powerManager = app.getSystemService(PowerManager::class.java)
        val alarmManager = app.getSystemService(AlarmManager::class.java)
        val notificationListenerGranted = NotificationManagerCompat
            .getEnabledListenerPackages(app)
            .contains(app.packageName)
        val postNotificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val dndPolicyAccessGranted = runCatching { notificationManager.isNotificationPolicyAccessGranted }.getOrDefault(false)
        val batteryOptimizationIgnored = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching { powerManager.isIgnoringBatteryOptimizations(app.packageName) }.getOrDefault(false)
        } else {
            true
        }
        val exactAlarmAllowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { alarmManager.canScheduleExactAlarms() }.getOrNull()
        } else {
            true
        }
        val writeSecureSettingsGranted = app.checkCallingOrSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED
        val currentInterruptionFilter = runCatching { notificationManager.currentInterruptionFilter }.getOrDefault(-1)
        val manufacturer = Build.MANUFACTURER.orEmpty()
        val brand = Build.BRAND.orEmpty()
        return Snapshot(
            manufacturer = manufacturer,
            brand = brand,
            model = Build.MODEL.orEmpty(),
            androidRelease = Build.VERSION.RELEASE.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT,
            notificationListenerGranted = notificationListenerGranted,
            postNotificationsGranted = postNotificationsGranted,
            dndPolicyAccessGranted = dndPolicyAccessGranted,
            batteryOptimizationIgnored = batteryOptimizationIgnored,
            exactAlarmAllowed = exactAlarmAllowed,
            writeSecureSettingsGranted = writeSecureSettingsGranted,
            currentInterruptionFilter = currentInterruptionFilter,
            // Software DND sync toggle (forceSoftwareDndSync, user-facing setting): when the user
            // has explicitly enabled it, treat the phone as non-native so our own sync + its UI
            // unlock, even on OnePlus/Oppo/Realme. isNativeOHealthPhone() itself is untouched.
            nativeOHealthPhone = !AppSettingsStore.load(app).forceSoftwareDndSync && isNativeOHealthPhone(manufacturer, brand),
            rawNativeOHealthPhone = isNativeOHealthPhone(manufacturer, brand),
        )
    }

    fun report(context: Context, snapshot: Snapshot): String = buildString {
        append("Permission probe\n")
        append("phoneManufacturer=").append(snapshot.manufacturer).append('\n')
        append("phoneBrand=").append(snapshot.brand).append('\n')
        append("phoneModel=").append(snapshot.model).append('\n')
        append("android=").append(snapshot.androidRelease).append(" api=").append(snapshot.sdkInt).append('\n')
        append("nativeOHealthPhone=").append(snapshot.nativeOHealthPhone).append('\n')
        append("forceSoftwareDndSync=").append(AppSettingsStore.load(context).forceSoftwareDndSync).append('\n')
        append("dndSyncAvailable=").append(snapshot.dndSyncAvailable).append('\n')
        append("dndSyncStatus=").append(snapshot.dndSyncStatus).append('\n')
        append("notificationListenerGranted=").append(snapshot.notificationListenerGranted).append('\n')
        append("postNotificationsGranted=").append(snapshot.postNotificationsGranted).append('\n')
        append("dndPolicyAccessGranted=").append(snapshot.dndPolicyAccessGranted).append('\n')
        append("batteryOptimizationIgnored=").append(snapshot.batteryOptimizationIgnored).append('\n')
        append("exactAlarmAllowed=").append(snapshot.exactAlarmAllowed ?: "unknown").append('\n')
        append("writeSecureSettingsGranted=").append(snapshot.writeSecureSettingsGranted).append('\n')
        append("currentInterruptionFilter=").append(snapshot.currentInterruptionFilter).append('\n')
    }.trimEnd()

    private fun isNativeOHealthPhone(manufacturer: String, brand: String): Boolean {
        val values = listOf(manufacturer, brand).map { it.lowercase() }
        return values.any { value ->
            value.contains("oneplus") || value.contains("oppo") || value.contains("realme")
        }
    }
}
