package com.example.imageedit.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.imageedit.MainActivity
import com.example.imageedit.core.edit.EditSession
import com.example.imageedit.core.edit.SessionStore
import com.example.imageedit.core.export.ExportFormat
import com.example.imageedit.core.export.ExportPipeline
import com.example.imageedit.core.export.ExportProgress
import com.example.imageedit.core.image.ColorPipeline
import com.example.imageedit.core.image.ImageContext
import com.example.imageedit.core.image.MemoryBudget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * dataSync foreground service for exports. Keeps the process alive while the
 * user backgrounds the app and reports progress through [ExportStatusBus].
 */
class ExportService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var exportJob: Job? = null
    private var fullContext: ImageContext? = null

    @Volatile
    private var completed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "图片导出",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "显示图片导出进度"
                },
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            completed = true
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent.action == ACTION_CANCEL) {
            val job = exportJob
            if (job?.isActive == true) {
                job.cancel()
            } else {
                completed = true
                runCatching {
                    getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
                }
                stopSelf()
            }
            return START_NOT_STICKY
        }

        val sourceUri = intent.getStringExtra(EXTRA_SOURCE_URI)
        val targetUri = intent.getStringExtra(EXTRA_TARGET_URI)
        val formatName = intent.getStringExtra(EXTRA_FORMAT)
        val instruction = intent.getStringExtra(EXTRA_INSTRUCTION).orEmpty()
        if (sourceUri.isNullOrBlank() || targetUri.isNullOrBlank()) {
            ExportStatusBus.failed("导出参数不完整")
            stopSelf()
            return START_NOT_STICKY
        }
        val format = ExportFormat.entries.firstOrNull { it.name == formatName }
            ?: ExportFormat.JPEG

        completed = false
        try {
            startForegroundCompat(notification("准备导出…", indeterminate = true))
        } catch (failure: Throwable) {
            completed = true
            ExportStatusBus.failed("无法启动导出服务：${failure.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        ExportStatusBus.running("准备导出…")

        exportJob = scope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    SessionStore(this@ExportService).load()
                }
                val history = saved?.invocations.orEmpty()

                val full = try {
                    withContext(Dispatchers.IO) {
                        ColorPipeline.loadFullBudgeted(
                            this@ExportService,
                            Uri.parse(sourceUri),
                            MemoryBudget.MAX_EXPORT_EDGE,
                        )
                    }
                } catch (oom: OutOfMemoryError) {
                    System.gc()
                    ExportStatusBus.running("内存不足，降低原图分辨率…")
                    withContext(Dispatchers.IO) {
                        ColorPipeline.loadFullBudgeted(
                            this@ExportService,
                            Uri.parse(sourceUri),
                            MemoryBudget.MAX_EXPORT_EDGE / 2,
                        )
                    }
                }
                fullContext = full
                val session = EditSession(
                    original = full,
                    initialHistory = history,
                    replayInitialHistory = false,
                )
                val result = ExportPipeline.export(
                    context = this@ExportService,
                    targetUri = Uri.parse(targetUri),
                    fullResolution = full,
                    session = session,
                    format = format,
                    instruction = instruction,
                    progress = { progress -> reportProgress(progress) },
                )
                ExportStatusBus.finished(result)
                val suffix = if (result.degraded) " · 内存保护降级" else ""
                notifyFinal("导出完成 · ${result.width}×${result.height}$suffix")
            } catch (cancel: CancellationException) {
                ExportStatusBus.failed("已取消导出")
                notifyFinal("已取消导出")
                throw cancel
            } catch (error: Throwable) {
                val message = error.message?.takeIf { it.isNotBlank() } ?: "导出失败"
                ExportStatusBus.failed(message)
                notifyFinal(message)
            } finally {
                completed = true
                fullContext?.bitmap?.let { if (!it.isRecycled) it.recycle() }
                fullContext = null
                stopForegroundCompat()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        exportJob?.cancel()
        scope.cancel()
        if (!completed) {
            ExportStatusBus.failed("导出服务已停止，请重试")
        }
        // The export coroutine's finally block owns bitmap recycling. Do not
        // recycle here: onDestroy can race with a non-cancellable native step.
        super.onDestroy()
    }

    private fun reportProgress(progress: ExportProgress) {
        ExportStatusBus.running(progress.phase)
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification(progress.phase, indeterminate = true))
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    private fun notifyFinal(text: String) {
        runCatching {
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID,
                notification(text, indeterminate = false, ongoing = false),
            )
        }
    }

    private fun notification(
        text: String,
        indeterminate: Boolean,
        ongoing: Boolean = true,
    ): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancel = PendingIntent.getService(
            this,
            1,
            Intent(this, ExportService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("图片导出")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, indeterminate)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "取消", cancel)
            .build()
    }

    companion object {
        const val ACTION_CANCEL = "com.example.imageedit.action.CANCEL_EXPORT"
        const val EXTRA_SOURCE_URI = "source_uri"
        const val EXTRA_TARGET_URI = "target_uri"
        const val EXTRA_FORMAT = "format"
        const val EXTRA_INSTRUCTION = "instruction"

        private const val CHANNEL_ID = "image_export"
        private const val NOTIFICATION_ID = 4101
    }
}
