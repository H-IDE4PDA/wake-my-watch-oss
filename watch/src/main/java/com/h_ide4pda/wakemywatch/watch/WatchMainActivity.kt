package com.h_ide4pda.wakemywatch.watch

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import android.os.Bundle
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.*
import com.h_ide4pda.wakemywatch.core.*
import com.h_ide4pda.wakemywatch.watch.dnd.WatchDndPermissionState
import com.h_ide4pda.wakemywatch.watch.ui.WatchColors
import com.h_ide4pda.wakemywatch.watch.ui.WatchLookAtPhoneScreen
import com.h_ide4pda.wakemywatch.watch.dnd.WatchDndSyncNotificationService
import com.h_ide4pda.wakemywatch.watch.sensors.OffBodyStateMonitor
import java.text.DateFormat
import kotlinx.coroutines.launch
import java.util.Date

private val Background = WatchColors.Background
private val Card = WatchColors.Card
private val Purple = WatchColors.Purple
private val Blue = WatchColors.Blue
private val Green = WatchColors.Green
private val Muted = WatchColors.Muted

private enum class WatchPage { MAIN, SOUND, DEVICE, DND_SETUP, PAUSE_SETTINGS, LOOK_AT_PHONE }

class WatchMainActivity : ComponentActivity() {
    private val resumeVersion = mutableLongStateOf(0L)

    private val requestPostNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        EventHistoryStore.add(
            this,
            "PERMISSIONS",
            if (granted) "POST_NOTIFICATIONS_GRANTED" else "POST_NOTIFICATIONS_DENIED",
            "alarm_bridge_prompt",
        )
    }

    companion object {
        const val EXTRA_OPEN_DND_SETUP = "com.h_ide4pda.wakemywatch.OPEN_DND_SETUP"
        private const val KEY_POST_NOTIFICATIONS_ASKED = "post_notifications_asked_for_alarm_bridge"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EventHistoryStore.add(
            this,
            "APP",
            "START",
            "version=${BuildConfig.VERSION_NAME} device=${Build.MANUFACTURER} ${Build.MODEL} wear=${Build.VERSION.RELEASE} api=${Build.VERSION.SDK_INT}",
        )
        EventHistoryStore.add(this, "SETTINGS_SNAPSHOT", "app_start", SettingsAudit.snapshot(AppSettingsStore.load(this)))
        maybeRequestPostNotificationsForAlarmBridge()
        setContent { WatchApp(resumeVersion.longValue) }
    }

    /**
     * One-time request, not a general onboarding prompt: only if Alarm Bridge is actually on
     * (synced from the phone via AppSettings) and only ever asked once, regardless of outcome,
     * so a denial doesn't keep re-prompting on every app open.
     */
    private fun maybeRequestPostNotificationsForAlarmBridge() {
        if (!AppSettingsStore.load(this).alarmBridge) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getPreferences(Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_POST_NOTIFICATIONS_ASKED, false)) return
        prefs.edit().putBoolean(KEY_POST_NOTIFICATIONS_ASKED, true).apply()
        requestPostNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onResume() {
        super.onResume()
        resumeVersion.longValue++
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        resumeVersion.longValue++
    }
}

@Composable
private fun WatchApp(resumeVersion: Long) {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var page by remember { mutableStateOf(WatchPage.MAIN) }
    var settings by remember { mutableStateOf(AppSettingsStore.load(context)) }
    var remote by remember { mutableStateOf(DeviceStore.remote(context)) }
    var ack by remember { mutableStateOf(DeviceStore.lastAck(context)) }
    var connection by remember { mutableStateOf(ConnectionDiagnosticsStore.snapshot(context)) }
    var dndPermission by remember { mutableStateOf(WatchDndPermissionState.snapshot(context)) }

    BackHandler(enabled = page != WatchPage.MAIN) {
        page = WatchPage.MAIN
    }

    fun reloadFromStores() {
        val loadedSettings = AppSettingsStore.load(context)
        settings = loadedSettings
        remote = DeviceStore.remote(context)
        ack = DeviceStore.lastAck(context)
        connection = ConnectionDiagnosticsStore.snapshot(context)
        dndPermission = WatchDndPermissionState.snapshot(context)
    }

    fun launchSettingsCandidates(reason: String, candidates: List<Intent>) {
        for (candidate in candidates) {
            val action = candidate.action.orEmpty()
            val launched = runCatching {
                context.startActivity(candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (launched) {
                EventHistoryStore.add(context, "DND_SETUP", "OPEN_SETTINGS", "$reason action=$action")
                return
            }
            EventHistoryStore.add(context, "DND_SETUP", "OPEN_SETTINGS_FAILED", "$reason action=$action")
        }
    }

    fun openAppDetailsIntent(): Intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.parse("package:${context.packageName}"))

    fun openNotificationListenerSettings() {
        val component = ComponentName(context, WatchDndSyncNotificationService::class.java)
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                        .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString()),
                )
            }
            add(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            add(openAppDetailsIntent())
            add(Intent(Settings.ACTION_SETTINGS))
        }
        launchSettingsCandidates("notification_listener", candidates)
    }

    fun openDndPolicySettings() {
        val candidates = buildList {
            add(
                Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .putExtra(Intent.EXTRA_PACKAGE_NAME, context.packageName),
            )
            add(openAppDetailsIntent())
            add(Intent(Settings.ACTION_SETTINGS))
        }
        launchSettingsCandidates("dnd_policy", candidates)
    }

    fun openAdbGuideOnPhone() {
        val envelope = MessageEnvelope(
            type = "DND_ADB_GUIDE",
            payload = org.json.JSONObject().put("reason", "watch_button"),
        )
        WearTransport.sendPreferred(context, Protocol.DND_ADB_GUIDE, envelope) { result ->
            EventHistoryStore.add(
                context,
                "DND_SETUP",
                if (result.success) "ADB_GUIDE_REQUEST_SENT" else "ADB_GUIDE_REQUEST_FAILED",
                result.detail,
            )
        }
        page = WatchPage.LOOK_AT_PHONE
    }

    fun update(value: AppSettings) {
        val previous = settings
        val revised = value.nextRevision()
        settings = revised
        AppSettingsStore.save(context, revised)
        OffBodyStateMonitor.updateRegistration(context, revised)
        SettingsAudit.recordChange(context, "watch_ui", previous, revised)
        SettingsSync.send(context, revised) { result ->
            EventHistoryStore.add(context, "SETTINGS_SYNC", if (result.success) "SENT" else "FAILED", result.detail)
        }
    }

    DisposableEffect(context) {
        val stores = listOf(
            context.getSharedPreferences(AppSettingsStore.PREFS_NAME, Context.MODE_PRIVATE),
            context.getSharedPreferences(DeviceStore.PREFS_NAME, Context.MODE_PRIVATE),
            context.getSharedPreferences(ConnectionDiagnosticsStore.PREFS_NAME, Context.MODE_PRIVATE),
            context.getSharedPreferences(WatchDndPermissionState.PREFS_NAME, Context.MODE_PRIVATE),
        )
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            mainHandler.post { reloadFromStores() }
        }
        stores.forEach { it.registerOnSharedPreferenceChangeListener(listener) }
        onDispose { stores.forEach { it.unregisterOnSharedPreferenceChangeListener(listener) } }
    }

    LaunchedEffect(resumeVersion) {
        reloadFromStores()
        OffBodyStateMonitor.updateRegistration(context, settings)
        Handshake.hello(context, DeviceRole.WATCH)
        if (WatchDndPermissionState.consumeSetupRequest(context) || (context as? ComponentActivity)?.intent?.getBooleanExtra(WatchMainActivity.EXTRA_OPEN_DND_SETUP, false) == true) {
            page = WatchPage.DND_SETUP
            WatchDndPermissionState.sendStatusToPhone(context, "watch_ui_opened")
        }
        WearTransport.refreshConnectionState(context) { refreshed ->
            mainHandler.post { connection = refreshed }
        }
    }

    MaterialTheme(
        colors = Colors(
            primary = Purple,
            secondary = Blue,
            background = Background,
            surface = Card,
            onPrimary = Color.White,
            onSecondary = Color.Black,
            onBackground = Color.White,
            onSurface = Color.White,
        ),
    ) {
        when (page) {
            WatchPage.MAIN -> WatchMainScreen(
                settings = settings,
                remote = remote,
                connected = connection.isConnected(remote?.nodeId),
                dndPermission = dndPermission,
                onSettings = ::update,
                onSound = { page = WatchPage.SOUND },
                onDndSetup = { page = WatchPage.DND_SETUP },
                onPauseSettings = { page = WatchPage.PAUSE_SETTINGS },
                onDevice = { page = WatchPage.DEVICE },
            )
            WatchPage.SOUND -> WatchChoiceScreen(
                title = stringResource(R.string.sound_mode),
                options = listOf(
                    SoundMode.NONE to stringResource(R.string.sound_none),
                    SoundMode.SYSTEM to stringResource(R.string.sound_system),
                ),
                selected = settings.soundMode,
                onSelect = { update(settings.copy(soundMode = it)); page = WatchPage.MAIN },
                onBack = { page = WatchPage.MAIN },
                descriptions = mapOf(SoundMode.SYSTEM to stringResource(R.string.sound_system_correction_description)),
            )
            WatchPage.PAUSE_SETTINGS -> WatchChoiceScreen(
                title = stringResource(R.string.pause_settings_title),
                options = listOf(
                    false to stringResource(R.string.pause_silence_everything),
                    true to stringResource(R.string.pause_keeps_alarm_and_dnd),
                ),
                selected = settings.pauseKeepsAlarmAndDnd,
                onSelect = { update(settings.copy(pauseKeepsAlarmAndDnd = it)); page = WatchPage.MAIN },
                onBack = { page = WatchPage.MAIN },
            )
            WatchPage.DEVICE -> WatchDeviceScreen(
                remote = remote,
                ack = ack,
                connection = connection,
                onBack = { page = WatchPage.MAIN },
                onResync = {
                    Handshake.hello(context, DeviceRole.WATCH)
                    SettingsSync.send(context, settings)
                },
            )
            WatchPage.DND_SETUP -> WatchDndSetupScreen(
                snapshot = dndPermission,
                onBack = {
                    WatchDndPermissionState.sendStatusToPhone(context, "watch_ui_closed")
                    page = WatchPage.MAIN
                },
                onRefresh = {
                    dndPermission = WatchDndPermissionState.snapshot(context)
                    WatchDndPermissionState.sendStatusToPhone(context, "manual_refresh")
                },
                onOpenPhoneGuide = { openAdbGuideOnPhone() },
            )
            WatchPage.LOOK_AT_PHONE -> WatchLookAtPhoneScreen(
                onOk = { page = WatchPage.DND_SETUP },
            )
        }
    }
}

@Composable
private fun WatchMainScreen(
    settings: AppSettings,
    remote: DeviceDescriptor?,
    connected: Boolean,
    dndPermission: WatchDndPermissionState.Snapshot,
    onSettings: (AppSettings) -> Unit,
    onSound: () -> Unit,
    onDndSetup: () -> Unit,
    onPauseSettings: () -> Unit,
    onDevice: () -> Unit,
) {
    Scaffold(timeText = { TimeText() }) {
        RotaryScrollColumn(
            contentPadding = PaddingValues(start = 10.dp, top = 42.dp, end = 10.dp, bottom = 22.dp),
        ) {
            AppTitle()
            val haptic = LocalHapticFeedback.current
            ToggleChip(
                    modifier = Modifier.fillMaxWidth(),
                    checked = settings.appPaused,
                    onCheckedChange = { enabled ->
                        haptic.performHapticFeedback(
                            if (enabled) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff,
                        )
                        onSettings(settings.copy(appPaused = enabled))
                    },
                    colors = wmwToggleColors(),
                    label = { Text(stringResource(R.string.app_paused_toggle)) },
                    secondaryLabel = {
                        Text(
                            if (settings.appPaused) stringResource(R.string.app_paused_status_on) else stringResource(R.string.app_paused_status_off),
                            color = if (settings.appPaused) Color(0xFFFFB74D) else Muted,
                        )
                    },
                    toggleControl = {
                        Icon(ToggleChipDefaults.switchIcon(checked = settings.appPaused), contentDescription = null)
                    },
                )
            if (settings.appPaused) {
                WmwChip(
                    stringResource(R.string.pause_settings_title),
                    if (settings.pauseKeepsAlarmAndDnd) stringResource(R.string.pause_keeps_alarm_and_dnd) else stringResource(R.string.pause_silence_everything),
                    onPauseSettings,
                )
            }
            if (!settings.appPaused) {
                ToggleChip(
                        modifier = Modifier.fillMaxWidth(),
                        checked = settings.screenWake,
                        onCheckedChange = { enabled ->
                            haptic.performHapticFeedback(
                                if (enabled) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff,
                            )
                            onSettings(settings.copy(screenWake = enabled))
                        },
                        colors = wmwToggleColors(),
                        label = { Text(stringResource(R.string.screen_wake)) },
                        secondaryLabel = {
                            Text(
                                if (settings.screenWake) stringResource(R.string.enabled) else stringResource(R.string.disabled),
                                color = if (settings.screenWake) Blue else Muted,
                            )
                        },
                        toggleControl = {
                            Icon(ToggleChipDefaults.switchIcon(checked = settings.screenWake), contentDescription = null)
                        },
                    )
                WmwChip(stringResource(R.string.sound_mode), watchSoundLabel(settings), onSound)
            }
            if (settings.dndSyncEnabled) {
                WmwChip(
                    stringResource(R.string.dnd_sync_setup_title),
                    if (dndPermission.ready) stringResource(R.string.dnd_sync_ready) else stringResource(R.string.dnd_sync_setup_needed),
                    onDndSetup,
                )
            }
            Chip(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        onDevice()
                    },
                    colors = ChipDefaults.chipColors(backgroundColor = Card),
                    label = { Text(remote?.displayName ?: stringResource(R.string.searching_phone), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    secondaryLabel = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(7.dp).background(if (connected) Green else Muted, RoundedCornerShape(7.dp)))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (connected) stringResource(R.string.connected) else stringResource(R.string.disconnected),
                                color = if (connected) Green else Muted,
                            )
                        }
                    },
                )
        }
    }
}

@Composable
private fun <T> WatchChoiceScreen(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    onBack: () -> Unit,
    descriptions: Map<T, String> = emptyMap(),
) {
    Scaffold {
        RotaryScrollColumn(
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 24.dp),
        ) {
            val haptic = LocalHapticFeedback.current
            Chip(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        onBack()
                    },
                    colors = ChipDefaults.chipColors(backgroundColor = Color.Transparent),
                    label = { Text("‹ $title", fontWeight = FontWeight.Bold) },
                )
            options.forEach { (value, label) ->
                Chip(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                            onSelect(value)
                        },
                        colors = ChipDefaults.chipColors(backgroundColor = Card),
                        label = { Text(label, maxLines = 2) },
                        icon = {
                            if (value == selected) {
                                Text("✓", color = Blue, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            }
                        },
                    )
                descriptions[value]?.let { description ->
                    Text(
                        description,
                        color = Muted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun WatchDndSetupScreen(
    snapshot: WatchDndPermissionState.Snapshot,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenPhoneGuide: () -> Unit,
) {
    Scaffold(timeText = { TimeText() }) {
        RotaryScrollColumn(
            contentPadding = PaddingValues(start = 10.dp, top = 32.dp, end = 10.dp, bottom = 22.dp),
        ) {
            val haptic = LocalHapticFeedback.current
            Chip(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    onBack()
                },
                colors = ChipDefaults.chipColors(backgroundColor = Color.Transparent),
                label = { Text("‹ ${stringResource(R.string.dnd_sync_setup_title)}", fontWeight = FontWeight.Bold) },
            )
            Text(
                stringResource(R.string.dnd_sync_setup_summary),
                color = Muted,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            )
            WatchPermissionStatusChip(
                title = stringResource(R.string.dnd_sync_watch_notification_access),
                granted = snapshot.notificationListenerGranted,
            )
            WatchPermissionStatusChip(
                title = stringResource(R.string.dnd_sync_watch_dnd_access),
                granted = snapshot.dndPolicyAccessGranted,
            )
            WmwChip(
                stringResource(R.string.dnd_sync_open_adb_guide_phone),
                stringResource(R.string.dnd_sync_open_adb_guide_phone_summary),
                onOpenPhoneGuide,
            )
            WmwChip(
                stringResource(R.string.dnd_sync_refresh_status),
                if (snapshot.ready) stringResource(R.string.dnd_sync_ready) else stringResource(R.string.dnd_sync_setup_needed),
                onRefresh,
            )
        }
    }
}

@Composable
private fun WatchPermissionStatusChip(title: String, granted: Boolean) {
    Chip(
        modifier = Modifier.fillMaxWidth(),
        onClick = {},
        enabled = false,
        colors = ChipDefaults.chipColors(backgroundColor = Card),
        label = { Text(title, maxLines = 2) },
        secondaryLabel = {
            Text(
                if (granted) stringResource(R.string.granted) else stringResource(R.string.not_granted),
                color = if (granted) Green else Muted,
            )
        },
        icon = { Text(if (granted) "✓" else "!", color = if (granted) Green else Blue, fontWeight = FontWeight.Bold) },
    )
}

@Composable
private fun WatchDeviceScreen(remote: DeviceDescriptor?, ack: Pair<Long, String>, connection: ConnectionSnapshot, onBack: () -> Unit, onResync: () -> Unit) {
    Scaffold {
        RotaryScrollColumn(
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 24.dp),
        ) {
            val haptic = LocalHapticFeedback.current
            Chip(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        onBack()
                    },
                    colors = ChipDefaults.chipColors(backgroundColor = Color.Transparent),
                    label = { Text("‹ ${stringResource(R.string.device_info)}", fontWeight = FontWeight.Bold) },
                )
            Chip(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        onResync()
                    },
                    colors = ChipDefaults.chipColors(backgroundColor = Card),
                    label = { Text(remote?.displayName ?: stringResource(R.string.searching_phone), maxLines = 1) },
                    secondaryLabel = {
                        Text(
                            remote?.let { "${if (connection.isConnected(it.nodeId)) stringResource(R.string.connected) else stringResource(R.string.disconnected)} · Android ${it.osRelease} · ${it.appVersion}" } ?: stringResource(R.string.tap_to_resync),
                            color = Muted,
                            maxLines = 2,
                        )
                    },
                )
            WmwChip(
                    stringResource(R.string.last_ack),
                    if (connection.lastAckAt > 0L) connection.lastAckResult.ifBlank { stringResource(R.string.received) } else if (ack.first > 0L) ack.second.ifBlank { stringResource(R.string.received) } else stringResource(R.string.not_available),
                    onResync,
                )
            WmwChip(
                    stringResource(R.string.last_handshake),
                    if (connection.lastHandshakeAt > 0L) {
                        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(connection.lastHandshakeAt))
                    } else {
                        stringResource(R.string.not_available)
                    },
                    onResync,
                )
        }
    }
}

@Composable
private fun AppTitle() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = buildAnnotatedString {
                append("Wake ")
                withStyle(SpanStyle(color = Purple)) { append("My") }
                append(" Watch")
            },
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
private fun WmwChip(label: String, secondary: String, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Chip(
        modifier = Modifier.fillMaxWidth(),
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            onClick()
        },
        colors = ChipDefaults.chipColors(backgroundColor = Card),
        label = { Text(label) },
        secondaryLabel = { Text(secondary, color = Muted, maxLines = 2) },
    )
}

@Composable
private fun RotaryScrollColumn(
    contentPadding: PaddingValues,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val haptic = LocalHapticFeedback.current
    var lastHapticAt by remember { mutableLongStateOf(0L) }

    LaunchedEffect(focusRequester) {
        focusRequester.requestFocus()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .onRotaryScrollEvent { event ->
                coroutineScope.launch {
                    scrollState.scrollBy(event.verticalScrollPixels)
                }
                val now = SystemClock.uptimeMillis()
                if (now - lastHapticAt >= 45L) {
                    haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                    lastHapticAt = now
                }
                true
            }
            .focusRequester(focusRequester)
            .focusable()
            .verticalScroll(scrollState)
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(7.dp),
        content = content,
    )
}

@Composable
private fun watchSoundLabel(settings: AppSettings): String = when (settings.soundMode) {
    SoundMode.NONE -> stringResource(R.string.sound_none_short)
    SoundMode.SYSTEM -> stringResource(R.string.sound_system_short)
}

@Composable
private fun wmwToggleColors() = ToggleChipDefaults.toggleChipColors(
    checkedStartBackgroundColor = Card,
    checkedEndBackgroundColor = Card,
    uncheckedStartBackgroundColor = Card,
    uncheckedEndBackgroundColor = Card,
    checkedToggleControlColor = Purple,
    uncheckedToggleControlColor = Muted,
)
