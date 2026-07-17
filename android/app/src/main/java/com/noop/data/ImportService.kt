package com.noop.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground keep-alive for long-running imports/restores (dataSync type).
 *
 * Why this exists: imports used to run as plain coroutines in the *screen's* Compose scope. The
 * moment the user switched apps or slept the screen, Android 14's cached-app freezer suspended
 * every thread mid-flight (observed live: "quick sync unfreeze <pid>" on return — zero progress
 * made while away), and an aggressive OEM killer could reclaim the process mid-write. For a large
 * WHOOP/Apple Health export that's minutes of work the user will absolutely background through.
 *
 * Design:
 *  - Work executes in a process-wide [SupervisorJob] scope owned by the companion — NOT the
 *    service's lifecycle and NOT the screen's. A screen that dies (swipe-from-recents kills the
 *    task/activity) no longer cancels a half-applied import; the UI simply misses the completion
 *    callback, which the screens already treat as transient (`busy` is deliberately not persisted).
 *  - The service itself is a pure keep-alive shell: while at least one import runs, we hold a
 *    dataSync foreground notification so the freezer and LMK leave the process alone. Last job
 *    out stops the service.
 *  - Screens call [run] and `await()` the [Deferred] from their own scope, so all existing local
 *    state (spinners, toasts, count refreshes) works unchanged when the screen stays alive.
 *
 * Android 15 caps dataSync at ~6h ([onTimeout]); an import that long has bigger problems — we
 * just drop the notification and let the job finish unprotected.
 */
class ImportService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Data import", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must reach startForeground() within 5s of startForegroundService(); do it first, always —
        // even if the job already finished (tiny race), then immediately stop.
        val tap = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(applicationInfo.icon)
            .setContentTitle(label.value ?: "Importing data")
            .setContentText("Keep NOOP running; you can switch apps.")
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setContentIntent(tap)
            .build()
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (active.get() == 0) stopSelf(startId)
        return START_NOT_STICKY
    }

    // Android 15+: dataSync budget exhausted. Drop FGS; the companion-scope job keeps running
    // unprotected (may re-freeze in background — better than an ANR-style crash here).
    override fun onTimeout(startId: Int) {
        stopSelf(startId)
    }

    companion object {
        private const val CHANNEL_ID = "import"
        private const val NOTIF_ID = 41

        private val active = AtomicInteger(0)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Title shown in the notification while an import runs; null when idle. */
        private val label = MutableStateFlow<String?>(null)

        /**
         * Execute [block] on the service-owned IO scope, holding a dataSync foreground service
         * for the duration. Callers `await()` the result from their own (screen) scope; if that
         * scope dies, [block] still runs to completion.
         */
        fun <T> run(context: Context, title: String, block: suspend () -> T): Deferred<T> {
            val app = context.applicationContext
            if (active.incrementAndGet() == 1) {
                label.value = title
                ContextCompat.startForegroundService(app, Intent(app, ImportService::class.java))
            }
            return scope.async {
                try {
                    block()
                } finally {
                    if (active.decrementAndGet() == 0) {
                        label.value = null
                        app.stopService(Intent(app, ImportService::class.java))
                    }
                }
            }
        }
    }
}
