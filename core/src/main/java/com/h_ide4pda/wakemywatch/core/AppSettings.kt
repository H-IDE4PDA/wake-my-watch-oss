package com.h_ide4pda.wakemywatch.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class SoundMode(val wireValue: Int) {
    NONE(0),
    SYSTEM(1);

    companion object {
        fun fromWire(value: Int): SoundMode = entries.firstOrNull { it.wireValue == value } ?: SYSTEM
    }
}

enum class AppFilterMode {
    ALL,
    ALLOWLIST,
    BLOCKLIST;

    companion object {
        fun fromWire(value: String): AppFilterMode = entries.firstOrNull { it.name == value } ?: ALL
    }
}

data class AppSettings(
    val revision: Long = 0L,
    val updatedAt: Long = 0L,
    val screenWake: Boolean = true,
    val respectPhoneDnd: Boolean = true,
    val wakeScreenOnPhoneDnd: Boolean = true,
    val skipWhenPhoneUnlocked: Boolean = true,
    val skipSilentNotifications: Boolean = true,
    val respectWatchDnd: Boolean = true,
    val skipWakeOffWrist: Boolean = true,
    val skipSoundOffWrist: Boolean = true,
    val soundMode: SoundMode = SoundMode.SYSTEM,
    val alarmBridge: Boolean = false,
    val dndSyncEnabled: Boolean = false,
    val forceSoftwareDndSync: Boolean = false,
    val dndSyncVibrate: Boolean = true,
    val appFilterMode: AppFilterMode = AppFilterMode.ALL,
    val appPackages: Set<String> = emptySet(),
    val appPaused: Boolean = false,
    val pauseKeepsAlarmAndDnd: Boolean = false,
    val savedAlarmBridgeBeforePause: Boolean? = null,
    val savedDndSyncEnabledBeforePause: Boolean? = null,
) {
    fun nextRevision(now: Long = System.currentTimeMillis()): AppSettings = copy(
        revision = maxOf(revision + 1L, now),
        updatedAt = now,
    )

    fun isPackageAllowed(packageName: String): Boolean = when (appFilterMode) {
        AppFilterMode.ALL -> true
        AppFilterMode.ALLOWLIST -> packageName in appPackages
        AppFilterMode.BLOCKLIST -> packageName !in appPackages
    }

    /** Paused with no exception: Alarm Bridge and DND Sync are silenced along with everything else. */
    val isFullyPaused: Boolean
        get() = appPaused && !pauseKeepsAlarmAndDnd

    /** Paused, but Alarm Bridge and DND Sync are explicitly allowed to keep working (sleep-safe mode). */
    val isPausedButAlarmDndAllowed: Boolean
        get() = appPaused && pauseKeepsAlarmAndDnd

    fun toJson(): JSONObject = JSONObject()
        .put("revision", revision)
        .put("updatedAt", updatedAt)
        .put("screenWake", screenWake)
        .put("respectPhoneDnd", respectPhoneDnd)
        .put("wakeScreenOnPhoneDnd", wakeScreenOnPhoneDnd)
        .put("skipWhenPhoneUnlocked", skipWhenPhoneUnlocked)
        .put("skipSilentNotifications", skipSilentNotifications)
        .put("respectWatchDnd", respectWatchDnd)
        .put("skipWakeOffWrist", skipWakeOffWrist)
        .put("skipSoundOffWrist", skipSoundOffWrist)
        .put("soundMode", soundMode.wireValue)
        .put("alarmBridge", alarmBridge)
        .put("dndSyncEnabled", dndSyncEnabled)
        .put("forceSoftwareDndSync", forceSoftwareDndSync)
        .put("dndSyncVibrate", dndSyncVibrate)
        .put("appFilterMode", appFilterMode.name)
        .put("appPackages", JSONArray(appPackages.sorted()))
        .put("appPaused", appPaused)
        .put("pauseKeepsAlarmAndDnd", pauseKeepsAlarmAndDnd)
        .put("savedAlarmBridgeBeforePause", savedAlarmBridgeBeforePause ?: JSONObject.NULL)
        .put("savedDndSyncEnabledBeforePause", savedDndSyncEnabledBeforePause ?: JSONObject.NULL)

    companion object {
        fun fromJson(json: JSONObject): AppSettings {
            val packages = buildSet {
                val array = json.optJSONArray("appPackages") ?: JSONArray()
                for (index in 0 until array.length()) {
                    array.optString(index).takeIf(String::isNotBlank)?.let(::add)
                }
            }
            return AppSettings(
                revision = json.optLong("revision", 0L),
                updatedAt = json.optLong("updatedAt", 0L),
                screenWake = json.optBoolean("screenWake", true),
                respectPhoneDnd = json.optBoolean("respectPhoneDnd", true),
                wakeScreenOnPhoneDnd = json.optBoolean("wakeScreenOnPhoneDnd", true),
                skipWhenPhoneUnlocked = json.optBoolean("skipWhenPhoneUnlocked", true),
                skipSilentNotifications = json.optBoolean("skipSilentNotifications", true),
                respectWatchDnd = json.optBoolean("respectWatchDnd", true),
                skipWakeOffWrist = json.optBoolean("skipWakeOffWrist", true),
                skipSoundOffWrist = json.optBoolean("skipSoundOffWrist", true),
                soundMode = SoundMode.fromWire(json.optInt("soundMode", SoundMode.SYSTEM.wireValue)),
                alarmBridge = json.optBoolean("alarmBridge", false),
                dndSyncEnabled = json.optBoolean("dndSyncEnabled", false),
                forceSoftwareDndSync = json.optBoolean("forceSoftwareDndSync", false),
                dndSyncVibrate = json.optBoolean("dndSyncVibrate", true),
                appFilterMode = AppFilterMode.fromWire(json.optString("appFilterMode", AppFilterMode.ALL.name)),
                appPackages = packages,
                appPaused = json.optBoolean("appPaused", false),
                pauseKeepsAlarmAndDnd = json.optBoolean("pauseKeepsAlarmAndDnd", false),
                savedAlarmBridgeBeforePause = if (json.isNull("savedAlarmBridgeBeforePause")) null else json.optBoolean("savedAlarmBridgeBeforePause"),
                savedDndSyncEnabledBeforePause = if (json.isNull("savedDndSyncEnabledBeforePause")) null else json.optBoolean("savedDndSyncEnabledBeforePause"),
            )
        }
    }
}

object AppSettingsStore {
    const val PREFS_NAME = "wmw_app_settings"
    private const val SETTINGS = "settings_json"

    fun load(context: Context): AppSettings {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(SETTINGS, null)
            ?: return AppSettings()
        return runCatching { AppSettings.fromJson(JSONObject(raw)) }.getOrElse { AppSettings() }
    }

    fun save(context: Context, settings: AppSettings) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(SETTINGS, settings.toJson().toString())
            .apply()
    }

    fun saveIfNewer(context: Context, incoming: AppSettings): Boolean {
        val current = load(context)
        if (incoming.revision < current.revision) return false
        if (incoming.revision == current.revision && incoming.updatedAt < current.updatedAt) return false
        save(context, incoming)
        return true
    }
}

object SettingsSync {
    fun send(context: Context, settings: AppSettings, callback: (WearTransport.Result) -> Unit = {}) {
        val envelope = MessageEnvelope(
            type = "SETTINGS",
            payload = JSONObject().put("settings", settings.toJson()),
        )
        WearTransport.sendPreferred(context, Protocol.SETTINGS, envelope, callback = callback)
    }

    fun extract(envelope: MessageEnvelope): AppSettings? = envelope.payload
        .optJSONObject("settings")
        ?.let { runCatching { AppSettings.fromJson(it) }.getOrNull() }
}
