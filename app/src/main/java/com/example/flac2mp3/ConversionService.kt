package com.example.flac2mp3

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Runs the queue as a foreground service so long batches survive the screen turning off. */
class ConversionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var worker: Job? = null
    private var lastNotifiedPercent = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "Conversion", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            val w = worker
            if (w == null) stopSelf() else w.cancel()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, buildNotification("Preparing…", null, ongoing = true),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            )
        } catch (e: Exception) {
            ConverterState.failAll("Could not start the conversion service: ${describe(e)}")
            stopSelf()
            return START_NOT_STICKY
        }
        if (worker?.isActive != true) worker = scope.launch { runQueue() }
        return START_NOT_STICKY
    }

    private suspend fun runQueue() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "flactranscoder:convert")
        wakeLock.acquire(6 * 60 * 60 * 1000L)

        ConverterState.setRunning(true)
        ConverterState.requeueUnfinished()
        var done = 0
        var failed = 0
        var cancelled = false
        try {
            // Settings are fixed for the whole run; the UI locks them while running.
            val snapshot = ConverterState.state.value
            val format = snapshot.format
            val split = snapshot.split
            val outDir = snapshot.outputDir?.let { DocumentFile.fromTreeUri(this, it) }
            if (outDir == null || !outDir.canWrite()) {
                ConverterState.failAll("Output folder is not writable. Choose it again.")
                return
            }
            val queue = snapshot.items.filter { it.status != Status.DONE }
            queue.forEachIndexed { index, item ->
                ConverterState.update(item.id) {
                    it.copy(status = Status.RUNNING, progress = 0f, detail = null, error = null)
                }
                lastNotifiedPercent = -1
                val title = "File ${index + 1} of ${queue.size}"
                notify(title, item.name, null)
                try {
                    val summary = Converter.convert(this, item, outDir, format, split) { p, detail ->
                        ConverterState.update(item.id) { it.copy(progress = p, detail = detail) }
                        val pct = ((p ?: 0f) * 100).toInt()
                        if (pct != lastNotifiedPercent) {
                            lastNotifiedPercent = pct
                            notify(title, detail?.let { "${item.name}, $it" } ?: item.name, if (p == null) null else pct)
                        }
                    }
                    ConverterState.update(item.id) {
                        it.copy(status = Status.DONE, progress = 1f, detail = summary)
                    }
                    done++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // Throwable on purpose: a native-library failure is an Error, not an Exception.
                    failed++
                    ConverterState.update(item.id) {
                        it.copy(status = Status.FAILED, detail = null, error = describe(e))
                    }
                }
            }
        } catch (e: CancellationException) {
            cancelled = true
            ConverterState.cancelPending()
        } catch (e: Throwable) {
            ConverterState.failAll(describe(e))
        } finally {
            ConverterState.setRunning(false)
            if (wakeLock.isHeld) wakeLock.release()
            stopForeground(STOP_FOREGROUND_REMOVE)
            val summary = when {
                cancelled -> "Cancelled"
                failed > 0 -> "$done finished, $failed failed"
                else -> "$done finished"
            }
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID + 1, buildNotification("Conversion finished", summary, ongoing = false))
            stopSelf()
        }
    }

    private fun describe(e: Throwable): String = when (e) {
        is UnsatisfiedLinkError, is NoClassDefFoundError, is ExceptionInInitializerError ->
            "FFmpeg could not load on this device. This build supports arm64-v8a; " +
                "this device reports ${Build.SUPPORTED_ABIS.joinToString()}. ${e.message.orEmpty()}".trim()
        else -> e.message ?: e::class.java.simpleName
    }

    private fun notify(title: String, text: String?, percent: Int?) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(title, text, ongoing = true, percent = percent))
    }

    private fun buildNotification(
        title: String,
        text: String?,
        ongoing: Boolean,
        percent: Int? = null,
    ): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setAutoCancel(!ongoing)
        if (ongoing) {
            val cancel = PendingIntent.getService(
                this, 1, Intent(this, ConversionService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            b.addAction(0, "Cancel", cancel)
            b.setProgress(100, percent ?: 0, percent == null)
        }
        return b.build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_CANCEL = "com.example.flac2mp3.CANCEL"
        private const val CHANNEL_ID = "convert"
        private const val NOTIF_ID = 1
    }
}
