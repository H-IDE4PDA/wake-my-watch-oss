package com.h_ide4pda.wakemywatch.phone

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.provider.Settings
import android.text.format.Formatter
import android.widget.Toast
import android.service.notification.NotificationListenerService
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import com.h_ide4pda.wakemywatch.core.*
import com.h_ide4pda.wakemywatch.phone.adb.DndAdbGuideContent
import com.h_ide4pda.wakemywatch.phone.dnd.PhoneDndSyncBridge
import com.h_ide4pda.wakemywatch.phone.notifications.NotificationRelayService
import com.h_ide4pda.wakemywatch.core.RingerSchedulePlan
import com.h_ide4pda.wakemywatch.phone.ringer.RingerScheduleController
import com.h_ide4pda.wakemywatch.phone.ringer.RingerSyncBridge
import com.h_ide4pda.wakemywatch.phone.permissions.PermissionProbe
import com.h_ide4pda.wakemywatch.phone.settings.PhoneSettings
import com.h_ide4pda.wakemywatch.phone.settings.PhoneSettingsStore
import com.h_ide4pda.wakemywatch.phone.ui.*
import com.h_ide4pda.wakemywatch.phone.wear.DndSyncStatus
import com.h_ide4pda.wakemywatch.phone.wear.PhoneDiagnosticsTransfer
import com.h_ide4pda.wakemywatch.phone.wear.PhoneDndSyncStatusTransfer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// TODO: вставить реальные ссылки/адрес
private const val KOFI_URL = "https://Ko-fi.com/h_ide4pda"
private const val MONOBANK_URL = "https://send.monobank.ua/jar/4W85VhKWit"
private const val USDT_TRC20_ADDRESS = "TSB1MTtXw9DXxTt9oEkyKUbQkNTqLc2RLb"

class PhoneMainActivity : ComponentActivity() {
    private var resumeCounter by mutableIntStateOf(0)

    companion object {
        const val EXTRA_OPEN_DND_ADB_GUIDE = "com.h_ide4pda.wakemywatch.OPEN_DND_ADB_GUIDE"
        private const val PREFS = "wmw_phone_activity"
        private const val KEY_OPEN_DND_ADB_GUIDE = "open_dnd_adb_guide"

        fun requestOpenDndAdbGuide(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_OPEN_DND_ADB_GUIDE, true)
                .apply()
        }

        fun consumeOpenDndAdbGuide(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_OPEN_DND_ADB_GUIDE, false)) return false
            prefs.edit().putBoolean(KEY_OPEN_DND_ADB_GUIDE, false).apply()
            return true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.getBooleanExtra(EXTRA_OPEN_DND_ADB_GUIDE, false) == true) {
            requestOpenDndAdbGuide(this)
        }
        setContent {
            WakeMyWatchTheme {
                PhoneApp(refreshToken = resumeCounter)
            }
        }
        EventHistoryStore.add(
            this,
            "APP",
            "START",
            "version=${BuildConfig.VERSION_NAME} device=${Build.MANUFACTURER} ${Build.MODEL} android=${Build.VERSION.RELEASE} api=${Build.VERSION.SDK_INT}",
        )
        EventHistoryStore.add(this, "SETTINGS_SNAPSHOT", "app_start", SettingsAudit.snapshot(PhoneSettingsStore.load(this)))
        Handshake.hello(this, DeviceRole.PHONE)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_DND_ADB_GUIDE, false)) {
            requestOpenDndAdbGuide(this)
        }
        resumeCounter++
    }

    override fun onResume() {
        super.onResume()
        resumeCounter++
    }
}

private enum class Page { MAIN, DEVICE, APP_FILTER, PERMISSIONS, DIAGNOSTICS, EVENT_HISTORY, RINGER_SCHEDULES, RINGER_SCHEDULE_EDIT }
private enum class SettingsDialog { SOUND, WRIST, DND, PAUSE, SILENT }
private data class InstalledApp(val label: String, val packageName: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhoneApp(refreshToken: Int) {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val appScope = rememberCoroutineScope()
    var page by remember { mutableStateOf(Page.MAIN) }
    var settings by remember { mutableStateOf(PhoneSettingsStore.load(context)) }
    var activeDialog by remember { mutableStateOf<SettingsDialog?>(null) }
    // Which ringer-schedule plan the edit screen is on; null while a brand-new plan is being added.
    var editingPlanId by remember { mutableStateOf<String?>(null) }
    var showAlarmWarning by remember { mutableStateOf(false) }
    var showLegal by remember { mutableStateOf(false) }
    var showDonate by remember { mutableStateOf(false) }
    var remote by remember { mutableStateOf(DeviceStore.remote(context)) }
    var ack by remember { mutableStateOf(DeviceStore.lastAck(context)) }
    var connection by remember { mutableStateOf(ConnectionDiagnosticsStore.snapshot(context)) }
    var permissionProbe by remember { mutableStateOf(PermissionProbe.snapshot(context)) }
    var toastText by remember { mutableStateOf<String?>(null) }
    val postNotificationsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionProbe = PermissionProbe.snapshot(context)
    }

    LaunchedEffect(settings.forceSoftwareDndSync) {
        // permissionProbe.nativeOHealthPhone already bakes in forceSoftwareDndSync at snapshot
        // time — re-snapshot whenever the setting flips so the Permissions screen (and self-heal,
        // pause-mode transitions) see the current gate immediately, not the value from app start.
        permissionProbe = PermissionProbe.snapshot(context)
    }

    BackHandler(enabled = page != Page.MAIN) {
        page = when (page) {
            Page.EVENT_HISTORY -> Page.DIAGNOSTICS
            Page.RINGER_SCHEDULE_EDIT -> Page.RINGER_SCHEDULES
            else -> Page.MAIN
        }
    }

    LaunchedEffect(Unit) {
        // Re-arm the ringer schedule on every app open: a force-stop cancels its alarms and the
        // boot receiver never runs after that until the app is opened again.
        RingerScheduleController.sync(context, "app_open")
    }

    LaunchedEffect(Unit) {
        // Self-heal a stuck dndSyncEnabled=true on OnePlus/Oppo/Realme (OHealth already syncs DND
        // natively there) — no matter how it got stuck, it must not stay on for these phones.
        if (permissionProbe.nativeOHealthPhone && settings.dndSyncEnabled) {
            val healed = settings.copy(dndSyncEnabled = false, savedDndSyncEnabledBeforePause = null).nextRevision()
            settings = healed
            PhoneSettingsStore.save(context, healed)
            SettingsSync.send(context, healed) { result ->
                EventHistoryStore.add(context, "SETTINGS_SYNC", if (result.success) "SENT" else "FAILED", result.detail)
            }
        }
    }

    LaunchedEffect(settings.appPaused) {
        // A dialog for a hidden functional setting (e.g. Sound Mode) shouldn't linger open
        // once its trigger row disappears from the collapsed pause view.
        if (settings.appPaused) activeDialog = null
    }

    fun update(value: PhoneSettings) {
        val previous = settings
        val resolved = PauseModeTransition.resolve(previous, value, permissionProbe.nativeOHealthPhone)
        val revised = resolved.nextRevision()
        settings = revised
        PhoneSettingsStore.save(context, revised)
        SettingsAudit.recordChange(context, "phone_ui", previous, revised)
        SettingsSync.send(context, PhoneDndSyncBridge.maskForNativeOHealth(context, revised)) { result ->
            EventHistoryStore.add(context, "SETTINGS_SYNC", if (result.success) "SENT" else "FAILED", result.detail)
        }
        if (previous.ringerSchedulePlans != revised.ringerSchedulePlans ||
            previous.isFullyPaused != revised.isFullyPaused
        ) {
            RingerScheduleController.sync(context, "settings_change")
        }
    }

    fun refreshPermissionProbe() {
        permissionProbe = PermissionProbe.snapshot(context)
        EventHistoryStore.add(context, "PERMISSIONS", "REFRESHED", PermissionProbe.report(context, permissionProbe))
    }

    fun openSettingsIntent(intent: Intent) {
        val launched = runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (!launched) toastText = context.getString(R.string.permission_open_settings_failed)
    }

    fun requestPostNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            postNotificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            toastText = context.getString(R.string.permission_not_required_on_this_android)
        }
    }

    fun openAppNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        openSettingsIntent(intent)
    }

    fun openNotificationListenerSettings() {
        openSettingsIntent(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    fun openDndPolicySettings() {
        openSettingsIntent(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
    }

    fun requestWatchDndSetup() {
        val envelope = MessageEnvelope(
            type = "DND_SETUP",
            payload = JSONObject().put("reason", "phone_ui"),
        )
        WearTransport.sendPreferred(context, Protocol.DND_SETUP, envelope) { result ->
            EventHistoryStore.add(
                context,
                "DND_SETUP",
                if (result.success) "REQUEST_SENT_TO_WATCH" else "REQUEST_SEND_FAILED",
                result.detail,
            )
            mainHandler.post {
                toastText = if (result.success) {
                    context.getString(R.string.dnd_sync_watch_setup_sent)
                } else {
                    context.getString(R.string.dnd_sync_watch_setup_failed, result.detail)
                }
            }
        }
    }

    fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val requestIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:${context.packageName}"))
            val launched = runCatching {
                context.startActivity(requestIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (!launched) openSettingsIntent(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } else {
            toastText = context.getString(R.string.permission_not_required_on_this_android)
        }
    }

    fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                .setData(Uri.parse("package:${context.packageName}"))
            openSettingsIntent(intent)
        } else {
            toastText = context.getString(R.string.permission_not_required_on_this_android)
        }
    }

    fun openAppDetailsSettings() {
        openSettingsIntent(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}")),
        )
    }


    fun reloadFromStores() {
        settings = PhoneSettingsStore.load(context)
        remote = DeviceStore.remote(context)
        ack = DeviceStore.lastAck(context)
        connection = ConnectionDiagnosticsStore.snapshot(context)
        permissionProbe = PermissionProbe.snapshot(context)
    }

    DisposableEffect(context) {
        val stores = listOf(
            context.getSharedPreferences(AppSettingsStore.PREFS_NAME, Context.MODE_PRIVATE),
            context.getSharedPreferences(DeviceStore.PREFS_NAME, Context.MODE_PRIVATE),
            context.getSharedPreferences(ConnectionDiagnosticsStore.PREFS_NAME, Context.MODE_PRIVATE),
        )
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            mainHandler.post { reloadFromStores() }
        }
        stores.forEach { it.registerOnSharedPreferenceChangeListener(listener) }
        onDispose { stores.forEach { it.unregisterOnSharedPreferenceChangeListener(listener) } }
    }

    LaunchedEffect(refreshToken) {
        if (PhoneMainActivity.consumeOpenDndAdbGuide(context)) {
            page = Page.PERMISSIONS
        }
        reloadFromStores()
        // A legacy stored soundMode int for the removed SYSTEM_AND_CUSTOM value (wire 2) no
        // longer matches any SoundMode entry, so SoundMode.fromWire() already falls back to
        // SYSTEM on load — no separate migration needed here.
        WearTransport.refreshConnectionState(context) { refreshed ->
            mainHandler.post { connection = refreshed }
        }
    }

    Scaffold(containerColor = WmwBackground) { padding ->
        when (page) {
            Page.MAIN -> MainScreen(
                modifier = Modifier.padding(padding),
                settings = settings,
                remote = remote,
                ack = ack,
                connection = connection,
                offBodySupported = remote?.features?.contains(DeviceFeatures.OFF_BODY) ?: true,
                nativeOHealthPhone = permissionProbe.nativeOHealthPhone,
                onSettings = ::update,
                onPauseSettings = { activeDialog = SettingsDialog.PAUSE },
                onSound = { activeDialog = SettingsDialog.SOUND },
                onRingerSyncToggle = { enabled ->
                    if (enabled) {
                        // Flip the setting first (mirrors DND's enable path), then push the
                        // phone's current profile to the watch off the main thread.
                        update(settings.copy(ringerSyncEnabled = true))
                        appScope.launch {
                            withContext(Dispatchers.IO) {
                                RingerSyncBridge.applyCurrentRingerOnEnableBlocking(context)
                            }
                        }
                    } else {
                        // Turning off never touches the watch's profile — unlike DND, the user
                        // may want to leave the watch where it is and set it themselves.
                        update(settings.copy(ringerSyncEnabled = false))
                    }
                },
                onRingerSchedule = { page = Page.RINGER_SCHEDULES },
                onWrist = { activeDialog = SettingsDialog.WRIST },
                onSilent = { activeDialog = SettingsDialog.SILENT },
                onDnd = { activeDialog = SettingsDialog.DND },
                onAppFilter = { page = Page.APP_FILTER },
                onAlarmBridge = {
                    if (settings.alarmBridge) update(settings.copy(alarmBridge = false)) else showAlarmWarning = true
                },
                onDevice = { page = Page.DEVICE },
                onPermissions = { page = Page.PERMISSIONS },
                onDiagnostics = { page = Page.DIAGNOSTICS },
                onDonate = { showDonate = true },
                onLegal = { showLegal = true },
                onGrant = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                onTest = {
                    val payload = JSONObject()
                        .put("test", true)
                        .put("packageName", "wake_my_watch_test")
                        .put("respectWatchDnd", settings.respectWatchDnd)
                        .put("skipWakeOffWrist", settings.skipWakeOffWrist)
                        .put("skipSoundOffWrist", settings.skipSoundOffWrist)
                        .put("soundMode", settings.soundMode.wireValue)
                        .put("wakeScreen", true)
                        .put("vibrateOnWake", settings.silentVibrate)
                    val envelope = MessageEnvelope(type = "WAKE_TEST", payload = payload)
                    WearTransport.sendPreferred(context, Protocol.WAKE, envelope) { result ->
                        toastText = if (result.success) context.getString(R.string.test_sent) else result.detail
                        EventHistoryStore.add(context, "TEST_WAKE", if (result.success) "SENT" else "FAILED", result.detail)
                    }
                },
            )
            Page.DEVICE -> DeviceInfoScreen(
                modifier = Modifier.padding(padding),
                remote = remote,
                ack = ack,
                connection = connection,
                onBack = { page = Page.MAIN },
                onResync = {
                    Handshake.hello(context, DeviceRole.PHONE) { result -> toastText = result.detail }
                    SettingsSync.send(context, PhoneDndSyncBridge.maskForNativeOHealth(context, settings))
                },
                onLegal = { showLegal = true },
            )
            Page.APP_FILTER -> AppFilterScreen(
                modifier = Modifier.padding(padding),
                settings = settings,
                onSettings = ::update,
                onBack = { page = Page.MAIN },
            )
            Page.PERMISSIONS -> PermissionsScreen(
                modifier = Modifier.padding(padding),
                snapshot = permissionProbe,
                ringerSyncEnabled = settings.ringerSyncEnabled,
                onBack = { page = Page.MAIN },
                onRefresh = ::refreshPermissionProbe,
                onNotificationListener = ::openNotificationListenerSettings,
                onPostNotifications = ::requestPostNotifications,
                onAppNotificationSettings = ::openAppNotificationSettings,
                onDndPolicy = ::openDndPolicySettings,
                onBattery = ::requestIgnoreBatteryOptimizations,
                onExactAlarm = ::openExactAlarmSettings,
                onAppDetails = ::openAppDetailsSettings,
                onShareAdbGuideHtml = { context.shareDndAdbGuideHtml() },
                onCheckWatch = ::requestWatchDndSetup,
            )
            Page.DIAGNOSTICS -> DiagnosticsScreen(
                modifier = Modifier.padding(padding),
                settings = settings,
                remote = remote,
                ack = ack,
                connection = connection,
                onBack = { page = Page.MAIN },
                onExpandHistory = { page = Page.EVENT_HISTORY },
            )
            Page.EVENT_HISTORY -> EventHistoryScreen(
                modifier = Modifier.padding(padding),
                onBack = { page = Page.DIAGNOSTICS },
                onToast = { toastText = it },
            )
            Page.RINGER_SCHEDULES -> RingerSchedulesScreen(
                modifier = Modifier.padding(padding),
                plans = settings.ringerSchedulePlans,
                onBack = { page = Page.MAIN },
                onTogglePlan = { id, on ->
                    update(settings.copy(ringerSchedulePlans = settings.ringerSchedulePlans.map {
                        if (it.id == id) it.copy(enabled = on) else it
                    }))
                },
                onEditPlan = { id -> editingPlanId = id; page = Page.RINGER_SCHEDULE_EDIT },
                onAddPlan = { editingPlanId = null; page = Page.RINGER_SCHEDULE_EDIT },
            )
            Page.RINGER_SCHEDULE_EDIT -> {
                val existing = editingPlanId?.let { id -> settings.ringerSchedulePlans.find { it.id == id } }
                RingerSchedulePlanScreen(
                    modifier = Modifier.padding(padding),
                    original = existing,
                    onBack = { page = Page.RINGER_SCHEDULES },
                    onSave = { plan ->
                        val list = settings.ringerSchedulePlans
                        val next = if (list.any { it.id == plan.id }) {
                            list.map { if (it.id == plan.id) plan else it }
                        } else {
                            list + plan
                        }
                        update(settings.copy(ringerSchedulePlans = next))
                        page = Page.RINGER_SCHEDULES
                    },
                    onDelete = existing?.let { plan ->
                        {
                            update(settings.copy(ringerSchedulePlans = settings.ringerSchedulePlans.filterNot { it.id == plan.id }))
                            page = Page.RINGER_SCHEDULES
                        }
                    },
                )
            }
        }
    }

    when (activeDialog) {
        SettingsDialog.SOUND -> SoundModeDialog(
            settings = settings,
            onSettings = ::update,
            onDismiss = { activeDialog = null },
        )
        SettingsDialog.WRIST -> WristDialog(settings, ::update) { activeDialog = null }
        SettingsDialog.SILENT -> SilentNotificationsDialog(settings, ::update) { activeDialog = null }
        SettingsDialog.PAUSE -> PauseSettingsDialog(settings, ::update) { activeDialog = null }
        SettingsDialog.DND -> DndDialog(
            settings = settings,
            onSettings = ::update,
            onOpenAdbGuide = {
                activeDialog = null
                page = Page.PERMISSIONS
            },
            onDismiss = { activeDialog = null },
        )
        null -> Unit
    }

    if (showAlarmWarning) {
        AlertDialog(
            onDismissRequest = { showAlarmWarning = false },
            icon = { Icon(ImageVector.vectorResource(id = R.drawable.ic_alarm), null) },
            title = { Text(stringResource(R.string.alarm_bridge)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.alarm_bridge_warning))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showAlarmWarning = false
                    update(settings.copy(alarmBridge = true))
                    NotificationListenerService.requestRebind(
                        ComponentName(context, NotificationRelayService::class.java),
                    )
                }) { Text(stringResource(R.string.enable)) }
            },
            dismissButton = { TextButton(onClick = { showAlarmWarning = false }) { Text(stringResource(R.string.cancel)) } },
            containerColor = WmwSurface,
        )
    }

    if (showLegal) {
        ModalBottomSheet(
            onDismissRequest = { showLegal = false },
            containerColor = WmwSurface,
            contentColor = WmwText,
        ) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
                Text(stringResource(R.string.about_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.legal_body), color = WmwMuted, lineHeight = 21.sp)
                Spacer(Modifier.height(20.dp))
                Button(onClick = { showLegal = false }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.close))
                }
            }
        }
    }

    if (showDonate) {
        val clipboard = LocalClipboardManager.current
        ModalBottomSheet(
            onDismissRequest = { showDonate = false },
            containerColor = WmwSurface,
            contentColor = WmwText,
        ) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
                Text(stringResource(R.string.donate_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(20.dp))
                ActionCard(
                    ImageVector.vectorResource(id = R.drawable.ic_local_cafe),
                    stringResource(R.string.donate_kofi),
                    stringResource(R.string.donate_kofi_summary),
                    onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(KOFI_URL))) },
                    tint = WmwGreen,
                )
                Spacer(Modifier.height(10.dp))
                ActionCard(
                    ImageVector.vectorResource(id = R.drawable.ic_account_balance),
                    stringResource(R.string.donate_monobank),
                    stringResource(R.string.donate_monobank_summary),
                    onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(MONOBANK_URL))) },
                    tint = WmwBlue,
                )
                Spacer(Modifier.height(10.dp))
                ActionCard(
                    ImageVector.vectorResource(id = R.drawable.ic_currency_exchange),
                    stringResource(R.string.donate_usdt),
                    USDT_TRC20_ADDRESS,
                    onClick = {
                        clipboard.setText(AnnotatedString(USDT_TRC20_ADDRESS))
                        toastText = context.getString(R.string.donate_address_copied)
                    },
                    tint = WmwPurple,
                )
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.donate_usdt_tap_copy), color = WmwMuted, fontSize = 12.sp)
                Spacer(Modifier.height(20.dp))
                Button(onClick = { showDonate = false }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.close))
                }
            }
        }
    }

    toastText?.let { text ->
        LaunchedEffect(text) {
            android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
            toastText = null
            remote = DeviceStore.remote(context)
            ack = DeviceStore.lastAck(context)
        }
    }
}

@Composable
private fun MainScreen(
    modifier: Modifier,
    settings: PhoneSettings,
    remote: DeviceDescriptor?,
    ack: Pair<Long, String>,
    connection: ConnectionSnapshot,
    offBodySupported: Boolean,
    nativeOHealthPhone: Boolean,
    onSettings: (PhoneSettings) -> Unit,
    onPauseSettings: () -> Unit,
    onSound: () -> Unit,
    onRingerSyncToggle: (Boolean) -> Unit,
    onRingerSchedule: () -> Unit,
    onWrist: () -> Unit,
    onSilent: () -> Unit,
    onDnd: () -> Unit,
    onAppFilter: () -> Unit,
    onAlarmBridge: () -> Unit,
    onDevice: () -> Unit,
    onPermissions: () -> Unit,
    onDiagnostics: () -> Unit,
    onDonate: () -> Unit,
    onLegal: () -> Unit,
    onGrant: () -> Unit,
    onTest: () -> Unit,
) {
    val context = LocalContext.current
    val hasAccess = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
    ) {
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Wake ", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("My", style = MaterialTheme.typography.headlineMedium, color = WmwPurple, fontWeight = FontWeight.Bold)
            Text(" Watch", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(18.dp))
        DeviceCard(remote = remote, ack = ack, connected = connection.isConnected(remote?.nodeId), onClick = onDevice)
        Spacer(Modifier.height(12.dp))
        PauseCard(
            paused = settings.appPaused,
            keepsAlarmAndDnd = settings.pauseKeepsAlarmAndDnd,
            onToggle = { onSettings(settings.copy(appPaused = it)) },
            onSettingsClick = onPauseSettings,
        )
        if (!hasAccess) {
            Spacer(Modifier.height(12.dp))
            Surface(shape = RoundedCornerShape(18.dp), color = Color(0xFF261A28), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, null, tint = Color(0xFFFFB4AB))
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.notification_access_required), Modifier.weight(1f), color = WmwText)
                    TextButton(onClick = onGrant) { Text(stringResource(R.string.grant_access)) }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        AnimatedVisibility(visible = !settings.appPaused, enter = expandVertically(), exit = shrinkVertically()) {
            SettingsPanel {
                SettingRow(
                    ImageVector.vectorResource(id = R.drawable.ic_light_mode),
                    stringResource(R.string.screen_wake),
                    stringResource(R.string.screen_wake_summary),
                    trailing = { Switch(settings.screenWake, { onSettings(settings.copy(screenWake = it)) }) },
                    onClick = { onSettings(settings.copy(screenWake = !settings.screenWake)) },
                )
                DividerLine()
                SettingRow(
                    Icons.Default.Lock,
                    stringResource(R.string.skip_when_phone_unlocked),
                    stringResource(R.string.skip_when_phone_unlocked_summary),
                    trailing = {
                        Switch(
                            checked = settings.skipWhenPhoneUnlocked,
                            onCheckedChange = { onSettings(settings.copy(skipWhenPhoneUnlocked = it)) },
                        )
                    },
                    onClick = { onSettings(settings.copy(skipWhenPhoneUnlocked = !settings.skipWhenPhoneUnlocked)) },
                )
                DividerLine()
                SettingRow(
                    Icons.Default.Notifications,
                    stringResource(R.string.silent_notifications),
                    silentNotificationsSummary(settings),
                    onClick = onSilent,
                )
                DividerLine()
                SettingRow(
                    ImageVector.vectorResource(id = R.drawable.ic_open_in_full),
                    stringResource(R.string.mirror_undelivered),
                    stringResource(R.string.mirror_undelivered_summary),
                    trailing = {
                        Switch(
                            checked = settings.mirrorUndelivered,
                            onCheckedChange = { onSettings(settings.copy(mirrorUndelivered = it)) },
                        )
                    },
                    onClick = { onSettings(settings.copy(mirrorUndelivered = !settings.mirrorUndelivered)) },
                )
                DividerLine()
                SettingRow(ImageVector.vectorResource(id = R.drawable.ic_volume_up), stringResource(R.string.sound_mode), soundModeLabel(settings), onClick = onSound)
                DividerLine()
                SettingRow(
                    ImageVector.vectorResource(id = R.drawable.ic_graphic_eq),
                    stringResource(R.string.ringer_sync_enable),
                    stringResource(R.string.ringer_sync_summary),
                    trailing = {
                        Switch(
                            checked = settings.ringerSyncEnabled,
                            onCheckedChange = onRingerSyncToggle,
                        )
                    },
                    onClick = { onRingerSyncToggle(!settings.ringerSyncEnabled) },
                )
                DividerLine()
                SettingRow(
                    ImageVector.vectorResource(id = R.drawable.ic_swap_vert),
                    stringResource(R.string.ringer_reverse_sync_enable),
                    stringResource(R.string.ringer_reverse_sync_summary),
                    enabled = settings.ringerSyncEnabled,
                    trailing = {
                        Switch(
                            checked = settings.ringerSyncEnabled && settings.ringerReverseSyncEnabled,
                            enabled = settings.ringerSyncEnabled,
                            onCheckedChange = { onSettings(settings.copy(ringerReverseSyncEnabled = it)) },
                        )
                    },
                    onClick = {
                        if (settings.ringerSyncEnabled) {
                            onSettings(settings.copy(ringerReverseSyncEnabled = !settings.ringerReverseSyncEnabled))
                        }
                    },
                )
                DividerLine()
                SettingRow(
                    Icons.Default.DateRange,
                    stringResource(R.string.ringer_schedule),
                    ringerScheduleSummary(settings),
                    onClick = onRingerSchedule,
                )
                DividerLine()
                SettingRow(
                    ImageVector.vectorResource(id = R.drawable.ic_pan_tool),
                    stringResource(R.string.wrist_detection),
                    wristSummary(settings, offBodySupported),
                    enabled = offBodySupported,
                    onClick = onWrist,
                )
                DividerLine()
                SettingRow(
                    ImageVector.vectorResource(id = R.drawable.ic_do_not_disturb_on),
                    stringResource(R.string.do_not_disturb),
                    dndSummary(settings),
                    onClick = onDnd,
                )
                DividerLine()
                SettingRow(
                    ImageVector.vectorResource(id = R.drawable.ic_filter_alt),
                    stringResource(R.string.app_filter),
                    appFilterSummary(settings),
                    onClick = onAppFilter,
                )
            }
        }
        AnimatedVisibility(
            visible = !settings.appPaused || settings.pauseKeepsAlarmAndDnd,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column {
                Spacer(Modifier.height(16.dp))
                SettingsPanel {
                    SettingRow(
                        ImageVector.vectorResource(id = R.drawable.ic_alarm),
                        stringResource(R.string.alarm_bridge),
                        if (settings.appPaused) {
                            stringResource(R.string.paused_stays_active)
                        } else if (settings.alarmBridge) {
                            stringResource(R.string.enabled)
                        } else {
                            stringResource(R.string.disabled)
                        },
                        trailing = {
                            Switch(
                                checked = if (settings.appPaused) true else settings.alarmBridge,
                                onCheckedChange = { onAlarmBridge() },
                                enabled = !settings.appPaused,
                            )
                        },
                        // Row itself stays full-color (not the dimmed "disabled" look) — only the
                        // Switch above is visually/functionally disabled while paused.
                        onClick = if (settings.appPaused) null else onAlarmBridge,
                    )
                    if (settings.appPaused && !nativeOHealthPhone) {
                        DividerLine()
                        SettingRow(
                            ImageVector.vectorResource(id = R.drawable.ic_do_not_disturb_on),
                            stringResource(R.string.dnd_sync_enable),
                            stringResource(R.string.paused_stays_active),
                            trailing = { Switch(checked = true, onCheckedChange = {}, enabled = false) },
                            onClick = null,
                        )
                    }
                    // Ringer sync is not force-enabled on entering safe pause (unlike Alarm Bridge
                    // and DND sync), so this reminder row only appears when the user already had it on.
                    if (settings.appPaused && settings.ringerSyncEnabled) {
                        DividerLine()
                        SettingRow(
                            ImageVector.vectorResource(id = R.drawable.ic_graphic_eq),
                            stringResource(R.string.ringer_sync_enable),
                            stringResource(R.string.paused_stays_active),
                            trailing = { Switch(checked = true, onCheckedChange = {}, enabled = false) },
                            onClick = null,
                        )
                    }
                }
            }
        }
        AnimatedVisibility(visible = !settings.appPaused, enter = expandVertically(), exit = shrinkVertically()) {
            Column {
                Spacer(Modifier.height(14.dp))
                ActionCard(Icons.AutoMirrored.Filled.Send, stringResource(R.string.test_wake), stringResource(R.string.test_wake_summary), onTest)
            }
        }
        Spacer(Modifier.height(10.dp))
        ActionCard(ImageVector.vectorResource(id = R.drawable.ic_admin_panel_settings), stringResource(R.string.permissions_and_background), stringResource(R.string.permissions_and_background_summary), onPermissions)
        Spacer(Modifier.height(10.dp))
        ActionCard(Icons.Default.Search, stringResource(R.string.diagnostics), stringResource(R.string.diagnostics_summary), onDiagnostics)
        Spacer(Modifier.height(10.dp))
        ActionCard(ImageVector.vectorResource(id = R.drawable.ic_coffee), stringResource(R.string.donate_title), stringResource(R.string.donate_summary), onDonate, tint = WmwGreen)
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.privacy_line), color = WmwMuted, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
        Footer(onLegal)
        Spacer(Modifier.height(20.dp))
    }
}


@Composable
private fun PermissionsScreen(
    modifier: Modifier,
    snapshot: PermissionProbe.Snapshot,
    ringerSyncEnabled: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onNotificationListener: () -> Unit,
    onPostNotifications: () -> Unit,
    onAppNotificationSettings: () -> Unit,
    onDndPolicy: () -> Unit,
    onBattery: () -> Unit,
    onExactAlarm: () -> Unit,
    onAppDetails: () -> Unit,
    onShareAdbGuideHtml: () -> Unit,
    onCheckWatch: () -> Unit,
) {
    val context = LocalContext.current
    var watchRefreshKey by remember { mutableIntStateOf(0) }
    var watchStatus by remember { mutableStateOf<DndSyncStatus?>(null) }
    var watchLoading by remember { mutableStateOf(true) }
    LaunchedEffect(watchRefreshKey) {
        watchLoading = true
        watchStatus = withContext(Dispatchers.IO) { requestWatchDndSyncStatus(context) }
        watchLoading = false
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        PageHeader(stringResource(R.string.permissions_and_background), onBack)
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = if (snapshot.criticalIssues > 0) Color(0xFF2B171B) else WmwCard,
            border = BorderStroke(1.dp, if (snapshot.criticalIssues > 0) Color(0xFFFFB4AB).copy(alpha = .5f) else WmwBlue.copy(alpha = .24f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (snapshot.criticalIssues > 0) Icons.Default.Warning else Icons.Default.CheckCircle,
                        null,
                        tint = if (snapshot.criticalIssues > 0) Color(0xFFFFB4AB) else WmwGreen,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (snapshot.criticalIssues > 0) stringResource(R.string.permissions_attention_required) else stringResource(R.string.permissions_basic_ready),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.permissions_phone_detected, snapshot.manufacturer, snapshot.model, snapshot.androidRelease, snapshot.sdkInt),
                    color = WmwMuted,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
                if (!snapshot.nativeOHealthPhone) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        when (snapshot.dndSyncStatus) {
                            "ready_phone_permissions" -> stringResource(R.string.permission_dnd_sync_status_phone_ready)
                            else -> stringResource(R.string.permission_dnd_sync_status_missing)
                        },
                        color = WmwMuted,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Text(
            stringResource(R.string.permissions_section_phone),
            color = WmwMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        SettingsPanel {
            PermissionStatusRow(
                icon = Icons.Default.Notifications,
                title = stringResource(R.string.permission_notification_listener),
                ok = snapshot.notificationListenerGranted,
                okText = stringResource(R.string.granted),
                badText = stringResource(R.string.not_granted),
                actionText = stringResource(R.string.open_settings),
                onAction = onNotificationListener,
            )
            DividerLine()
            PermissionStatusRow(
                icon = Icons.Default.Notifications,
                title = stringResource(R.string.permission_post_notifications),
                ok = snapshot.postNotificationsGranted,
                okText = stringResource(R.string.granted),
                badText = stringResource(R.string.not_granted),
                actionText = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) stringResource(R.string.grant_access) else stringResource(R.string.open_settings),
                onAction = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) onPostNotifications else onAppNotificationSettings,
            )
            DividerLine()
            PermissionStatusRow(
                icon = ImageVector.vectorResource(id = R.drawable.ic_do_not_disturb_on),
                title = stringResource(R.string.permission_dnd_policy),
                ok = snapshot.nativeOHealthPhone || snapshot.dndPolicyAccessGranted,
                okText = if (snapshot.nativeOHealthPhone) stringResource(R.string.disabled_native_ohealth) else stringResource(R.string.granted),
                badText = stringResource(R.string.not_granted),
                actionText = stringResource(R.string.open_settings),
                enabled = !snapshot.nativeOHealthPhone,
                onAction = onDndPolicy,
            )
            DividerLine()
            PermissionStatusRow(
                icon = ImageVector.vectorResource(id = R.drawable.ic_battery_saver),
                title = stringResource(R.string.permission_battery_unrestricted),
                ok = snapshot.batteryOptimizationIgnored,
                okText = stringResource(R.string.enabled),
                badText = stringResource(R.string.permission_battery_restricted),
                actionText = stringResource(R.string.permission_request_unrestricted),
                onAction = onBattery,
            )
            DividerLine()
            PermissionStatusRow(
                icon = ImageVector.vectorResource(id = R.drawable.ic_alarm),
                title = stringResource(R.string.permission_exact_alarm),
                ok = snapshot.exactAlarmAllowed != false,
                okText = if (snapshot.exactAlarmAllowed == null) stringResource(R.string.not_available) else stringResource(R.string.granted),
                badText = stringResource(R.string.not_granted),
                actionText = stringResource(R.string.open_settings),
                onAction = onExactAlarm,
            )
            if (!snapshot.notificationListenerGranted) {
                DividerLine()
                PermissionStatusRow(
                    icon = ImageVector.vectorResource(id = R.drawable.ic_admin_panel_settings),
                    title = stringResource(R.string.permission_restricted_settings),
                    ok = false,
                    okText = stringResource(R.string.not_available),
                    badText = stringResource(R.string.permission_manual_only),
                    actionText = stringResource(R.string.open_app_info),
                    onAction = onAppDetails,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.permissions_section_watch),
            color = WmwMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        WatchPermissionsPanel(
            status = watchStatus,
            loading = watchLoading,
            ringerSyncEnabled = ringerSyncEnabled,
        )

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                onRefresh()
                watchRefreshKey++
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Refresh, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.refresh))
        }

        if (snapshot.criticalIssues > 0 || snapshot.warningIssues > 0) {
            Spacer(Modifier.height(12.dp))
            ManualSetupCard(snapshot = snapshot, onAppDetails = onAppDetails)
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onShareAdbGuideHtml, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Settings, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.dnd_adb_guide_title))
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onCheckWatch, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Refresh, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.dnd_adb_check_watch))
        }
        Spacer(Modifier.height(20.dp))
    }
}

private fun Context.shareDndAdbGuideHtml() {
    runCatching {
        val file = File(cacheDir, DndAdbGuideContent.htmlFileName)
        file.writeText(DndAdbGuideContent.html(), Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/html")
            .putExtra(Intent.EXTRA_SUBJECT, "Wake My Watch — ADB guide")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(Intent.createChooser(intent, "Wake My Watch ADB guide HTML").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        Toast.makeText(this, R.string.dnd_adb_guide_export_failed, Toast.LENGTH_SHORT).show()
        EventHistoryStore.add(this, "DND_SETUP", "HTML_EXPORT_FAILED", it.message ?: it.javaClass.simpleName)
    }
}

@Composable
private fun WatchPermissionsPanel(
    status: DndSyncStatus?,
    loading: Boolean,
    ringerSyncEnabled: Boolean,
) {
    SettingsPanel {
        when {
            loading -> WatchPermInfoRow(stringResource(R.string.permissions_watch_checking))
            status == null || !status.reachable ->
                WatchPermInfoRow(stringResource(R.string.permissions_watch_unreachable))
            else -> {
                PermissionStatusRow(
                    icon = Icons.Default.Notifications,
                    title = stringResource(R.string.permission_watch_notification_listener),
                    ok = status.watchNotificationListenerGranted,
                    okText = stringResource(R.string.granted),
                    badText = stringResource(R.string.not_granted),
                    actionText = "",
                    onAction = {},
                )
                DividerLine()
                PermissionStatusRow(
                    icon = ImageVector.vectorResource(id = R.drawable.ic_do_not_disturb_on),
                    title = stringResource(R.string.permission_watch_dnd_policy),
                    ok = status.watchDndPolicyAccessGranted,
                    okText = stringResource(R.string.granted),
                    badText = stringResource(R.string.not_granted),
                    actionText = "",
                    onAction = {},
                )
                DividerLine()
                PermissionStatusRow(
                    icon = Icons.Default.Notifications,
                    title = stringResource(R.string.permission_watch_post_notifications),
                    ok = status.watchPostNotificationsGranted,
                    okText = stringResource(R.string.granted),
                    badText = stringResource(R.string.not_granted),
                    actionText = "",
                    onAction = {},
                )
                if (!status.watchDndPolicyAccessGranted) {
                    DividerLine()
                    Text(
                        stringResource(
                            if (ringerSyncEnabled) {
                                R.string.permissions_watch_dnd_missing_ringer
                            } else {
                                R.string.permissions_watch_dnd_missing
                            },
                        ),
                        color = Color(0xFFFFB74D),
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun WatchPermInfoRow(text: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Search, null, tint = WmwMuted)
        Spacer(Modifier.width(12.dp))
        Text(text, color = WmwMuted, fontSize = 13.sp, lineHeight = 18.sp)
    }
}

@Composable
private fun PermissionStatusRow(
    icon: ImageVector,
    title: String,
    ok: Boolean,
    okText: String,
    badText: String,
    actionText: String,
    enabled: Boolean = true,
    onAction: () -> Unit,
) {
    val showAction = enabled && !ok && actionText.isNotBlank()
    Column(
        Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else .55f)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(21.dp))
                    .background((if (ok) WmwGreen else Color(0xFFFFB4AB)).copy(alpha = .16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = if (ok) WmwGreen else Color(0xFFFFB4AB))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 19.sp)
                Text(
                    if (ok) okText else badText,
                    color = if (ok) WmwGreen else Color(0xFFFFB4AB),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                )
            }
        }
        if (showAction) {
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onAction, modifier = Modifier.fillMaxWidth()) {
                Text(actionText)
            }
        }
    }
}

@Composable
private fun ManualSetupCard(snapshot: PermissionProbe.Snapshot, onAppDetails: () -> Unit) {
    var expandedPixel by remember { mutableStateOf(false) }
    var expandedSamsung by remember { mutableStateOf(false) }
    var expandedXiaomi by remember { mutableStateOf(false) }
    var expandedHonor by remember { mutableStateOf(false) }
    var expandedOnePlus by remember { mutableStateOf(false) }
    var expandedAdb by remember { mutableStateOf(false) }
    val brand = (snapshot.manufacturer + " " + snapshot.brand).lowercase()
    LaunchedEffect(snapshot.manufacturer, snapshot.brand) {
        expandedPixel = brand.contains("google") || brand.contains("pixel")
        expandedSamsung = brand.contains("samsung")
        expandedXiaomi = brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco")
        expandedHonor = brand.contains("honor") || brand.contains("huawei")
        expandedOnePlus = snapshot.nativeOHealthPhone
    }
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = Color(0xFF2A1820),
        border = BorderStroke(1.dp, Color(0xFFFFB4AB).copy(alpha = .35f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, null, tint = Color(0xFFFFB4AB))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.manual_setup_title), fontWeight = FontWeight.Bold, color = Color(0xFFFFDAD6))
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.manual_setup_summary), color = WmwMuted, fontSize = 13.sp, lineHeight = 18.sp)
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onAppDetails, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Settings, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.open_app_info))
            }
            ManualInstructionSection(
                title = stringResource(R.string.manual_common_title),
                expanded = true,
                onExpanded = {},
                body = stringResource(R.string.manual_common_body),
                alwaysExpanded = true,
            )
            ManualInstructionSection(
                title = stringResource(R.string.manual_pixel_title),
                expanded = expandedPixel,
                onExpanded = { expandedPixel = !expandedPixel },
                body = stringResource(R.string.manual_pixel_body),
            )
            ManualInstructionSection(
                title = stringResource(R.string.manual_samsung_title),
                expanded = expandedSamsung,
                onExpanded = { expandedSamsung = !expandedSamsung },
                body = stringResource(R.string.manual_samsung_body),
            )
            ManualInstructionSection(
                title = stringResource(R.string.manual_xiaomi_title),
                expanded = expandedXiaomi,
                onExpanded = { expandedXiaomi = !expandedXiaomi },
                body = stringResource(R.string.manual_xiaomi_body),
            )
            ManualInstructionSection(
                title = stringResource(R.string.manual_honor_title),
                expanded = expandedHonor,
                onExpanded = { expandedHonor = !expandedHonor },
                body = stringResource(R.string.manual_honor_body),
            )
            ManualInstructionSection(
                title = stringResource(R.string.manual_oneplus_title),
                expanded = expandedOnePlus,
                onExpanded = { expandedOnePlus = !expandedOnePlus },
                body = stringResource(R.string.manual_oneplus_body),
            )
            ManualInstructionSection(
                title = stringResource(R.string.manual_adb_title),
                expanded = expandedAdb,
                onExpanded = { expandedAdb = !expandedAdb },
                body = stringResource(R.string.manual_adb_body),
            )
        }
    }
}

@Composable
private fun ManualInstructionSection(
    title: String,
    expanded: Boolean,
    onExpanded: () -> Unit,
    body: String,
    alwaysExpanded: Boolean = false,
) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = !alwaysExpanded, onClick = onExpanded).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (!alwaysExpanded) Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, tint = WmwMuted)
        }
        if (expanded || alwaysExpanded) {
            Text(body, color = WmwMuted, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun SoundModeDialog(
    settings: PhoneSettings,
    onSettings: (PhoneSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = listOf(
        SoundMode.NONE to stringResource(R.string.sound_none),
        SoundMode.SYSTEM to stringResource(R.string.sound_system),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sound_mode)) },
        text = {
            Column {
                options.forEach { (value, label) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onSettings(settings.copy(soundMode = value)) }.padding(vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = value == settings.soundMode, onClick = { onSettings(settings.copy(soundMode = value)) })
                        Spacer(Modifier.width(8.dp))
                        Text(label, modifier = Modifier.weight(1f))
                    }
                    if (value == SoundMode.SYSTEM) {
                        Text(
                            stringResource(R.string.sound_system_correction_description),
                            color = WmwMuted,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            modifier = Modifier.padding(start = 40.dp, bottom = 4.dp),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
        containerColor = WmwSurface,
    )
}


@Composable
private fun <T> ChoiceDialog(title: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (value, label) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onSelect(value) }.padding(vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = value == selected, onClick = { onSelect(value) })
                        Spacer(Modifier.width(8.dp))
                        Text(label, modifier = Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        containerColor = WmwSurface,
    )
}

@Composable
private fun WristDialog(settings: PhoneSettings, onSettings: (PhoneSettings) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.wrist_detection)) },
        text = {
            Column {
                SwitchDialogRow(stringResource(R.string.skip_wake_off_wrist), settings.skipWakeOffWrist) {
                    onSettings(settings.copy(skipWakeOffWrist = it))
                }
                SwitchDialogRow(stringResource(R.string.skip_sound_off_wrist), settings.skipSoundOffWrist) {
                    onSettings(settings.copy(skipSoundOffWrist = it))
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.off_wrist_support_note), color = WmwMuted, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                SwitchDialogRow(stringResource(R.string.off_wrist_requires_lock), settings.offWristRequiresLock) {
                    onSettings(settings.copy(offWristRequiresLock = it))
                }
                Text(stringResource(R.string.off_wrist_requires_lock_summary), color = WmwMuted, fontSize = 13.sp)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
        containerColor = WmwSurface,
    )
}

@Composable
private fun ringerScheduleSummary(settings: PhoneSettings): String {
    val plans = settings.ringerSchedulePlans
    val on = plans.count { it.enabled }
    return when {
        plans.isEmpty() -> stringResource(R.string.ringer_schedule_summary_empty)
        on == 0 -> stringResource(R.string.ringer_schedule_summary_all_off, plans.size)
        else -> stringResource(R.string.ringer_schedule_summary_on, on, plans.size)
    }
}

@Composable
private fun RingerSchedulesScreen(
    modifier: Modifier,
    plans: List<RingerSchedulePlan>,
    onBack: () -> Unit,
    onTogglePlan: (String, Boolean) -> Unit,
    onEditPlan: (String) -> Unit,
    onAddPlan: () -> Unit,
) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        PageHeader(stringResource(R.string.ringer_schedule), onBack)
        Text(
            stringResource(R.string.ringer_schedule_screen_note),
            color = WmwMuted,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        if (plans.isEmpty()) {
            Text(
                stringResource(R.string.ringer_schedule_empty_hint),
                color = WmwMuted,
                fontSize = 13.sp,
                modifier = Modifier.padding(vertical = 20.dp),
            )
        } else {
            SettingsPanel {
                plans.forEachIndexed { index, plan ->
                    if (index > 0) DividerLine()
                    Row(
                        Modifier.fillMaxWidth().clickable { onEditPlan(plan.id) }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                plan.name.ifBlank { stringResource(R.string.ringer_schedule_plan_default_name) },
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp,
                            )
                            Text(
                                "${RingerScheduleController.hhmm(plan.startMinutes)} – ${RingerScheduleController.hhmm(plan.endMinutes)} · ${daysLabel(plan.days)}",
                                color = WmwMuted,
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                            )
                        }
                        Switch(checked = plan.enabled, onCheckedChange = { onTogglePlan(plan.id, it) })
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Button(onClick = onAddPlan, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Add, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.ringer_schedule_add))
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun RingerSchedulePlanScreen(
    modifier: Modifier,
    original: RingerSchedulePlan?,
    onBack: () -> Unit,
    onSave: (RingerSchedulePlan) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var draft by remember(original?.id) { mutableStateOf(original ?: RingerSchedulePlan()) }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        PageHeader(
            stringResource(if (original == null) R.string.ringer_schedule_new else R.string.ringer_schedule_edit_title),
            onBack,
        )
        SettingsPanel {
            Column(Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { draft = draft.copy(name = it.take(40)) },
                    label = { Text(stringResource(R.string.ringer_schedule_plan_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                RingerScheduleTimeRow(
                    label = stringResource(R.string.ringer_schedule_quiet_start),
                    minutes = draft.startMinutes,
                    enabled = true,
                ) { draft = draft.copy(startMinutes = it) }
                RingerScheduleTimeRow(
                    label = stringResource(R.string.ringer_schedule_quiet_end),
                    minutes = draft.endMinutes,
                    enabled = true,
                ) { draft = draft.copy(endMinutes = it) }
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.ringer_schedule_repeat), fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                DayCircles(draft.days) { day, on -> draft = draft.withDay(day, on) }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.ringer_schedule_days_note),
                    color = WmwMuted,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = { onSave(draft) },
            enabled = draft.startMinutes != draft.endMinutes && draft.days != 0,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.ringer_schedule_save)) }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.ringer_schedule_exit))
        }
        if (onDelete != null) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.ringer_schedule_delete), color = Color(0xFFFFB4AB))
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun DayCircles(days: Int, onToggle: (Int, Boolean) -> Unit) {
    val labels = stringArrayResource(R.array.weekday_short)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        for (day in 0..6) {
            val on = (days shr day) and 1 == 1
            Box(
                Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(19.dp))
                    .background(if (on) WmwPurple else Color.White.copy(alpha = .08f))
                    .clickable { onToggle(day, !on) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    labels.getOrElse(day) { "?" },
                    color = if (on) Color.White else WmwMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun daysLabel(days: Int): String {
    val labels = stringArrayResource(R.array.weekday_short)
    if (days and RingerSchedulePlan.ALL_DAYS == RingerSchedulePlan.ALL_DAYS) return stringResource(R.string.ringer_schedule_days_every)
    if (days == 0b0011111) return stringResource(R.string.ringer_schedule_days_weekdays)
    if (days == 0b1100000) return stringResource(R.string.ringer_schedule_days_weekend)
    return (0..6).filter { (days shr it) and 1 == 1 }.joinToString(" ") { labels.getOrElse(it) { "?" } }
        .ifBlank { stringResource(R.string.ringer_schedule_days_none) }
}

@Composable
private fun RingerScheduleTimeRow(
    label: String,
    minutes: Int,
    enabled: Boolean,
    onPicked: (Int) -> Unit,
) {
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else .45f)
            .clickable(enabled = enabled) {
                android.app.TimePickerDialog(
                    context,
                    { _, hour, minute -> onPicked(hour * 60 + minute) },
                    minutes / 60,
                    minutes % 60,
                    true,
                ).show()
            }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp)
        Text(
            RingerScheduleController.hhmm(minutes),
            color = WmwBlue,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
        )
    }
}

@Composable
private fun SilentNotificationsDialog(settings: PhoneSettings, onSettings: (PhoneSettings) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.silent_notifications)) },
        text = {
            Column {
                Text(stringResource(R.string.silent_notifications_intro), color = WmwMuted, fontSize = 13.sp, lineHeight = 18.sp)
                Spacer(Modifier.height(12.dp))
                SwitchDialogRow(stringResource(R.string.silent_wake_screen), settings.silentWakeScreen) {
                    onSettings(settings.copy(silentWakeScreen = it))
                }
                Text(stringResource(R.string.silent_wake_screen_summary), color = WmwMuted, fontSize = 13.sp, lineHeight = 18.sp)
                Spacer(Modifier.height(12.dp))
                SwitchDialogRow(stringResource(R.string.silent_vibrate), settings.silentVibrate) {
                    onSettings(settings.copy(silentVibrate = it))
                }
                Text(stringResource(R.string.silent_vibrate_summary), color = WmwMuted, fontSize = 13.sp, lineHeight = 18.sp)
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.silent_notifications_ohealth_note), color = WmwMuted, fontSize = 13.sp, lineHeight = 18.sp)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
        containerColor = WmwSurface,
    )
}

@Composable
private fun PauseSettingsDialog(settings: PhoneSettings, onSettings: (PhoneSettings) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pause_settings_title)) },
        text = {
            Column {
                SwitchDialogRow(stringResource(R.string.pause_keeps_alarm_and_dnd), settings.pauseKeepsAlarmAndDnd) {
                    onSettings(settings.copy(pauseKeepsAlarmAndDnd = it))
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.pause_keeps_alarm_and_dnd_summary), color = WmwMuted, fontSize = 13.sp)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
        containerColor = WmwSurface,
    )
}

/**
 * The one path any "the user turned a DND sync toggle off" action must go through — whether
 * that's forceSoftwareDndSync or the inner dndSyncEnabled switch, both sever our control of the
 * watch's DND and both must not leave it stuck in whatever DND state we put it in:
 *   a) clears DND on the watch (clearWatchDndBlocking) while control is still live — blocking,
 *      off the main thread, so `next` below genuinely cannot be applied early;
 *   b) on failure, clearWatchDndBlocking itself queues a deferred retry (PendingDndClear);
 *   c) applies `next` (the already-disabled settings) via onSettings, back on the main
 *      dispatcher — this is what actually flips the toggle and syncs it to the watch.
 * Call this instead of calling onSettings directly whenever a switch's *new* value disables sync.
 */
private suspend fun disableSoftwareDndAndClear(
    context: Context,
    next: PhoneSettings,
    onSettings: (PhoneSettings) -> Unit,
    trigger: String,
) {
    withContext(Dispatchers.IO) { PhoneDndSyncBridge.clearWatchDndBlocking(context, trigger) }
    onSettings(next)
}

/**
 * Symmetric counterpart to disableSoftwareDndAndClear() — the one path any "the user turned a
 * DND sync toggle on" action must go through, whether that's forceSoftwareDndSync or the inner
 * dndSyncEnabled switch. Sync otherwise only reacts to the *next* change of the phone's DND
 * state, so a phone that's already in (or already out of) DND at the moment sync is enabled
 * would leave the watch out of sync indefinitely without this:
 *   a) applies `next` (the already-enabled settings) via onSettings first — this is what flips
 *      the toggle and sends the SETTINGS sync that turns dndSyncEnabled=true on the watch;
 *   b) only then reads the phone's current DND state and pushes it to the watch in one shot
 *      (applyCurrentDndOnEnableBlocking, off the main thread) — reversed order from the disable
 *      path, since the watch must already have dndSyncEnabled=true before it will accept this.
 */
private suspend fun enableSoftwareDndAndApply(
    context: Context,
    next: PhoneSettings,
    onSettings: (PhoneSettings) -> Unit,
    trigger: String,
) {
    onSettings(next)
    withContext(Dispatchers.IO) { PhoneDndSyncBridge.applyCurrentDndOnEnableBlocking(context, trigger) }
}

@Composable
private fun DndDialog(
    settings: PhoneSettings,
    onSettings: (PhoneSettings) -> Unit,
    onOpenAdbGuide: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val permissionProbe = remember { PermissionProbe.snapshot(context) }
    // Reactive to the current setting (unlike permissionProbe, snapshotted once when the dialog
    // opens) so turning forceSoftwareDndSync on/off immediately flips which branch is shown below.
    val effectiveNativeOHealthPhone = permissionProbe.rawNativeOHealthPhone && !settings.forceSoftwareDndSync
    val scope = rememberCoroutineScope()

    var dndSyncStatusValue by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(effectiveNativeOHealthPhone) {
        if (effectiveNativeOHealthPhone) {
            dndSyncStatusValue = withContext(Dispatchers.IO) { requestWatchDndSyncStatus(context).value }
        }
    }

    var watchDndFilter by remember { mutableStateOf<Int?>(null) }
    var watchDndFilterLoading by remember { mutableStateOf(false) }
    suspend fun refreshWatchDndFilter() {
        watchDndFilterLoading = true
        watchDndFilter = withContext(Dispatchers.IO) { requestWatchDndSyncStatus(context).currentInterruptionFilter }
        watchDndFilterLoading = false
    }
    LaunchedEffect(settings.forceSoftwareDndSync) {
        if (settings.forceSoftwareDndSync) refreshWatchDndFilter()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.do_not_disturb)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SwitchDialogRow(stringResource(R.string.wake_screen_on_phone_dnd), settings.wakeScreenOnPhoneDnd) {
                    onSettings(settings.copy(wakeScreenOnPhoneDnd = it))
                }
                DividerLine()
                if (permissionProbe.rawNativeOHealthPhone) {
                    if (effectiveNativeOHealthPhone) {
                        when (dndSyncStatusValue) {
                            1, 0 -> {
                                ReadOnlySwitchDialogRow(
                                    label = stringResource(R.string.dnd_sync_enable),
                                    checked = dndSyncStatusValue == 1,
                                )
                                Text(
                                    stringResource(
                                        if (dndSyncStatusValue == 1) R.string.dnd_sync_status_active else R.string.dnd_sync_status_inactive
                                    ),
                                    color = WmwMuted,
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                            null -> {
                                Text(
                                    stringResource(R.string.dnd_sync_status_checking),
                                    color = WmwMuted,
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp,
                                    modifier = Modifier.padding(vertical = 8.dp),
                                )
                            }
                            else -> {
                                Text(
                                    stringResource(R.string.dnd_sync_status_unknown),
                                    color = WmwMuted,
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp,
                                    modifier = Modifier.padding(vertical = 8.dp),
                                )
                            }
                        }
                        Text(
                            stringResource(R.string.dnd_sync_native_path),
                            color = WmwMuted,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    } else {
                        SwitchDialogRow(stringResource(R.string.dnd_sync_enable), settings.dndSyncEnabled) { enabled ->
                            if (enabled) {
                                // Apply the phone's current DND state to the watch right away —
                                // otherwise sync only reacts to the *next* DND change, leaving the
                                // watch out of sync if the phone is already in (or out of) DND.
                                scope.launch {
                                    enableSoftwareDndAndApply(
                                        context,
                                        settings.copy(dndSyncEnabled = true),
                                        onSettings,
                                        trigger = "dnd_sync_enabled_enabled",
                                    )
                                }
                            } else {
                                // Turning the inner switch off is just as much a "sever control"
                                // moment as turning forceSoftwareDndSync off — must go through the
                                // same clear-before-disable path, or the watch is left in DND with
                                // nothing left to un-stick it.
                                scope.launch {
                                    disableSoftwareDndAndClear(
                                        context,
                                        settings.copy(dndSyncEnabled = false, savedDndSyncEnabledBeforePause = null),
                                        onSettings,
                                        trigger = "dnd_sync_enabled_disabled",
                                    )
                                }
                            }
                        }
                        Text(
                            stringResource(R.string.dnd_sync_description),
                            color = WmwMuted,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = onOpenAdbGuide,
                            enabled = settings.dndSyncEnabled,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.dnd_adb_guide_title))
                        }
                        Spacer(Modifier.height(10.dp))
                        DividerLine()
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.dnd_watch_state_label))
                                Text(
                                    stringResource(
                                        when {
                                            watchDndFilterLoading || watchDndFilter == null -> R.string.dnd_sync_status_checking
                                            watchDndFilter!! < 0 -> R.string.dnd_sync_status_unknown
                                            DndSync.isDndOn(watchDndFilter!!) -> R.string.dnd_watch_state_on
                                            else -> R.string.dnd_watch_state_off
                                        }
                                    ),
                                    color = WmwMuted,
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                            TextButton(
                                enabled = !watchDndFilterLoading,
                                onClick = { scope.launch { refreshWatchDndFilter() } },
                            ) {
                                Text(stringResource(R.string.refresh))
                            }
                        }
                        SwitchDialogRow(stringResource(R.string.dnd_sync_vibrate), settings.dndSyncVibrate) {
                            onSettings(settings.copy(dndSyncVibrate = it))
                        }
                        Text(
                            stringResource(R.string.dnd_sync_vibrate_description),
                            color = WmwMuted,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    DividerLine()
                    SwitchDialogRow(stringResource(R.string.dnd_force_software_sync), settings.forceSoftwareDndSync) { enabled ->
                        if (enabled) {
                            // A previous disable may have left a deferred clear queued (watch was
                            // unreachable at the time) — the user turning sync back on means they
                            // want the watch under our control again, so that stale intent must
                            // not fire later and clear DND out from under them.
                            PhoneDndSyncBridge.cancelPendingClear(context)
                            scope.launch {
                                enableSoftwareDndAndApply(
                                    context,
                                    settings.copy(forceSoftwareDndSync = true),
                                    onSettings,
                                    trigger = "force_software_dnd_sync_enabled",
                                )
                            }
                        } else {
                            scope.launch {
                                disableSoftwareDndAndClear(
                                    context,
                                    settings.copy(
                                        forceSoftwareDndSync = false,
                                        dndSyncEnabled = false,
                                        savedDndSyncEnabledBeforePause = null,
                                    ),
                                    onSettings,
                                    trigger = "force_software_dnd_sync_disabled",
                                )
                            }
                        }
                    }
                    Text(
                        stringResource(R.string.dnd_force_software_sync_description),
                        color = WmwMuted,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                } else {
                    SwitchDialogRow(stringResource(R.string.dnd_sync_enable), settings.dndSyncEnabled) { enabled ->
                        if (enabled) {
                            scope.launch {
                                enableSoftwareDndAndApply(
                                    context,
                                    settings.copy(dndSyncEnabled = true),
                                    onSettings,
                                    trigger = "dnd_sync_enabled_enabled",
                                )
                            }
                        } else {
                            scope.launch {
                                disableSoftwareDndAndClear(
                                    context,
                                    settings.copy(dndSyncEnabled = false, savedDndSyncEnabledBeforePause = null),
                                    onSettings,
                                    trigger = "dnd_sync_enabled_disabled",
                                )
                            }
                        }
                    }
                    Text(
                        stringResource(R.string.dnd_sync_description),
                        color = WmwMuted,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = onOpenAdbGuide,
                        enabled = settings.dndSyncEnabled,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.dnd_adb_guide_title))
                    }
                    DividerLine()
                    SwitchDialogRow(stringResource(R.string.dnd_sync_vibrate), settings.dndSyncVibrate) {
                        onSettings(settings.copy(dndSyncVibrate = it))
                    }
                    Text(
                        stringResource(R.string.dnd_sync_vibrate_description),
                        color = WmwMuted,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
        containerColor = WmwSurface,
    )
}

@Composable
private fun SwitchDialogRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChecked(!checked) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked, onChecked)
    }
}

@Composable
private fun ReadOnlySwitchDialogRow(label: String, checked: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = WmwMuted, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null, enabled = false)
    }
}

@Composable
private fun AppFilterScreen(modifier: Modifier, settings: PhoneSettings, onSettings: (PhoneSettings) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { apps = loadLaunchableApps(context) }
    val visible = remember(apps, query) {
        apps.filter { query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true) }
    }

    Column(modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        PageHeader(stringResource(R.string.app_filter), onBack)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.search_apps)) },
            leadingIcon = { Icon(Icons.Default.Search, null) },
        )
        Spacer(Modifier.height(12.dp))
        SettingsPanel {
            FilterModeRow(stringResource(R.string.all_apps), AppFilterMode.ALL, settings, onSettings)
            DividerLine()
            FilterModeRow(stringResource(R.string.allowlist), AppFilterMode.ALLOWLIST, settings, onSettings)
            DividerLine()
            FilterModeRow(stringResource(R.string.blocklist), AppFilterMode.BLOCKLIST, settings, onSettings)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            if (settings.appFilterMode == AppFilterMode.ALL) stringResource(R.string.filter_all_note) else stringResource(R.string.selected_apps_count, settings.appPackages.size),
            color = WmwMuted,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(8.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            visible.forEach { app ->
                val selected = app.packageName in settings.appPackages
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(enabled = settings.appFilterMode != AppFilterMode.ALL) {
                        val next = settings.appPackages.toMutableSet().apply {
                            if (selected) remove(app.packageName) else add(app.packageName)
                        }
                        onSettings(settings.copy(appPackages = next))
                    },
                    shape = RoundedCornerShape(16.dp),
                    color = WmwCard,
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(WmwSurface), contentAlignment = Alignment.Center) {
                            Text(app.label.take(1).uppercase(), color = WmwPurple, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(app.packageName, color = WmwMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Checkbox(
                            checked = selected,
                            enabled = settings.appFilterMode != AppFilterMode.ALL,
                            onCheckedChange = {
                                val next = settings.appPackages.toMutableSet().apply {
                                    if (selected) remove(app.packageName) else add(app.packageName)
                                }
                                onSettings(settings.copy(appPackages = next))
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun FilterModeRow(label: String, mode: AppFilterMode, settings: PhoneSettings, onSettings: (PhoneSettings) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onSettings(settings.copy(appFilterMode = mode)) }.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(settings.appFilterMode == mode, onClick = { onSettings(settings.copy(appFilterMode = mode)) })
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
private fun DiagnosticsScreen(
    modifier: Modifier,
    settings: PhoneSettings,
    remote: DeviceDescriptor?,
    ack: Pair<Long, String>,
    connection: ConnectionSnapshot,
    onBack: () -> Unit,
    onExpandHistory: () -> Unit,
) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var events by remember(refresh) { mutableStateOf(EventHistoryStore.read(context)) }
    LaunchedEffect(refresh) { events = EventHistoryStore.read(context) }
    val hasAccess = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    Column(modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        PageHeader(stringResource(R.string.diagnostics), onBack)
        SettingsPanel {
            DetailRow(stringResource(R.string.notification_access), if (hasAccess) stringResource(R.string.granted) else stringResource(R.string.not_granted))
            DividerLine()
            DetailRow(stringResource(R.string.connection), if (connection.isConnected(remote?.nodeId)) stringResource(R.string.connected) else stringResource(R.string.disconnected))
            DividerLine()
            DetailRow(
                stringResource(R.string.off_wrist_sensor),
                when {
                    remote == null -> stringResource(R.string.not_available)
                    remote.features.contains(DeviceFeatures.OFF_BODY) -> stringResource(R.string.supported)
                    else -> stringResource(R.string.not_supported)
                },
            )
            DividerLine()
            DetailRow(stringResource(R.string.last_ack), connection.lastAckAt.takeIf { it > 0 }?.let { "${DateFormat.getDateTimeInstance().format(Date(it))} · ${connection.lastAckResult}" } ?: ack.first.takeIf { it > 0 }?.let { DateFormat.getDateTimeInstance().format(Date(it)) })
            DividerLine()
            DetailRow(stringResource(R.string.last_handshake), connection.lastHandshakeAt.takeIf { it > 0 }?.let { DateFormat.getDateTimeInstance().format(Date(it)) })
            DividerLine()
            DetailRow(stringResource(R.string.last_transport), connection.lastSendAt.takeIf { it > 0 }?.let { "${connection.lastSendResult} · ${connection.lastSendPath}" })
            DividerLine()
            DetailRow(stringResource(R.string.settings_revision), settings.revision.toString())
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.event_history), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.event_count, events.size), color = WmwMuted, fontSize = 12.sp)
            }
            IconButton(onClick = { refresh++ }) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
            }
            TextButton(onClick = onExpandHistory) {
                Icon(ImageVector.vectorResource(id = R.drawable.ic_open_in_full), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.expand))
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (events.isEmpty()) {
                Text(stringResource(R.string.no_events), color = WmwMuted, modifier = Modifier.padding(vertical = 24.dp))
            } else {
                events.take(4).forEach { entry ->
                    EventHistoryCard(entry)
                }
                if (events.size > 4) {
                    TextButton(onClick = onExpandHistory, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text(stringResource(R.string.show_all_events, events.size))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun EventHistoryScreen(
    modifier: Modifier,
    onBack: () -> Unit,
    onToast: (String) -> Unit,
) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var events by remember(refresh) { mutableStateOf(EventHistoryStore.read(context)) }
    var showClearConfirmation by remember { mutableStateOf(false) }
    LaunchedEffect(refresh) { events = EventHistoryStore.read(context) }

    Column(modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        PageHeader(stringResource(R.string.event_history), onBack)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.event_count, events.size), color = WmwMuted, modifier = Modifier.weight(1f))
            IconButton(onClick = { refresh++ }) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
            }
            DiagnosticExportButton(
                enabled = events.isNotEmpty(),
                events = events,
                onToast = onToast,
            )
            IconButton(enabled = events.isNotEmpty(), onClick = { showClearConfirmation = true }) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.clear))
            }
        }
        Spacer(Modifier.height(6.dp))
        if (events.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.no_events), color = WmwMuted)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(events, key = { index, entry -> "${entry.time}:$index" }) { _, entry ->
                    EventHistoryCard(entry)
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }

    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
            title = { Text(stringResource(R.string.clear_history_title)) },
            text = { Text(stringResource(R.string.clear_history_message)) },
            confirmButton = {
                TextButton(onClick = {
                    EventHistoryStore.clear(context)
                    showClearConfirmation = false
                    refresh++
                }) { Text(stringResource(R.string.clear)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmation = false }) { Text(stringResource(R.string.cancel)) }
            },
            containerColor = WmwSurface,
        )
    }
}

@Composable
private fun EventHistoryCard(entry: EventHistoryStore.Entry) {
    Surface(Modifier.fillMaxWidth().padding(vertical = 4.dp), shape = RoundedCornerShape(16.dp), color = WmwCard) {
        Column(Modifier.padding(12.dp)) {
            Row {
                Text(entry.type, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(entry.time)), color = WmwMuted, fontSize = 11.sp)
            }
            Text(
                entry.result,
                color = if (entry.result.contains("FAILED") || entry.result == "SKIPPED") Color(0xFFFFB4AB) else WmwBlue,
                fontSize = 13.sp,
            )
            if (entry.repeatCount > 1) {
                val dateTime = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
                Text(
                    "×${entry.repeatCount} · ${dateTime.format(Date(entry.firstTime))} — ${dateTime.format(Date(entry.lastTime))}",
                    color = WmwMuted,
                    fontSize = 11.sp,
                )
            }
            if (entry.detail.isNotBlank()) {
                Text(entry.detail, color = WmwMuted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun DiagnosticExportButton(
    enabled: Boolean,
    events: List<EventHistoryStore.Entry>,
    onToast: (String) -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var pendingBytes by remember { mutableStateOf<ByteArray?>(null) }
    var exporting by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        val bytes = pendingBytes
        pendingBytes = null
        if (uri == null || bytes == null) return@rememberLauncherForActivityResult
        val saved = runCatching {
            context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                output.write(bytes)
                output.flush()
            } ?: error("output_stream_unavailable")
        }.isSuccess
        onToast(context.getString(if (saved) R.string.log_exported else R.string.log_export_failed))
    }

    TextButton(
        enabled = enabled && !exporting,
        onClick = {
            exporting = true
            coroutineScope.launch {
                val archive = withContext(Dispatchers.IO) {
                    buildCombinedDiagnosticZip(context, events)
                }
                pendingBytes = archive
                exporting = false
                val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
                launcher.launch("WakeMyWatch-diagnostics-$timestamp.zip")
            }
        },
    ) {
        Icon(ImageVector.vectorResource(id = R.drawable.ic_file_download), contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(if (exporting) R.string.export_preparing else R.string.export_log))
    }
}

private data class WatchDiagnosticFetch(val report: String?, val error: String?)

private fun buildCombinedDiagnosticZip(
    context: Context,
    initialEvents: List<EventHistoryStore.Entry>,
): ByteArray {
    val watchFetch = requestWatchDiagnosticReport(context)
    val events = EventHistoryStore.read(context).ifEmpty { initialEvents }
    val phoneReport = buildDiagnosticReport(context, events)
    val settings = PhoneSettingsStore.load(context)
    val remote = DeviceStore.remote(context)
    val connection = ConnectionDiagnosticsStore.snapshot(context)
    val permissionSnapshot = PermissionProbe.snapshot(context)
    val dateTime = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)

    val summary = buildString {
        append("Wake My Watch combined diagnostics\n")
        append("Exported: ").append(dateTime.format(Date())).append('\n')
        append("Phone report: included\n")
        append("Watch report: ").append(if (watchFetch.report != null) "included" else "unavailable").append('\n')
        if (watchFetch.error != null) append("Watch error: ").append(watchFetch.error).append('\n')
        append("This package contains Wake My Watch's own phone/watch logs. It does not contain full Android logcat, OHealth internals or a system bugreport.\n")
    }
    val settingsReport = buildString {
        append("Phone settings\n")
        append(SettingsAudit.snapshot(settings)).append('\n')
        append("\nSettings changes are also preserved in phone-events.txt.\n")
    }
    val devicesReport = buildString {
        append("Phone\n")
        append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(" | Android ").append(Build.VERSION.RELEASE)
            .append(" | API ").append(Build.VERSION.SDK_INT)
            .append(" | build=").append(Build.DISPLAY).append('\n')
        append("App: ").append(BuildConfig.VERSION_NAME).append(" (code ").append(BuildConfig.VERSION_CODE).append(")\n\n")
        append("Watch\n")
        if (remote == null) {
            append("Unknown\n")
        } else {
            append(remote.displayName)
                .append(" | OS ").append(remote.osRelease)
                .append(" | API ").append(remote.sdkInt)
                .append(" | build=").append(remote.buildDisplay)
                .append(" | app=").append(remote.appVersion)
                .append(" | sound=").append(remote.notificationSoundName ?: "unknown")
                .append(" | node=").append(remote.nodeId ?: "unknown").append('\n')
        }
    }
    val connectionReport = buildString {
        append("connectedNodes=").append(connection.connectedNodeIds.sorted().joinToString(",")).append('\n')
        append("checkedAt=").append(connection.checkedAt).append('\n')
        append("lastPeerConnectedAt=").append(connection.lastPeerConnectedAt).append('\n')
        append("lastPeerDisconnectedAt=").append(connection.lastPeerDisconnectedAt).append('\n')
        append("lastHandshakeAt=").append(connection.lastHandshakeAt).append('\n')
        append("lastHandshakeDirection=").append(connection.lastHandshakeDirection).append('\n')
        append("lastSendAt=").append(connection.lastSendAt).append('\n')
        append("lastSendPath=").append(connection.lastSendPath).append('\n')
        append("lastSendResult=").append(connection.lastSendResult).append('\n')
        append("lastSendDetail=").append(connection.lastSendDetail).append('\n')
        append("lastAckAt=").append(connection.lastAckAt).append('\n')
        append("lastAckResult=").append(connection.lastAckResult).append('\n')
        append("lastAckDetail=").append(connection.lastAckDetail).append('\n')
    }

    val output = ByteArrayOutputStream()
    ZipOutputStream(output).use { zip ->
        zip.putText("summary.txt", summary)
        zip.putText("phone-events.txt", phoneReport)
        zip.putText("settings.txt", settingsReport)
        zip.putText("devices.txt", devicesReport)
        zip.putText("connection.txt", connectionReport)
        zip.putText("permissions.txt", PermissionProbe.report(context, permissionSnapshot))
        if (watchFetch.report != null) {
            zip.putText("watch-events.txt", watchFetch.report)
        } else {
            zip.putText("watch-unavailable.txt", watchFetch.error ?: "Watch diagnostics were not received before timeout.")
        }
    }
    return output.toByteArray()
}

private fun requestWatchDiagnosticReport(context: Context): WatchDiagnosticFetch {
    val requestId = Protocol.eventId()
    val future = PhoneDiagnosticsTransfer.prepare(requestId)
    val envelope = MessageEnvelope(
        type = "DIAGNOSTICS_REQUEST",
        eventId = requestId,
        payload = JSONObject().put("requestedAt", System.currentTimeMillis()),
    )
    EventHistoryStore.add(context, "DIAGNOSTICS", "WATCH_REPORT_REQUESTED", "requestId=$requestId")
    WearTransport.sendPreferred(context, Protocol.DIAGNOSTICS_REQUEST, envelope) { result ->
        EventHistoryStore.add(
            context,
            "DIAGNOSTICS",
            if (result.success) "WATCH_REQUEST_SENT" else "WATCH_REQUEST_FAILED",
            "requestId=$requestId ${result.detail}",
        )
        if (!result.success) PhoneDiagnosticsTransfer.fail(requestId, result.detail)
    }
    return try {
        val result = future.get(8, TimeUnit.SECONDS)
        WatchDiagnosticFetch(result.report, result.error)
    } catch (error: Exception) {
        PhoneDiagnosticsTransfer.cancel(requestId)
        val detail = error.cause?.message ?: error.message ?: error.javaClass.simpleName
        EventHistoryStore.add(context, "DIAGNOSTICS", "WATCH_REPORT_TIMEOUT", "requestId=$requestId error=$detail")
        WatchDiagnosticFetch(null, detail)
    }
}

private fun requestWatchDndSyncStatus(context: Context): DndSyncStatus {
    val hasConnectedNode = try {
        Tasks.await(Wearable.getNodeClient(context).connectedNodes, 2, TimeUnit.SECONDS).isNotEmpty()
    } catch (error: Exception) {
        false
    }
    if (!hasConnectedNode) return DndSyncStatus(-1, -1, reachable = false)

    requestWatchDndSyncStatusOnce(context, attempt = 1)?.let { return it }
    requestWatchDndSyncStatusOnce(context, attempt = 2)?.let { return it }
    return DndSyncStatus(-1, -1, reachable = false)
}

/** Returns the watch's answer, or null if no response arrived within the per-attempt timeout
 * (5s per attempt — a cold round-trip to the watch takes ~4s). */
private fun requestWatchDndSyncStatusOnce(context: Context, attempt: Int): DndSyncStatus? {
    val requestId = Protocol.eventId()
    val future = PhoneDndSyncStatusTransfer.prepare(requestId)
    val envelope = MessageEnvelope(
        type = "DND_SYNC_STATUS_REQUEST",
        eventId = requestId,
    )
    EventHistoryStore.add(context, "DND_SYNC_STATUS", "REQUESTED", "requestId=$requestId attempt=$attempt")
    WearTransport.sendPreferred(context, Protocol.DND_SYNC_STATUS_REQUEST, envelope) { result ->
        if (!result.success) PhoneDndSyncStatusTransfer.fail(requestId)
    }
    return try {
        future.get(5, TimeUnit.SECONDS)
    } catch (error: Exception) {
        PhoneDndSyncStatusTransfer.cancel(requestId)
        null
    }
}

private fun ZipOutputStream.putText(name: String, text: String) {
    putNextEntry(ZipEntry(name))
    write(text.toByteArray(Charsets.UTF_8))
    closeEntry()
}

private fun buildDiagnosticReport(context: Context, events: List<EventHistoryStore.Entry>): String {
    val dateTime = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
    val settings = PhoneSettingsStore.load(context)
    val remote = DeviceStore.remote(context)
    val connection = ConnectionDiagnosticsStore.snapshot(context)
    val notificationAccess = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    return buildString {
        append("Wake My Watch phone diagnostics\n")
        append("Phone app version: ").append(BuildConfig.VERSION_NAME).append(" (code ").append(BuildConfig.VERSION_CODE).append(")\n")
        append("Exported: ").append(dateTime.format(Date())).append('\n')
        append("Phone: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(" | Android ").append(Build.VERSION.RELEASE).append(" | API ").append(Build.VERSION.SDK_INT)
            .append(" | build=").append(Build.DISPLAY).append('\n')
        append("Notification listener access: ").append(notificationAccess).append('\n')
        append("Current settings: ").append(SettingsAudit.snapshot(settings)).append('\n')
        if (remote != null) {
            append("Watch: ").append(remote.displayName)
                .append(" | OS ").append(remote.osRelease).append(" | API ").append(remote.sdkInt)
                .append(" | app=").append(remote.appVersion)
                .append(" | build=").append(remote.buildDisplay)
                .append(" | sound=").append(remote.notificationSoundName ?: "unknown")
                .append(" | node=").append(remote.nodeId ?: "unknown")
                .append('\n')
        } else {
            append("Watch: unknown\n")
        }
        append("Connection: connectedNodes=").append(connection.connectedNodeIds.sorted().joinToString(","))
            .append(" lastHandshakeAt=").append(connection.lastHandshakeAt)
            .append(" lastHandshakeDirection=").append(connection.lastHandshakeDirection)
            .append(" lastSendAt=").append(connection.lastSendAt)
            .append(" lastSendPath=").append(connection.lastSendPath)
            .append(" lastSendResult=").append(connection.lastSendResult)
            .append(" lastAckAt=").append(connection.lastAckAt)
            .append(" lastAckResult=").append(connection.lastAckResult)
            .append(" lastAckDetail=").append(connection.lastAckDetail)
            .append('\n')
        append("Events: ").append(events.size).append(" (newest first; store limit 500)\n\n")
        events.forEachIndexed { index, entry ->
            append('#').append(index + 1).append(" [")
            append(dateTime.format(Date(entry.time)))
            append("] ")
            append(entry.type)
            append(" · ")
            append(entry.result)
            if (entry.repeatCount > 1) {
                append(" · repeated=").append(entry.repeatCount)
                append(" first=").append(dateTime.format(Date(entry.firstTime)))
                append(" last=").append(dateTime.format(Date(entry.lastTime)))
            }
            if (entry.detail.isNotBlank()) {
                append('\n').append(entry.detail)
            }
            append("\n\n")
        }
    }.trimEnd()
}

private suspend fun loadLaunchableApps(context: Context): List<InstalledApp> = withContext(Dispatchers.IO) {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    @Suppress("DEPRECATION")
    context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        .mapNotNull { info ->
            val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
            if (packageName == context.packageName) return@mapNotNull null
            InstalledApp(info.loadLabel(context.packageManager).toString().ifBlank { packageName }, packageName)
        }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}

@Composable
private fun soundModeLabel(settings: PhoneSettings): String = when (settings.soundMode) {
    SoundMode.NONE -> stringResource(R.string.sound_none)
    SoundMode.SYSTEM -> stringResource(R.string.sound_system)
}

@Composable
private fun wristSummary(settings: PhoneSettings, supported: Boolean): String = when {
    !supported -> stringResource(R.string.not_supported_by_watch)
    settings.skipWakeOffWrist && settings.skipSoundOffWrist -> stringResource(R.string.wrist_both_enabled)
    settings.skipWakeOffWrist -> stringResource(R.string.wrist_wake_only)
    settings.skipSoundOffWrist -> stringResource(R.string.wrist_sound_only)
    else -> stringResource(R.string.disabled)
}

@Composable
private fun silentNotificationsSummary(settings: PhoneSettings): String = when {
    settings.silentWakeScreen && settings.silentVibrate -> stringResource(R.string.silent_both_enabled)
    settings.silentWakeScreen -> stringResource(R.string.silent_wake_only)
    settings.silentVibrate -> stringResource(R.string.silent_vibrate_only)
    else -> stringResource(R.string.disabled)
}

@Composable
private fun dndSummary(settings: PhoneSettings): String {
    val wakePart = if (settings.wakeScreenOnPhoneDnd) {
        stringResource(R.string.dnd_wake_on_phone_dnd_on)
    } else {
        stringResource(R.string.dnd_wake_on_phone_dnd_off)
    }
    val watchPart = if (settings.respectWatchDnd) {
        stringResource(R.string.dnd_respects_watch_dnd)
    } else {
        stringResource(R.string.dnd_ignores_watch_dnd)
    }
    val base = "$wakePart · $watchPart"
    return if (settings.dndSyncEnabled) {
        "$base · ${stringResource(R.string.dnd_sync_enabled_short)}"
    } else {
        base
    }
}

@Composable
private fun appFilterSummary(settings: PhoneSettings): String = when (settings.appFilterMode) {
    AppFilterMode.ALL -> stringResource(R.string.all_apps)
    AppFilterMode.ALLOWLIST -> stringResource(R.string.allowlist_count, settings.appPackages.size)
    AppFilterMode.BLOCKLIST -> stringResource(R.string.blocklist_count, settings.appPackages.size)
}

@Composable
private fun PageHeader(title: String, onBack: () -> Unit) {
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    }
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun DeviceCard(
    remote: DeviceDescriptor?,
    ack: Pair<Long, String>,
    connected: Boolean,
    onClick: (() -> Unit)? = null,
) {
    // onClick == null on the Device Info screen itself: the card there leads nowhere, so it is
    // not clickable and shows no chevron. On the main screen it navigates here.
    val clickModifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Surface(
        modifier = Modifier.fillMaxWidth().then(clickModifier),
        shape = RoundedCornerShape(22.dp), color = WmwCard,
        border = BorderStroke(1.dp, WmwBlue.copy(alpha = .45f)),
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(64.dp).clip(RoundedCornerShape(18.dp)).background(WmwSurface), contentAlignment = Alignment.Center) {
                Icon(ImageVector.vectorResource(id = R.drawable.ic_watch), null, tint = WmwBlue, modifier = Modifier.size(38.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(remote?.headline ?: stringResource(R.string.searching_watch), fontWeight = FontWeight.Bold, fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(8.dp)).background(if (connected) WmwGreen else WmwMuted))
                    Spacer(Modifier.width(7.dp))
                    Text(if (connected) stringResource(R.string.connected) else stringResource(R.string.disconnected), color = if (connected) WmwGreen else WmwMuted)
                }
                if (ack.first > 0) Text("${stringResource(R.string.last_ack)}: ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ack.first))}", color = WmwMuted, fontSize = 12.sp)
            }
            if (onClick != null) Icon(Icons.Default.KeyboardArrowRight, null, tint = WmwMuted)
        }
    }
}

@Composable
private fun PauseCard(
    paused: Boolean,
    keepsAlarmAndDnd: Boolean,
    onToggle: (Boolean) -> Unit,
    onSettingsClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = if (paused) Color(0xFF2A1F14) else WmwCard,
        border = BorderStroke(1.dp, if (paused) Color(0xFFFFB74D).copy(alpha = .5f) else WmwBlue.copy(alpha = .25f)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(ImageVector.vectorResource(id = R.drawable.ic_pause_circle), null, tint = if (paused) Color(0xFFFFB74D) else WmwMuted, modifier = Modifier.size(30.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.app_paused_toggle), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text(
                        if (paused) stringResource(R.string.app_paused_status_on) else stringResource(R.string.app_paused_status_off),
                        color = if (paused) Color(0xFFFFB74D) else WmwMuted,
                        fontSize = 13.sp,
                    )
                }
                Switch(checked = paused, onCheckedChange = onToggle)
            }
            if (paused) {
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onSettingsClick),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Settings, null, tint = WmwMuted, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (keepsAlarmAndDnd) {
                            stringResource(R.string.pause_keeps_alarm_and_dnd_summary)
                        } else {
                            stringResource(R.string.app_paused_status_on_full)
                        },
                        color = WmwMuted,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(Icons.Default.KeyboardArrowRight, null, tint = WmwMuted)
                }
            }
        }
    }
}

@Composable
private fun SettingsPanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(22.dp), color = WmwCard, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 4.dp), content = content)
    }
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    summary: String,
    trailing: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val clickModifier = if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier
    Row(
        Modifier.fillMaxWidth().then(clickModifier).alpha(if (enabled) 1f else .48f).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(23.dp)).background(WmwPurple.copy(alpha = .22f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = WmwPurple)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(summary, color = WmwMuted, fontSize = 13.sp, lineHeight = 17.sp)
        }
        when {
            trailing != null -> trailing()
            enabled -> Icon(Icons.Default.KeyboardArrowRight, null, tint = WmwMuted)
            else -> Icon(Icons.Default.Close, null, tint = WmwMuted)
        }
    }
}

@Composable
private fun DividerLine() = HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = Color.White.copy(alpha = .07f))

@Composable
private fun ActionCard(icon: ImageVector, title: String, summary: String, onClick: () -> Unit, tint: Color = WmwBlue) {
    Surface(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = WmwCard,
        border = BorderStroke(1.dp, tint.copy(alpha = .25f)),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, color = tint, fontWeight = FontWeight.SemiBold)
                Text(summary, color = WmwMuted, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun Footer(onLegal: () -> Unit) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Text(stringResource(R.string.made_for), color = WmwMuted, modifier = Modifier.clickable {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://4pda.to/forum/index.php?showuser=4077878"))
            context.startActivity(intent)
        })
        Spacer(Modifier.width(10.dp))
        IconButton(onClick = onLegal, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Info, stringResource(R.string.about_title), tint = WmwPurple) }
    }
}

@Composable
private fun DeviceInfoScreen(modifier: Modifier, remote: DeviceDescriptor?, ack: Pair<Long, String>, connection: ConnectionSnapshot, onBack: () -> Unit, onResync: () -> Unit, onLegal: () -> Unit) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        PageHeader(stringResource(R.string.device_info), onBack)
        DeviceCard(remote, ack, connection.isConnected(remote?.nodeId))
        Spacer(Modifier.height(18.dp))
        SettingsPanel {
            DetailRow(stringResource(R.string.model), remote?.displayName)
            DividerLine()
            DetailRow(stringResource(R.string.wear_os), remote?.let { "Wear OS / Android ${it.osRelease} (API ${it.sdkInt})" })
            DividerLine()
            DetailRow(stringResource(R.string.build_number), remote?.buildDisplay)
            DividerLine()
            DetailRow(stringResource(R.string.app_version), remote?.appVersion)
            DividerLine()
            DetailRow(
                stringResource(R.string.last_sync),
                connection.lastAckAt.takeIf { it > 0 }?.let { "${DateFormat.getDateTimeInstance().format(Date(it))} · ${connection.lastAckResult}" }
                    ?: connection.lastHandshakeAt.takeIf { it > 0 }?.let { DateFormat.getDateTimeInstance().format(Date(it)) }
                    ?: ack.first.takeIf { it > 0 }?.let { DateFormat.getDateTimeInstance().format(Date(it)) },
            )
        }
        Spacer(Modifier.height(14.dp))
        ActionCard(Icons.Default.Refresh, stringResource(R.string.resync), stringResource(R.string.test_connection), onResync)
        Footer(onLegal)
    }
}

@Composable
private fun DetailRow(label: String, value: String?) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(label, color = WmwMuted, fontSize = 12.sp, lineHeight = 15.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            value ?: stringResource(R.string.not_available),
            color = WmwText,
            fontSize = 14.sp,
            lineHeight = 19.sp,
        )
    }
}
