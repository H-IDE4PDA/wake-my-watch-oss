package com.h_ide4pda.wakemywatch.core

import android.content.Context
import android.hardware.SensorManager
import android.media.RingtoneManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/**
 * OnePlus Watch model code -> human name (introduced vc61). Дописывать новые модели сюда.
 * The technical "Model" row keeps showing the raw codename; only headline UI uses this.
 */
private val WATCH_MODEL_NAMES = mapOf(
    "OPWWE261" to "OnePlus Watch 4",
    "OPWWE251" to "OnePlus Watch 3",
    "OPWWE234" to "OnePlus Watch 2R",
    "OPWWE231" to "OnePlus Watch 2",
)

object DeviceFeatures {
    const val WAKE = "wake"
    const val ACK = "ack"
    const val SOUND = "sound"
    const val OFF_BODY = "off_body"
    const val ALARM_BRIDGE = "alarm_bridge"
}

data class DeviceDescriptor(
    val role: DeviceRole,
    val manufacturer: String,
    val model: String,
    val osRelease: String,
    val sdkInt: Int,
    val buildDisplay: String,
    val appVersion: String,
    val notificationSoundName: String?,
    val features: Set<String>,
    val nodeId: String? = null,
    val lastSeenAt: Long = System.currentTimeMillis(),
) {
    val displayName: String
        get() = listOf(manufacturer, model)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .replace(Regex("(?i)^${Regex.escape(manufacturer)}\\s+${Regex.escape(manufacturer)}\\s+"), "$manufacturer ")

    /**
     * Human-readable name for headline UI (device-card title). Resolves the watch model code
     * against [WATCH_MODEL_NAMES]; falls back to the raw [displayName] when the model is unknown
     * to the dictionary. The "Model" detail row deliberately keeps [displayName].
     */
    val headline: String
        get() = WATCH_MODEL_NAMES[model.trim()] ?: displayName

    fun toJson(): JSONObject = JSONObject()
        .put("role", role.name)
        .put("manufacturer", manufacturer)
        .put("model", model)
        .put("osRelease", osRelease)
        .put("sdkInt", sdkInt)
        .put("buildDisplay", buildDisplay)
        .put("appVersion", appVersion)
        .put("notificationSoundName", notificationSoundName)
        .put("features", JSONArray(features.toList()))
        .put("nodeId", nodeId)
        .put("lastSeenAt", lastSeenAt)

    fun withNode(nodeId: String): DeviceDescriptor = copy(nodeId = nodeId, lastSeenAt = System.currentTimeMillis())

    companion object {
        fun current(context: Context, role: DeviceRole): DeviceDescriptor {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            @Suppress("DEPRECATION")
            val versionName = packageInfo.versionName ?: "unknown"
            val features = buildSet {
                add(DeviceFeatures.WAKE)
                add(DeviceFeatures.ACK)
                add(DeviceFeatures.SOUND)
                if (role == DeviceRole.WATCH) {
                    val hasOffBody = runCatching {
                        context.getSystemService(SensorManager::class.java).getDefaultSensor(34) != null
                    }.getOrDefault(false)
                    if (hasOffBody) add(DeviceFeatures.OFF_BODY)
                }
                add(DeviceFeatures.ALARM_BRIDGE)
            }
            return DeviceDescriptor(
                role = role,
                manufacturer = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() },
                model = Build.MODEL.orEmpty(),
                osRelease = Build.VERSION.RELEASE.orEmpty(),
                sdkInt = Build.VERSION.SDK_INT,
                buildDisplay = Build.DISPLAY.orEmpty(),
                appVersion = versionName,
                notificationSoundName = runCatching {
                    val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_NOTIFICATION)
                    uri?.let { RingtoneManager.getRingtone(context, it)?.getTitle(context) }
                }.getOrNull()?.takeIf { it.isNotBlank() },
                features = features,
            )
        }

        fun fromJson(json: JSONObject): DeviceDescriptor {
            val rawFeatures = json.optJSONArray("features") ?: JSONArray()
            val features = buildSet {
                for (index in 0 until rawFeatures.length()) add(rawFeatures.optString(index))
            }
            return DeviceDescriptor(
                role = runCatching { DeviceRole.valueOf(json.optString("role")) }.getOrDefault(DeviceRole.WATCH),
                manufacturer = json.optString("manufacturer"),
                model = json.optString("model"),
                osRelease = json.optString("osRelease"),
                sdkInt = json.optInt("sdkInt"),
                buildDisplay = json.optString("buildDisplay"),
                appVersion = json.optString("appVersion"),
                notificationSoundName = json.optString("notificationSoundName")
                    .takeIf { it.isNotBlank() && it != "null" },
                features = features,
                nodeId = json.optString("nodeId").takeIf { it.isNotBlank() && it != "null" },
                lastSeenAt = json.optLong("lastSeenAt", System.currentTimeMillis()),
            )
        }
    }
}
