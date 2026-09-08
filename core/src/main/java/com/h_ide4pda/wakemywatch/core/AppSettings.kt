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
    // Silent (IMPORTANCE_LOW) notifications arrive collapsed to the watch face — lighting the
    // panel for one shows nothing useful and costs battery. Off by default.
    val silentWakeScreen: Boolean = false,
    // A silent notification's own bridge delivery is silent by definition — no watch-side buzz,
    // no watch-face dot — which on a wrist is indistinguishable from never arriving. On by
    // default: this is the reaction that actually makes silent notifications noticeable.
    val silentVibrate: Boolean = true,
    val respectWatchDnd: Boolean = true,
    val skipWakeOffWrist: Boolean = true,
    val skipSoundOffWrist: Boolean = true,
    // Off: off_wrist is decided by the sensor alone (previous behavior). On: the sensor must also
    // agree with a locked keyguard before off_wrist is counted — Wear OS locks the watch the
    // moment it leaves the wrist, so an unlocked watch is being worn no matter what a stale or
    // freshly re-registered sensor reading claims. Defaults off: needs on-device confirmation
    // that our hardware benefits before it changes behavior for everyone.
    val offWristRequiresLock: Boolean = false,
    val soundMode: SoundMode = SoundMode.SYSTEM,
    // Shows a notification on the watch ourselves, but only for the ones the stock sync provably
    // drops: a lone group summary with no children (e.g. Reddit chat DMs). Everything else the
    // watch already receives on its own, so mirroring it would just double up. New feature that
    // changes what appears on the watch — off by default until it has been tried on-device.
    val mirrorUndelivered: Boolean = false,
    // Mirrors the phone's sound profile (silent / vibrate / normal) onto the watch, one way.
    // NOT Do Not Disturb: notifications still arrive, only their sound follows the phone. Off by
    // default — the watch's own "match phone sound settings" is expected to do this and only
    // needs our help where it silently doesn't (OnePlus Watch 4).
    val ringerSyncEnabled: Boolean = false,
    // Reverse leg of the ringer sync: watch -> phone. Off by default, so out of the box the
    // sync is one way (phone -> watch) exactly as it was before this leg existed. Only has an
    // effect while [ringerSyncEnabled] is on. Even when on, "silent" coming FROM the watch is
    // never forwarded to the phone: the watch has no user-facing "silent" profile (its settings
    // only offer sound and vibrate), so a SILENT there comes from system automation (sleep/
    // bedtime mode) rather than a user action, and forwarding it would drop the phone into Do
    // Not Disturb on its own at night. Phone -> watch still carries "silent" as before.
    val ringerReverseSyncEnabled: Boolean = false,
    // Time-of-day schedule that flips the PHONE between "sound" (NORMAL) and "vibrate" only —
    // the watch follows through the ordinary ringer sync. Never touches "silent"/Do Not Disturb.
    // Minutes since local midnight, 0..1439. Between start and end the phone is held on VIBRATE;
    // outside it, on NORMAL. start > end means the quiet window wraps past midnight. Every day.
    val ringerSchedulePlans: List<RingerSchedulePlan> = emptyList(),
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

    /**
     * True when forwarding this notification would produce nothing at all on the watch, so there
     * is no point sending it. `screenWake` used to gate the whole relay, which made "do not light
     * the panel" mean "switch the app off" — the sound correction died with it. Each reaction now
     * stands on its own and forwarding stops only once every one of them is off.
     *
     * A silent (low-importance) notification has its own pair of toggles: its bridge delivery is
     * itself silent, so screen and vibrate are decided by [silentWakeScreen] / [silentVibrate].
     * An ordinary notification already buzzes the watch through the system bridge, so vibration is
     * not one of its reactions here — only the screen and the sound correction are.
     */
    fun forwardsNothingToWatch(isSilentLowImportance: Boolean): Boolean = if (isSilentLowImportance) {
        !silentWakeScreen && !silentVibrate
    } else {
        !screenWake && soundMode == SoundMode.NONE
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
        .put("silentWakeScreen", silentWakeScreen)
        .put("silentVibrate", silentVibrate)
        .put("respectWatchDnd", respectWatchDnd)
        .put("skipWakeOffWrist", skipWakeOffWrist)
        .put("skipSoundOffWrist", skipSoundOffWrist)
        .put("offWristRequiresLock", offWristRequiresLock)
        .put("soundMode", soundMode.wireValue)
        .put("mirrorUndelivered", mirrorUndelivered)
        .put("ringerSyncEnabled", ringerSyncEnabled)
        .put("ringerReverseSyncEnabled", ringerReverseSyncEnabled)
        .put("ringerSchedulePlans", RingerSchedulePlan.listToJson(ringerSchedulePlans))
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
        /**
         * Reads the plan list. Migrates the old single-schedule shape
         * (ringerScheduleEnabled + ringerScheduleQuietStart/EndMinutes, every day) into one plan
         * so nothing is lost for users who had already turned it on. A blob already carrying
         * "ringerSchedulePlans" is used as-is and never re-migrated.
         */
        private fun readRingerSchedulePlans(json: JSONObject): List<RingerSchedulePlan> {
            if (json.has("ringerSchedulePlans")) {
                return RingerSchedulePlan.listFromJson(json.optJSONArray("ringerSchedulePlans"))
            }
            if (!json.has("ringerScheduleEnabled")) return emptyList()
            return listOf(
                RingerSchedulePlan(
                    name = "",
                    startMinutes = json.optInt("ringerScheduleQuietStartMinutes", 8 * 60).coerceIn(0, 1439),
                    endMinutes = json.optInt("ringerScheduleQuietEndMinutes", 17 * 60).coerceIn(0, 1439),
                    days = RingerSchedulePlan.ALL_DAYS,
                    enabled = json.optBoolean("ringerScheduleEnabled", false),
                ),
            )
        }

        fun fromJson(json: JSONObject): AppSettings {
            val packages = buildSet {
                val array = json.optJSONArray("appPackages") ?: JSONArray()
                for (index in 0 until array.length()) {
                    array.optString(index).takeIf(String::isNotBlank)?.let(::add)
                }
            }
            // Migrates the single pre-checkpoint skipSilentNotifications gate into the pair below.
            // It used to block the whole relay for silent notifications; screen and vibrate now
            // toggle independently, so a saved settings blob from before this split is read once
            // and mapped to the pair that reproduces its old effective behavior exactly: skip=on
            // (silent notifications ignored) becomes both off, skip=off (silent notifications went
            // through in full) becomes both on. A blob already carrying either new key is never
            // touched again — it can only be either version's shape, never a mix of the two.
            val hasSplitSilentSettings = json.has("silentWakeScreen") || json.has("silentVibrate")
            val silentWakeScreen: Boolean
            val silentVibrate: Boolean
            if (hasSplitSilentSettings) {
                silentWakeScreen = json.optBoolean("silentWakeScreen", false)
                silentVibrate = json.optBoolean("silentVibrate", true)
            } else {
                val legacySilentNotificationsSkipped = json.optBoolean("skipSilentNotifications", true)
                silentWakeScreen = !legacySilentNotificationsSkipped
                silentVibrate = !legacySilentNotificationsSkipped
            }
            return AppSettings(
                revision = json.optLong("revision", 0L),
                updatedAt = json.optLong("updatedAt", 0L),
                screenWake = json.optBoolean("screenWake", true),
                respectPhoneDnd = json.optBoolean("respectPhoneDnd", true),
                wakeScreenOnPhoneDnd = json.optBoolean("wakeScreenOnPhoneDnd", true),
                skipWhenPhoneUnlocked = json.optBoolean("skipWhenPhoneUnlocked", true),
                silentWakeScreen = silentWakeScreen,
                silentVibrate = silentVibrate,
                respectWatchDnd = json.optBoolean("respectWatchDnd", true),
                skipWakeOffWrist = json.optBoolean("skipWakeOffWrist", true),
                skipSoundOffWrist = json.optBoolean("skipSoundOffWrist", true),
                offWristRequiresLock = json.optBoolean("offWristRequiresLock", false),
                soundMode = SoundMode.fromWire(json.optInt("soundMode", SoundMode.SYSTEM.wireValue)),
                mirrorUndelivered = json.optBoolean("mirrorUndelivered", false),
                ringerSyncEnabled = json.optBoolean("ringerSyncEnabled", false),
                ringerReverseSyncEnabled = json.optBoolean("ringerReverseSyncEnabled", false),
                ringerSchedulePlans = readRingerSchedulePlans(json),
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
