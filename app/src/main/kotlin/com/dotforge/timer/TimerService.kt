package com.dotforge.timer

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.dotforge.R
import com.dotforge.device.TimerFaces
import com.dotforge.live.GlyphTimer
import com.dotforge.live.TimerShown
import com.dotforge.live.Timers
import com.dotforge.ui.MainActivity

/**
 * Keeps DotForge alive while one of its own timers or the stopwatch is going (a foreground service), posts the
 * timer's Live Update (a promoted ongoing notification on Android 16: the status-bar chip and the lock screen),
 * and wakes the phone when the next timer ends, so the burst starts on time even in Doze.
 */
class TimerService : Service() {
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        GlyphTimers.onChange = { update(it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val state = GlyphTimers.state.value
        val started = runCatching {
            startForeground(NOTIFICATION_ID, TimerNotifications.ongoing(this, state, GlyphTimers.now()), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        }.isSuccess
        if (!started || !state.ours) {
            stopSelf()
            return START_NOT_STICKY
        }
        update(state)
        return START_STICKY
    }

    private fun update(state: GlyphTimers.State) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (!state.ours) {
            nm.cancel(DONE_NOTIFICATION_ID)
            release()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val now = GlyphTimers.now()
        nm.notify(NOTIFICATION_ID, TimerNotifications.ongoing(this, state, now))
        val done = state.timers.filter { it.over(now) }
        if (done.isNotEmpty()) {
            nm.notify(DONE_NOTIFICATION_ID, TimerNotifications.timesUp(this, done))
            hold()
        } else {
            nm.cancel(DONE_NOTIFICATION_ID)
            release()
        }
        schedule(Timers.nextEnd(state.timers))
    }

    /** The burst plays until dismissed: keep the CPU awake for it (at most 10 minutes). */
    private fun hold() {
        if (wake?.isHeld == true) return
        wake = getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DotForge:timer")?.apply {
            setReferenceCounted(false)
            acquire(10 * 60_000L)
        }
    }

    private fun release() {
        wake?.takeIf { it.isHeld }?.release()
        wake = null
    }

    /** An exact wake-up when the next timer ends (inexact when Android doesn't allow exact alarms). */
    private fun schedule(at: Long?) {
        val am = getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(this, 0, Intent(this, TimerReceiver::class.java).setAction(TimerReceiver.WAKE), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        if (at == null) { am.cancel(pi); return }
        runCatching {
            if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    override fun onDestroy() {
        if (GlyphTimers.onChange != null) GlyphTimers.onChange = null
        release()
        super.onDestroy()
    }

    companion object {
        const val NOTIFICATION_ID = 21
        const val DONE_NOTIFICATION_ID = 22

        /** Starts the service if it is not running (from the app, or from a notification action). */
        fun ensure(context: Context) {
            runCatching { context.startForegroundService(Intent(context, TimerService::class.java)) }
        }
    }
}

/** The timer's notifications: the ongoing Live Update and "Time's up". */
object TimerNotifications {
    const val CHANNEL = "timers"
    const val DONE_CHANNEL = "timers_done"
    /** Android 16's request for a promoted ongoing notification (a Live Update). */
    private const val EXTRA_REQUEST_PROMOTED = "android.requestPromotedOngoing"

    private fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Timers and stopwatch", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(DONE_CHANNEL, "Timer ended", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun open(ctx: Context) = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_FEATURE, "timer").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun action(ctx: Context, label: String, act: String, id: String?, code: Int): Notification.Action {
        val pi = PendingIntent.getBroadcast(
            ctx, code, Intent(ctx, TimerReceiver::class.java).setAction(act).putExtra(TimerReceiver.EXTRA_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(null, label, pi).build()
    }

    /** What our own timers and stopwatch are doing, as one ongoing Live Update. */
    fun ongoing(ctx: Context, s: GlyphTimers.State, now: Long): Notification {
        channels(ctx)
        val ours = s.timers
        val b = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.toy_timer)
            .setContentIntent(open(ctx))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STOPWATCH)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        b.extras.putBoolean(EXTRA_REQUEST_PROMOTED, true)
        val shown = Timers.shown(ours, s.stopwatch.takeIf { it.started }, now)
        val running = ours.count { it.running && !it.over(now) }
        when (shown) {
            is TimerShown.Running -> {
                val t = shown.timer
                b.setContentTitle(TimerFaces.clock(shown.leftMs) + " left")
                    .setContentText(t.name + if (shown.others > 0) " · ${shown.others} more running" else "")
                    .setUsesChronometer(true).setChronometerCountDown(true).setWhen(t.endsAt).setShowWhen(true)
                    .addAction(action(ctx, "Pause", TimerReceiver.PAUSE, t.id, 1))
                    .addAction(action(ctx, "+1 min", TimerReceiver.PLUS, t.id, 2))
                    .addAction(action(ctx, "Stop", TimerReceiver.CANCEL, t.id, 3))
                // the status-bar chip: "4 min", then seconds in the last minute
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) b.setShortCriticalText(TimerFaces.middle(shown.leftMs) + if (shown.leftMs > 60_000) " min" else " s")
                progress(b, t.totalMs - shown.leftMs, t.totalMs)
            }
            is TimerShown.Paused -> {
                val t = shown.timer
                b.setContentTitle("Paused · " + TimerFaces.clock(shown.leftMs) + " left")
                    .setContentText(t.name)
                    .setShowWhen(false)
                    .addAction(action(ctx, "Resume", TimerReceiver.RESUME, t.id, 1))
                    .addAction(action(ctx, "Stop", TimerReceiver.CANCEL, t.id, 3))
                progress(b, t.totalMs - shown.leftMs, t.totalMs)
            }
            is TimerShown.Done -> {
                b.setContentTitle("Time's up").setContentText(shown.timer.name + if (running > 0) " · $running more running" else "")
                    .addAction(action(ctx, "Dismiss", TimerReceiver.DISMISS, shown.timer.id, 1))
                    .addAction(action(ctx, "+1 min", TimerReceiver.PLUS, shown.timer.id, 2))
            }
            is TimerShown.Watch -> {
                val w = shown.stopwatch
                b.setContentTitle(if (w.running) "Stopwatch" else "Stopwatch paused · " + TimerFaces.clock(shown.elapsedMs, roundUp = false))
                    .setContentText(if (w.laps.isEmpty()) "DotForge" else "Lap ${w.laps.size + 1}")
                if (w.running) {
                    b.setUsesChronometer(true).setWhen(now - shown.elapsedMs).setShowWhen(true)
                        .addAction(action(ctx, "Pause", TimerReceiver.WATCH_PAUSE, null, 4))
                        .addAction(action(ctx, "Lap", TimerReceiver.WATCH_LAP, null, 5))
                } else {
                    b.addAction(action(ctx, "Resume", TimerReceiver.WATCH_START, null, 4))
                        .addAction(action(ctx, "Reset", TimerReceiver.WATCH_RESET, null, 5))
                }
            }
            null -> b.setContentTitle("Timer").setContentText("Nothing running")
        }
        return b.build()
    }

    /** Android 16: a ProgressStyle (the Live Update's bar); before it, the classic bar. */
    private fun progress(b: Notification.Builder, done: Long, total: Long) {
        val max = 1000
        val now = ((done.toDouble() / total.coerceAtLeast(1)) * max).toInt().coerceIn(0, max)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            b.setStyle(Notification.ProgressStyle().setProgressSegments(listOf(Notification.ProgressStyle.Segment(max))).setProgress(now).setStyledByProgress(true))
        } else b.setProgress(max, now, false)
    }

    /** "Time's up": its own high-importance notification, so the phone sounds once. */
    fun timesUp(ctx: Context, done: List<GlyphTimer>): Notification {
        channels(ctx)
        val first = done.maxBy { it.doneAt }
        return Notification.Builder(ctx, DONE_CHANNEL)
            .setSmallIcon(R.drawable.toy_timer)
            .setContentTitle("Time's up")
            .setContentText(if (done.size == 1) first.name else "${done.size} timers ended")
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(open(ctx))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(action(ctx, if (done.size == 1) "Dismiss" else "Dismiss all", TimerReceiver.DISMISS_ALL, null, 6))
            .addAction(action(ctx, "+1 min", TimerReceiver.PLUS, first.id, 7))
            .build()
    }
}

/** Notification buttons and the wake-up alarm. */
class TimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID)
        when (intent.action) {
            WAKE -> GlyphTimers.tick()
            PAUSE -> id?.let(GlyphTimers::pause)
            RESUME -> id?.let(GlyphTimers::resume)
            PLUS -> id?.let(GlyphTimers::plusMinute)
            CANCEL, DISMISS -> id?.let(GlyphTimers::dismiss)
            DISMISS_ALL -> GlyphTimers.dismissAllDone()
            WATCH_START -> GlyphTimers.stopwatchStart()
            WATCH_PAUSE -> GlyphTimers.stopwatchPause()
            WATCH_LAP -> GlyphTimers.stopwatchLap()
            WATCH_RESET -> GlyphTimers.stopwatchReset()
        }
    }

    companion object {
        const val EXTRA_ID = "id"
        const val WAKE = "com.dotforge.timer.WAKE"
        const val PAUSE = "com.dotforge.timer.PAUSE"
        const val RESUME = "com.dotforge.timer.RESUME"
        const val PLUS = "com.dotforge.timer.PLUS"
        const val CANCEL = "com.dotforge.timer.CANCEL"
        const val DISMISS = "com.dotforge.timer.DISMISS"
        const val DISMISS_ALL = "com.dotforge.timer.DISMISS_ALL"
        const val WATCH_START = "com.dotforge.timer.WATCH_START"
        const val WATCH_PAUSE = "com.dotforge.timer.WATCH_PAUSE"
        const val WATCH_LAP = "com.dotforge.timer.WATCH_LAP"
        const val WATCH_RESET = "com.dotforge.timer.WATCH_RESET"
    }
}
