package com.h_ide4pda.wakemywatch.watch.alarm

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import java.util.Date
import java.util.Locale
import com.h_ide4pda.wakemywatch.core.EventHistoryStore
import com.h_ide4pda.wakemywatch.core.MessageEnvelope
import com.h_ide4pda.wakemywatch.core.Protocol
import com.h_ide4pda.wakemywatch.core.WearTransport
import com.h_ide4pda.wakemywatch.watch.R
import org.json.JSONObject

class AlarmActivity : ComponentActivity() {
    private var activeSession by mutableStateOf<AlarmSession?>(null)
    private var actionState by mutableStateOf(AlarmActionUiState())
    private var pendingRequestId: String? = null
    private var pendingAlarmEventId: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val actionTimeout = Runnable {
        val requestId = pendingRequestId ?: return@Runnable
        EventHistoryStore.add(this, "ALARM_ACTION", "TIMEOUT", requestId)
        pendingRequestId = null
        pendingAlarmEventId = null
        actionState = AlarmActionUiState(
            status = AlarmActionStatus.ERROR,
            detail = getString(R.string.alarm_action_timeout),
        )
    }

    private val alarmReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_CLOSE -> handleClose(intent)
                ACTION_RESULT -> handleActionResult(intent)
                Intent.ACTION_SCREEN_ON -> recoverAlarmOnScreenOn()
                Intent.ACTION_SCREEN_OFF -> repostFullScreenNotificationOnScreenOff()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val filter = IntentFilter(ACTION_CLOSE).apply {
            addAction(ACTION_RESULT)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(
            this,
            alarmReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        activeSession = AlarmSessionStore.load(this)
        val session = activeSession
        if (session == null) {
            finish()
            return
        }
        // Deliberately NOT cancelling the recovery notification here: it must stay available as
        // a manual fallback for the whole ring (long screen-off periods can outlast reorder-to-
        // front), and is only ever cancelled at a real close (see onDestroy()/close()).
        EventHistoryStore.add(this, "ALARM", "ALARM_SHOWN", session.title)
        AlarmAlertController.start(this, session.eventId)
        setContent {
            activeSession?.let { session ->
                AlarmScreen(
                    session = session,
                    actionState = actionState,
                    onAction = { action -> sendAction(session, action) },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        mainHandler.removeCallbacks(actionTimeout)
        pendingRequestId = null
        pendingAlarmEventId = null
        actionState = AlarmActionUiState()
        activeSession = AlarmSessionStore.load(this)
        val session = activeSession
        if (session == null) {
            AlarmAlertController.stop(this, "new_intent_no_session")
            finishAndRemoveTask()
        } else {
            EventHistoryStore.add(this, "ALARM", "ALARM_SHOWN", session.title)
            AlarmAlertController.start(this, session.eventId)
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(actionTimeout)
        AlarmAlertController.stop(this, "activity_destroyed")
        // Catch-all: whatever path led here (dismiss/snooze confirmed, phone-initiated close, a
        // stale/no-session relaunch, or the system just tearing the Activity down), the alarm is
        // over — the recovery notification must not outlive it.
        cancelAlarmNotifications(this)
        runCatching { unregisterReceiver(alarmReceiver) }
        super.onDestroy()
    }

    private fun handleClose(intent: Intent) {
        val eventId = intent.getStringExtra(EXTRA_EVENT_ID)
        val active = activeSession ?: AlarmSessionStore.load(this)
        if (eventId.isNullOrBlank() || active == null || active.eventId == eventId) {
            mainHandler.removeCallbacks(actionTimeout)
            AlarmAlertController.stop(this, "close_from_phone")
            pendingRequestId = null
            pendingAlarmEventId = null
            activeSession = null
            finishAndRemoveTask()
        }
    }

    private fun handleActionResult(intent: Intent) {
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        val alarmEventId = intent.getStringExtra(EXTRA_EVENT_ID).orEmpty()
        val result = intent.getStringExtra(EXTRA_RESULT).orEmpty()
        val detail = intent.getStringExtra(EXTRA_DETAIL).orEmpty()
        val active = activeSession ?: AlarmSessionStore.load(this) ?: return

        val expectedRequestId = pendingRequestId ?: return
        val expectedAlarmEventId = pendingAlarmEventId ?: return
        if (requestId != expectedRequestId) return
        if (alarmEventId != expectedAlarmEventId || alarmEventId != active.eventId) return

        mainHandler.removeCallbacks(actionTimeout)
        pendingRequestId = null
        pendingAlarmEventId = null

        if (result == "ALARM_ACTION_OK") {
            EventHistoryStore.add(this, "ALARM_ACTION", "CONFIRMED", detail)
            AlarmAlertController.stop(this, "action_confirmed")
            AlarmSessionStore.clear(this, active.eventId)
            actionState = AlarmActionUiState(status = AlarmActionStatus.CONFIRMED)
            activeSession = null
            finishAndRemoveTask()
        } else {
            EventHistoryStore.add(this, "ALARM_ACTION", "FAILED", detail)
            actionState = AlarmActionUiState(
                status = AlarmActionStatus.ERROR,
                detail = detail.ifBlank { getString(R.string.alarm_action_failed) },
            )
        }
    }

    private fun sendAction(session: AlarmSession, action: String) {
        if (pendingRequestId != null) return

        AlarmAlertController.stop(this, "user_$action")
        val requestId = Protocol.eventId()
        pendingRequestId = requestId
        pendingAlarmEventId = session.eventId
        actionState = AlarmActionUiState(
            status = AlarmActionStatus.SENDING,
            action = action,
        )
        mainHandler.removeCallbacks(actionTimeout)
        mainHandler.postDelayed(actionTimeout, ACTION_TIMEOUT_MS)

        val envelope = MessageEnvelope(
            type = "ALARM_ACTION",
            eventId = requestId,
            payload = JSONObject()
                .put("alarmEventId", session.eventId)
                .put("action", action),
        )
        WearTransport.sendPreferred(this, Protocol.ALARM_ACTION, envelope) { result ->
            EventHistoryStore.add(
                this,
                "ALARM_ACTION",
                if (result.success) "SENT" else "SEND_FAILED",
                "$action:${result.detail}",
            )
            if (!result.success) {
                runOnUiThread {
                    if (pendingRequestId != requestId || pendingAlarmEventId != session.eventId) {
                        return@runOnUiThread
                    }
                    mainHandler.removeCallbacks(actionTimeout)
                    pendingRequestId = null
                    pendingAlarmEventId = null
                    actionState = AlarmActionUiState(
                        status = AlarmActionStatus.ERROR,
                        detail = result.detail.ifBlank { getString(R.string.alarm_action_failed) },
                    )
                }
            }
        }
    }

    private fun recoverAlarmOnScreenOn() {
        val session = activeSession ?: AlarmSessionStore.load(this) ?: return
        activeSession = session
        if (!AlarmAlertController.refreshForScreenOn(this, session.eventId)) {
            EventHistoryStore.add(this, "ALARM_SCREEN_ON", "SKIPPED", "inactive")
            return
        }

        EventHistoryStore.add(this, "ALARM_SCREEN_ON", "RECOVER", session.eventId)
        runCatching {
            val pendingIntent = alarmActivityPendingIntent(
                this,
                ALARM_RECOVERY_REQUEST_CODE,
                alarmRecoveryIntent(this),
            )
            val options = pendingIntentSenderOptions()
            if (options == null) {
                pendingIntent.send()
            } else {
                pendingIntent.send(this, 0, null, null, null, null, options)
            }
        }.onSuccess {
            EventHistoryStore.add(this, "ALARM_SCREEN_ON", "PI_SENT", session.eventId)
        }.onFailure { error ->
            EventHistoryStore.add(this, "ALARM_SCREEN_ON", "PI_FAILED", error.message ?: error.javaClass.simpleName)
        }
    }

    /**
     * reorder-to-front (recoverAlarmOnScreenOn) only wins the race against Wear OS's
     * watchface-on-wake takeover some of the time, and loses more often on longer screen-off
     * periods. Full-screen-intent notifications are a separate, OS-privileged interrupt path
     * (used by calls/alarms to appear over the lock screen); re-posting one right as the screen
     * goes off — before the watchface has been decided on — gives that path a chance to win
     * instead. One-shot per SCREEN_OFF, not a loop.
     */
    private fun repostFullScreenNotificationOnScreenOff() {
        val session = activeSession ?: AlarmSessionStore.load(this) ?: return
        activeSession = session
        val posted = postScreenOffFullScreenNotification(this, session)
        EventHistoryStore.add(this, "ALARM_SCREEN_OFF_FSI", if (posted) "POSTED" else "SKIPPED", session.eventId)
    }

    companion object {
        private const val ACTION_CLOSE = "com.h_ide4pda.wakemywatch.action.CLOSE_ALARM"
        private const val ACTION_RESULT = "com.h_ide4pda.wakemywatch.action.ALARM_ACTION_RESULT"
        private const val EXTRA_EVENT_ID = "alarm_event_id"
        private const val EXTRA_REQUEST_ID = "alarm_action_request_id"
        private const val EXTRA_RESULT = "alarm_action_result"
        private const val EXTRA_DETAIL = "alarm_action_detail"
        private const val ACTION_TIMEOUT_MS = 10_000L
        private const val ALARM_SCREEN_OFF_CHANNEL_ID = "wmw_alarm_screen_off"
        private const val ALARM_NOTIFICATION_ID = 4_201
        private const val ALARM_SCREEN_OFF_NOTIFICATION_ID_ALT = 4_203
        private const val ALARM_RECOVERY_REQUEST_CODE = 4_202

        // Alternated on every SCREEN_OFF repost so notify() always targets an ID with no existing
        // record, instead of racing a cancel() on the same ID against the following notify().
        @Volatile
        private var lastScreenOffNotificationId = ALARM_SCREEN_OFF_NOTIFICATION_ID_ALT

        private fun alarmIntent(context: Context) =
            Intent(context, AlarmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        private fun alarmRecoveryIntent(context: Context) =
            Intent(context, AlarmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        private fun alarmActivityPendingIntent(context: Context, requestCode: Int, intent: Intent): PendingIntent {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val options = pendingIntentCreatorOptions()
            return if (options == null) {
                PendingIntent.getActivity(context, requestCode, intent, flags)
            } else {
                PendingIntent.getActivity(context, requestCode, intent, flags, options)
            }
        }

        private fun pendingIntentCreatorOptions(): Bundle? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
            return ActivityOptions.makeBasic()
                .setPendingIntentCreatorBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle()
        }

        private fun pendingIntentSenderOptions(): Bundle? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
            return ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle()
        }

        /**
         * Belt and braces: launching from this background service can be silently vetoed by
         * Android's background-activity-launch policy (confirmed on-device: a regular
         * notification's wake landing ~3s before an alarm, or the screen simply already being on,
         * makes the launch get "BAL_BLOCK"'d, with no exception and no screen/sound). The direct
         * launch goes through the same PendingIntent + MODE_BACKGROUND_ACTIVITY_START_ALLOWED
         * bypass already proven to work for the ACTION_SCREEN_ON recovery path, rather than a bare
         * startActivity(). A full-screen-intent notification is a second, independent launch path
         * for the same situation, so both are attempted together rather than one as a fallback to
         * the other's failure.
         */
        fun show(context: Context, session: AlarmSession): Result<Unit> {
            AlarmSessionStore.save(context, session)
            val directResult = runCatching {
                val pendingIntent = alarmActivityPendingIntent(
                    context,
                    session.eventId.hashCode(),
                    alarmIntent(context),
                )
                val options = pendingIntentSenderOptions()
                if (options == null) {
                    pendingIntent.send()
                } else {
                    pendingIntent.send(context, 0, null, null, null, null, options)
                }
            }
            val fsiResult = runCatching { postFullScreenSafetyNet(context, session) }
            val fsiDetail = fsiResult.getOrElse { "fsi_failed:${it.message ?: it.javaClass.simpleName}" }
            val directDetail = if (directResult.isSuccess) {
                "direct=ok"
            } else {
                "direct=failed:${directResult.exceptionOrNull()?.message ?: directResult.exceptionOrNull()?.javaClass?.simpleName}"
            }
            EventHistoryStore.add(context, "ALARM", "ALARM_LAUNCH_REQUESTED", "$directDetail $fsiDetail")
            if (directResult.isFailure && fsiResult.isFailure) {
                AlarmSessionStore.clear(context, session.eventId)
                return Result.failure(directResult.exceptionOrNull() ?: fsiResult.exceptionOrNull()!!)
            }
            return Result.success(Unit)
        }

        private fun postFullScreenSafetyNet(context: Context, session: AlarmSession): String {
            val postNotificationsGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            val canUseFsi = NotificationManagerCompat.from(context).canUseFullScreenIntent()
            if (!postNotificationsGranted) {
                return "fsi=skipped_no_post_notifications granted=$postNotificationsGranted canUseFsi=$canUseFsi"
            }
            // Muted: this notification now stays posted for the whole ring (see onCreate()/
            // onNewIntent() no longer cancelling it early), not just a brief flash — it must not
            // buzz on top of the alarm tone already playing via AlarmAlertController.
            ensureScreenOffNotificationChannel(context)
            val contentIntent = alarmActivityPendingIntent(
                context,
                session.eventId.hashCode(),
                alarmIntent(context),
            )
            val notification = NotificationCompat.Builder(context, ALARM_SCREEN_OFF_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_wmw_alarm_24)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true)
                .setAutoCancel(false)
                .setSilent(true)
                .setContentIntent(contentIntent)
                .setFullScreenIntent(contentIntent, true)
                .build()
            NotificationManagerCompat.from(context).notify(ALARM_NOTIFICATION_ID, notification)
            return "fsi=posted granted=$postNotificationsGranted canUseFsi=$canUseFsi"
        }

        /** Cancels both possible screen-off-repost notification IDs, whichever is currently active. */
        private fun cancelAlarmNotifications(context: Context) {
            NotificationManagerCompat.from(context).apply {
                cancel(ALARM_NOTIFICATION_ID)
                cancel(ALARM_SCREEN_OFF_NOTIFICATION_ID_ALT)
            }
        }

        /**
         * Re-post the full-screen-intent notification once, right as the screen turns off while
         * an alarm is ringing (on top of it already staying posted for the whole ring since
         * postFullScreenSafetyNet() — this repost is what lets a *second* screen-off/on cycle
         * during the same ring get a fresh FSI attempt too). Reuses the same PendingIntent
         * identity as the working reorder-to-front recovery so a successful FSI launch lands on
         * the same singleTask instance via onNewIntent().
         *
         * Alternates notification ID on every call instead of cancel()-then-notify() on the same
         * ID: cancel()/notify() are separate, non-synchronous IPC calls into the system service,
         * so a same-ID notify() can land before the preceding cancel() is fully processed and get
         * coalesced into an update of the still-existing record — and only a genuinely fresh post
         * is documented to (re-)trigger the full-screen-intent auto-launch.
         */
        private fun postScreenOffFullScreenNotification(context: Context, session: AlarmSession): Boolean {
            val postNotificationsGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!postNotificationsGranted) return false
            ensureScreenOffNotificationChannel(context)
            val contentIntent = alarmActivityPendingIntent(
                context,
                ALARM_RECOVERY_REQUEST_CODE,
                alarmRecoveryIntent(context),
            )
            val notification = NotificationCompat.Builder(context, ALARM_SCREEN_OFF_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_wmw_alarm_24)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true)
                .setAutoCancel(false)
                .setSilent(true)
                .setContentIntent(contentIntent)
                .setFullScreenIntent(contentIntent, true)
                .build()
            val manager = NotificationManagerCompat.from(context)
            val previousId = lastScreenOffNotificationId
            val newId = if (previousId == ALARM_NOTIFICATION_ID) ALARM_SCREEN_OFF_NOTIFICATION_ID_ALT else ALARM_NOTIFICATION_ID
            lastScreenOffNotificationId = newId
            manager.notify(newId, notification)
            manager.cancel(previousId)
            return true
        }

        private fun ensureScreenOffNotificationChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(ALARM_SCREEN_OFF_CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                ALARM_SCREEN_OFF_CHANNEL_ID,
                context.getString(R.string.alarm_notification_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            )
            channel.setSound(null, null)
            channel.enableVibration(false)
            manager.createNotificationChannel(channel)
        }

        fun close(context: Context, eventId: String) {
            AlarmAlertController.stop(context, "close_request")
            AlarmSessionStore.clear(context, eventId)
            // The Activity's own onDestroy() also cancels these, but it may not be alive to
            // receive ACTION_CLOSE at all (e.g. its process was killed) — cancel here too.
            cancelAlarmNotifications(context)
            context.sendBroadcast(
                Intent(ACTION_CLOSE)
                    .setPackage(context.packageName)
                    .putExtra(EXTRA_EVENT_ID, eventId),
            )
        }

        fun reportActionResult(
            context: Context,
            requestId: String,
            alarmEventId: String,
            result: String,
            detail: String,
        ) {
            context.sendBroadcast(
                Intent(ACTION_RESULT)
                    .setPackage(context.packageName)
                    .putExtra(EXTRA_REQUEST_ID, requestId)
                    .putExtra(EXTRA_EVENT_ID, alarmEventId)
                    .putExtra(EXTRA_RESULT, result)
                    .putExtra(EXTRA_DETAIL, detail),
            )
        }
    }
}

private enum class AlarmActionStatus { IDLE, SENDING, CONFIRMED, ERROR }

private data class AlarmActionUiState(
    val status: AlarmActionStatus = AlarmActionStatus.IDLE,
    val action: String? = null,
    val detail: String? = null,
)

@Composable
private fun AlarmScreen(
    session: AlarmSession,
    actionState: AlarmActionUiState,
    onAction: (String) -> Unit,
) {
    val context = LocalContext.current
    val controlsEnabled = actionState.status != AlarmActionStatus.SENDING
    val timeText = remember(session.postedAt, session.title) { alarmTimeLabel(context, session) }
    val subtitle = remember(session.title, session.text) { alarmSubtitle(context, session) }

    MaterialTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(start = 24.dp, top = 18.dp, end = 24.dp, bottom = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AlarmIcon()
            Spacer(Modifier.height(12.dp))
            Text(
                text = timeText,
                modifier = Modifier.fillMaxWidth(),
                color = Color.White,
                fontSize = 40.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = subtitle,
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFFC8CDD8),
                fontSize = 16.sp,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )

            Spacer(Modifier.weight(1f))

            when (actionState.status) {
                AlarmActionStatus.SENDING -> Spacer(Modifier.height(14.dp))
                AlarmActionStatus.ERROR -> Text(
                    text = buildString {
                        append(stringResource(R.string.alarm_action_failed))
                        actionState.detail?.takeIf { it.isNotBlank() }?.let {
                            append("\n")
                            append(it)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    color = Color(0xFFFF9DAE),
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
                else -> Spacer(Modifier.height(14.dp))
            }

            Spacer(Modifier.height(10.dp))
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AlarmRoundActionButton(
                    iconRes = R.drawable.ic_wmw_close_24,
                    background = Color(0xFF202127),
                    content = Color.White,
                    enabled = controlsEnabled && session.canDismiss,
                    onClick = { onAction("DISMISS") },
                )
                Spacer(Modifier.width(28.dp))
                AlarmRoundActionButton(
                    iconRes = R.drawable.ic_wmw_snooze_24,
                    background = Color(0xFF2F80ED),
                    content = Color.White,
                    enabled = controlsEnabled && session.canSnooze,
                    onClick = { onAction("SNOOZE") },
                )
            }
        }
    }
}

@Composable
private fun AlarmIcon() {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(Color(0xFF4E6CF6)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_wmw_alarm_24),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(25.dp),
        )
    }
}

@Composable
private fun AlarmRoundActionButton(
    iconRes: Int,
    background: Color,
    content: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .width(58.dp)
            .height(50.dp)
            .clip(RoundedCornerShape(25.dp))
            .background(background)
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(27.dp),
        )
    }
}

private fun alarmTimeLabel(context: Context, session: AlarmSession): String {
    val title = session.title.trim()
    if (title.matches(Regex("""\d{1,2}[:.]\d{2}.*"""))) return title.take(16)
    return android.text.format.DateFormat.getTimeFormat(context).format(Date(session.postedAt))
}

private fun alarmSubtitle(context: Context, session: AlarmSession): String {
    val cleanedText = session.text.trim().takeUnless { it.isBlank() || it.isGoogleClockSwipeInstruction() }
    if (cleanedText != null) return cleanedText.take(44)
    val title = session.title.trim()
    val genericTitles = setOf("alarm", "google clock", "будильник", "часы", "clock")
    if (title.isNotBlank() && title.lowercase(Locale.ROOT) !in genericTitles && !title.matches(Regex("""\d{1,2}[:.]\d{2}.*"""))) {
        return title.take(44)
    }
    return context.getString(R.string.alarm_signal)
}

private fun String.isGoogleClockSwipeInstruction(): Boolean {
    val normalized = lowercase(Locale.ROOT)
    return listOf(
        "провед",
        "смах",
        "swipe",
        "slide",
        "stop by swiping",
        "чтобы остановить",
        "чтобы отключить",
    ).any { normalized.contains(it) }
}
