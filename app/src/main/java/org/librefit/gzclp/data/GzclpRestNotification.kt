/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.librefit.gzclp.data

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import org.librefit.R
import org.librefit.activities.MainActivity
import org.librefit.db.entity.GzclpSessionEntity
import org.librefit.db.repository.GzclpRepository
import org.librefit.gzclp.domain.RestDeadline

/** A timestamp notification and inexact alarm; no foreground service or exact alarm permission. */
class GzclpRestNotification @Inject constructor(@ApplicationContext private val context: Context) {
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val channel = "gzclp_rest"
    private val doneChannel = "gzclp_rest_done"

    init {
        manager.createNotificationChannel(NotificationChannel(channel, "GZCLP rest timer", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(doneChannel, "GZCLP rest complete", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun action(sessionId: Long, name: String): PendingIntent = PendingIntent.getBroadcast(context,
        (sessionId * 7 + name.hashCode()).toInt(),
        Intent(context, GzclpTimerReceiver::class.java).setAction(name).putExtra("sessionId", sessionId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun allowed(): Boolean = manager.areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    /** Notification permission and credential storage may settle after the first boot broadcast. */
    fun scheduleBootRefresh() {
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + 15_000L, action(0, "refresh"))
    }

    @SuppressLint("MissingPermission")
    fun show(session: GzclpSessionEntity) {
        val remaining = RestDeadline(session.restDeadlineMillis, session.restPausedMillis)
            .remaining(System.currentTimeMillis())
        if (session.status != "ACTIVE") { cancel(session.id); return }
        if (remaining == 0L) {
            if (session.restDeadlineMillis > 0L && session.restPausedMillis == 0L) finished(session)
            else cancel(session.id)
            return
        }
        alarms.cancel(action(session.id, "end"))
        if (session.restPausedMillis == 0L) alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
            session.restDeadlineMillis, action(session.id, "end"))
        if (!allowed()) return
        val open = PendingIntent.getActivity(context, session.id.toInt(),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification_monochrome)
            .setContentTitle("GZCLP rest")
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(false)
            .setSilent(true)
            .addAction(0, "−15s", action(session.id, "minus"))
            .addAction(0, "+15s", action(session.id, "plus"))
            .addAction(0, "Skip", action(session.id, "skip"))
        if (session.restPausedMillis > 0) builder
            .setContentText("Paused · ${remaining / 1000}s left")
            .addAction(0, "Resume", action(session.id, "resume"))
        else builder.setWhen(session.restDeadlineMillis).setUsesChronometer(true)
            .setChronometerCountDown(true)
            .addAction(0, "Pause", action(session.id, "pause"))
        manager.notify(notificationId(session.id), builder.build())
    }

    @SuppressLint("MissingPermission")
    fun finished(session: GzclpSessionEntity) {
        cancel(session.id)
        if (!allowed()) return
        manager.notify(notificationId(session.id), NotificationCompat.Builder(context, doneChannel)
            .setSmallIcon(R.drawable.ic_notification_monochrome)
            .setContentTitle("Rest complete")
            .setContentText("Ready for the next GZCLP set")
            .setAutoCancel(true).build())
    }

    fun cancel(sessionId: Long) {
        alarms.cancel(action(sessionId, "end"))
        manager.cancel(notificationId(sessionId))
    }

    private fun notificationId(sessionId: Long) = 20_000 + (sessionId % 10_000).toInt()
}

@AndroidEntryPoint
class GzclpTimerReceiver : BroadcastReceiver() {
    @Inject lateinit var repository: GzclpRepository
    @Inject lateinit var notification: GzclpRestNotification

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("sessionId", -1)
        if (id <= 0 && intent.action != "refresh") return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (intent.action == "refresh") {
                    repository.activeSession.first()?.session?.let(notification::show)
                } else if (intent.action == "end") {
                    val session = repository.getSession(id)?.session
                    if (session != null && session.status == "ACTIVE" &&
                        session.restDeadlineMillis > 0 && session.restDeadlineMillis <= System.currentTimeMillis())
                        notification.finished(session)
                } else {
                    val updated = repository.changeRest(id, intent.action.orEmpty())
                    if (updated != null) notification.show(updated)
                }
            } finally { pending.finish() }
        }
    }
}

/** AlarmManager clears alarms across reboot; restore only a still-active persisted deadline. */
@AndroidEntryPoint
class GzclpBootReceiver : BroadcastReceiver() {
    @Inject lateinit var repository: GzclpRepository
    @Inject lateinit var notification: GzclpRestNotification

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_USER_UNLOCKED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                notification.scheduleBootRefresh()
                repository.activeSession.first()?.session?.let { session ->
                    if (session.restDeadlineMillis > 0 || session.restPausedMillis > 0)
                        notification.show(session)
                }
            } finally { pending.finish() }
        }
    }
}
