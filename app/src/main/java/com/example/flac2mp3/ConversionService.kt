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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/** Runs the queue as a foreground service so long batches survive the screen turning off. */
class ConversionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var worker: Job? = null

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
        val done = AtomicInteger(0)
        val failed = AtomicInteger(0)
        var cancelled = false
        try {
            Converter.clearTemp(this)
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
            val ids = queue.map { it.id }.toSet()
            val lastNotified = AtomicInteger(-1)

            // One notification for the whole batch, refreshed only when its numbers change.
            fun notifyOverall() {
                val items = ConverterState.state.value.items.filter { it.id in ids }
                val finished = done.get() + failed.get()
                val running = items.filter { it.status == Status.RUNNING }.sumOf { (it.progress ?: 0f).toDouble() }
                val pct = (((finished + running) / queue.size) * 100).toInt().coerceIn(0, 100)
                if (lastNotified.getAndSet(finished * 101 + pct) != finished * 101 + pct) {
                    notify("$finished of ${queue.size} files done", null, pct)
                }
            }

            suspend fun convertOne(item: Item) {
                ConverterState.update(item.id) {
                    it.copy(status = Status.RUNNING, progress = 0f, detail = null, error = null)
                }
                // FFmpeg reports progress many times a second, from several threads.
                // Only a changed percentage or detail line reaches the UI.
                val gate = Any()
                var lastPct = -2
                var lastDetail: String? = null
                try {
                    val summary = Converter.convert(this, item, outDir, format, split) { p, detail ->
                        val pct = p?.let { (it * 100).toInt() } ?: -1
                        val changed = synchronized(gate) {
                            if (pct != lastPct || detail != lastDetail) {
                                lastPct = pct
                                lastDetail = detail
                                true
                            } else false
                        }
                        if (changed) {
                            ConverterState.update(item.id) {
                                if (it.status == Status.RUNNING) it.copy(progress = p, detail = detail) else it
                            }
                            notifyOverall()
                        }
                    }
                    ConverterState.update(item.id) {
                        it.copy(status = Status.DONE, progress = 1f, detail = summary)
                    }
                    done.incrementAndGet()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // Throwable on purpose: a native-library failure is an Error, not an Exception.
                    failed.incrementAndGet()
                    ConverterState.update(item.id) {
                        it.copy(status = Status.FAILED, detail = null, error = describe(e))
                    }
                }
                notifyOverall()
            }

            notifyOverall()
            // A few files at a time. Converter caps the number of encodes actually running.
            val next = AtomicInteger(0)
            coroutineScope {
                repeat(minOf(Converter.parallelism, queue.size)) {
                    launch {
                        while (true) {
                            val item = queue.getOrNull(next.getAndIncrement()) ?: break
                            convertOne(item)
                        }
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
                failed.get() > 0 -> "${done.get()} finished, ${failed.get()} failed"
                else -> "${done.get()} finished"
            }
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID + 1, buildNotification("Conversion finished", summary, ongoing = false))
            stopSelf()
        }
    }

    private fun describe(e: Throwable): String = when (e) {
        is UnsatisfiedLinkError ->
            "FFmpeg's native library could not load. This build supports arm64-v8a; " +
                "this device reports ${Build.SUPPORTED_ABIS.joinToString()}. ${e.message.orEmpty()}".trim()
        is LinkageError ->
            "FFmpeg could not start (${e::class.java.simpleName}): ${e.message.orEmpty()}".trim()
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
