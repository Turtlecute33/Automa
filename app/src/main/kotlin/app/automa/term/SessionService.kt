package app.automa.term

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import app.automa.MainActivity
import app.automa.R
import app.automa.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Keeps the app process and the CPU on while a terminal session or a chat is open, or a job runs (DESIGN §4), so that opencode
 * continues with the screen off. A foreground service also keeps the network: from Android 15, the system blocks the
 * network of an app in the background a few seconds after the user leaves it (research F-9). The notification shows
 * the number of sessions and has a Stop action.
 */
class SessionService : Service() {
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    // The lock is held while a session runs, for any time; onDestroy releases it.
    @SuppressLint("WakelockTimeout")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            app.sessions.closeAll()
            app.chats.closeAll()
        }
        val count = open(this)
        // Always first: a foreground start must call startForeground, also when it stops at once.
        startForeground(ID, notification(count), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (count == 0 && jobs == 0) {
            running = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (wake == null) {
            wake = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "automa:sessions").apply { acquire() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wake?.release()
        wake = null
        running = false
        super.onDestroy()
    }

    private fun notification(count: Int): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Sessions", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows while a terminal session is open"
                setShowBadge(false)
            },
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SessionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(
                when (count) {
                    0 -> "Automa is working"
                    1 -> "1 session open"
                    else -> "$count sessions open"
                },
            )
            .setContentText("The processes continue with the screen off.")
            .setContentIntent(open)
            .setOngoing(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .apply {
                // Not from the lock screen: a person with the phone must not stop a running job (review SEC-10).
                if (count > 0) addAction(Notification.Action.Builder(null, "Stop all", stop).setAuthenticationRequired(true).build())
            }
            .build()
    }

    companion object {
        private const val ID = 1
        private const val CHANNEL = "sessions"
        private const val ACTION_STOP = "app.automa.STOP"
        private const val ACTION_SYNC = "app.automa.SYNC"
        // Main thread only, like the session callbacks.
        private var running = false
        private var jobs = 0

        /**
         * The terminal sessions and chats that run. A tab that ended with an error and a chat whose agent ended stay on
         * the screen, but they must not keep the service and the wake lock.
         */
        private fun open(context: Context) = context.app.run { sessions.tabs.value.count { it.exit == null } + chats.running() }

        /** Shows the number of sessions. The first call starts the service from the UI (a foreground start). */
        fun sync(context: Context) {
            val intent = Intent(context, SessionService::class.java).setAction(ACTION_SYNC)
            when {
                running -> context.startService(intent)
                jobs > 0 || open(context) > 0 -> {
                    // Android refuses a foreground start from the background. The job then runs with no service.
                    running = runCatching { context.startForegroundService(intent) }.isSuccess
                }
            }
        }

        /**
         * Runs [block] with the service in the foreground: a setup, an update, a login or a clone continues when the
         * user goes to another app (for example to the browser for the GitHub login). Start it from a user action.
         */
        suspend fun <T> keep(context: Context, block: suspend () -> T): T {
            withContext(Dispatchers.Main.immediate) {
                jobs++
                sync(context)
            }
            try {
                return block()
            } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    jobs--
                    sync(context)
                }
            }
        }
    }
}
