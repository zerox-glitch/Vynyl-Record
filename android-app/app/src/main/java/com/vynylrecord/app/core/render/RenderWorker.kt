package com.vynylrecord.app.core.render

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.vynylrecord.app.MainActivity
import com.vynylrecord.app.R
import com.vynylrecord.app.VynylGraph
import com.vynylrecord.app.core.audio.render.RenderPipeline
import com.vynylrecord.app.core.model.AssetCategory
import com.vynylrecord.app.core.model.RenderStage
import com.vynylrecord.app.core.storage.FileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * The press, running as durable work.
 *
 * A record takes minutes to press. A user who presses "Press my record" and then goes back to the Vault,
 * opens another record, locks the phone or leaves the app must find their record finished when they come
 * back — not half-written because the process was reaped. That is precisely what a `CoroutineWorker` with
 * a foreground notification is for, and it is why the render does not live in a ViewModel.
 *
 * ## Failure behaviour
 *
 * WorkManager retries a worker that returns `Result.retry()`. A press that failed because the device ran
 * out of space would fail the same way a second later, so this worker does not retry: it records the
 * reason on the record and returns `Result.failure()`. Retrying would also spend the user's battery on
 * something they can fix themselves in one action. What it *does* guarantee is the inverse: work that is
 * interrupted leaves the record marked `rendering`, and the startup sweep turns that into "Press
 * interrupted — start it again", never into a completed record with no audio.
 */
class RenderWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0f, RenderStage.PREPARING.label)

    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        val recordId = inputData.getString(KEY_RECORD_ID) ?: return@withContext Result.failure()
        val jobId = inputData.getString(KEY_JOB_ID) ?: id.toString()

        val graph = VynylGraph.of(applicationContext)
        val records = graph.recordRepository
        val record = records.find(recordId) ?: return@withContext Result.failure()

        val asset = record.backgroundAssetId?.let { graph.assetRepository.find(it) }
        val pipeline = RenderPipeline(applicationContext, FileStore(applicationContext))

        setForeground(foregroundInfo(0f, RenderStage.PREPARING.label))
        records.markRendering(recordId, jobId, RenderStage.PREPARING, 0f)

        var lastNotifiedBucket = -1
        return@withContext try {
            val outcome = pipeline.render(
                request = RenderPipeline.Request(
                    record = record,
                    jobId = jobId,
                    music = asset?.takeIf { it.category == AssetCategory.BACKGROUND_MUSIC },
                ),
                onProgress = { stage, fraction ->
                    val overall = stage.upTo.coerceAtLeast(0f) * 0.98f + fraction * 0.02f
                    records.markRendering(recordId, jobId, stage, overall)
                    setProgress(
                        workDataOf(
                            KEY_PROGRESS to overall,
                            KEY_STAGE to stage.label,
                            KEY_STAGE_INDEX to stage.index,
                        ),
                    )
                    val bucket = (overall * 20f).toInt()
                    if (bucket != lastNotifiedBucket) {
                        lastNotifiedBucket = bucket
                        // Throttled to five percent steps: a notification updated on every block would
                        // flicker, and the platform rate-limits it anyway.
                        notify(foregroundInfo(overall, stage.label))
                    }
                },
                isCancelled = { isStopped },
            )

            records.commitRender(outcome)
            notifyCompletion(recordId, outcome.record.displayTitle)
            Result.success(
                workDataOf(
                    KEY_RECORD_ID to recordId,
                    KEY_PROGRESS to 1f,
                    KEY_STAGE to RenderStage.COMPLETE.label,
                    KEY_DURATION_MS to outcome.durationMs,
                ),
            )
        } catch (error: RenderPipeline.RenderException) {
            if (isStopped) {
                records.markCancelled(recordId)
                Result.failure()
            } else {
                records.markFailed(recordId, error.message)
                notifyFailure(record.displayTitle, error.message)
                Result.failure(workDataOf(KEY_RECORD_ID to recordId, KEY_ERROR to error.message))
            }
        } catch (error: Exception) {
            records.markFailed(recordId, "The press failed: ${error.message ?: "unknown error"}")
            Result.failure(workDataOf(KEY_RECORD_ID to recordId, KEY_ERROR to error.message))
        } finally {
            // The progress notification comes down in every case, including failure: a stuck "Pressing your
            // record" is worse than no notification at all.
            RenderNotifications.dismiss(applicationContext, RenderNotifications.PROGRESS_ID)
        }
    }

    private fun notify(info: ForegroundInfo) {
        val manager = NotificationManagerCompat.from(applicationContext)
        if (NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()) {
            try {
                manager.notify(RenderNotifications.PROGRESS_ID, info.notification)
            } catch (error: SecurityException) {
                // The user denied notifications. The press runs anyway; only the progress display is lost.
            }
        }
    }

    private fun notifyCompletion(recordId: String, title: String) {
        RenderNotifications.complete(applicationContext, recordId, title)
    }

    private fun notifyFailure(title: String, message: String) {
        RenderNotifications.failed(applicationContext, title, message)
    }

    private fun foregroundInfo(progress: Float, stage: String): ForegroundInfo {
        val context = applicationContext
        RenderNotifications.ensureChannel(context)
        val notification = RenderNotifications.progress(context, progress, stage)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                RenderNotifications.PROGRESS_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(RenderNotifications.PROGRESS_ID, notification)
        }
    }

    companion object {
        const val KEY_RECORD_ID = "recordId"
        const val KEY_JOB_ID = "jobId"
        const val KEY_PROGRESS = "progress"
        const val KEY_STAGE = "stage"
        const val KEY_STAGE_INDEX = "stageIndex"
        const val KEY_DURATION_MS = "durationMs"
        const val KEY_ERROR = "error"

        /** One worker per record: re-pressing the same record replaces the queued job rather than racing it. */
        private fun workName(recordId: String) = "press-$recordId"
    }
}

/**
 * Starts, observes and cancels presses.
 *
 * The UI never talks to WorkManager's own API. It asks this for a record's progress and for a way to
 * cancel it, which keeps the knowledge that a press is a `CoroutineWorker` in one file instead of in every
 * screen that can start one.
 */
class RenderScheduler(private val context: Context) {

    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    /** Enqueues a press, replacing any queued press for the same record. */
    fun enqueue(recordId: String, jobId: String = UUID.randomUUID().toString()): UUID {
        val request = OneTimeWorkRequestBuilder<RenderWorker>()
            .setInputData(Data.Builder().putString(RenderWorker.KEY_RECORD_ID, recordId).putString(RenderWorker.KEY_JOB_ID, jobId).build())
            .addTag(TAG)
            .addTag(tagFor(recordId))
            .build()
        workManager.enqueueUniqueWork(
            workName(recordId),
            ExistingWorkPolicy.REPLACE,
            request,
        )
        return request.id
    }

    fun cancel(recordId: String) {
        workManager.cancelUniqueWork(workName(recordId))
    }

    fun cancelAll() {
        workManager.cancelAllWorkByTag(TAG)
    }

    /** Progress for one record, as the UI shows it. */
    fun observe(recordId: String): Flow<PressProgress?> =
        workManager.getWorkInfosByTagFlow(tagFor(recordId)).map { infos ->
            val info = infos.firstOrNull() ?: return@map null
            PressProgress(
                state = info.state,
                progress = info.progress.getFloat(RenderWorker.KEY_PROGRESS, 0f),
                stage = info.progress.getString(RenderWorker.KEY_STAGE) ?: "Preparing source",
                stageIndex = info.progress.getInt(RenderWorker.KEY_STAGE_INDEX, 0),
                error = info.outputData.getString(RenderWorker.KEY_ERROR),
            )
        }

    fun cancelUnique(recordId: String) = cancel(recordId)

    private fun workName(recordId: String) = "press-$recordId"

    private fun tagFor(recordId: String) = "$TAG-$recordId"

    private companion object {
        const val TAG = "vynyl-press"
    }
}

/** A press as the UI sees it: WorkManager's state, plus the numbers the notification and the screen show. */
data class PressProgress(
    val state: WorkInfo.State,
    val progress: Float,
    val stage: String,
    val stageIndex: Int,
    val error: String?,
) {
    val isRunning: Boolean get() = state == WorkInfo.State.RUNNING || state == WorkInfo.State.ENQUEUED
}

/**
 * The notification the app shows while a record is being pressed.
 *
 * "Pressing your record" with the stage underneath, a cancel action and the app's own amber on obsidian.
 * It is a progress notification, not a promotion: it exists because a foreground service must show one and
 * because a user who starts a two-minute press deserves to see that it is still running.
 */
object RenderNotifications {

    const val CHANNEL_ID = "vynyl_press"
    const val PROGRESS_ID = 4_100
    const val COMPLETE_ID = 4_101
    const val FAILED_ID = 4_102

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.render_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.render_channel_description)
            enableLights(false)
            enableVibration(false)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun openApp(context: Context, recordId: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (recordId != null) putExtra(MainActivity.EXTRA_RECORD_ID, recordId)
        }
        return PendingIntent.getActivity(
            context,
            recordId?.hashCode() ?: 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun cancelIntent(context: Context, recordId: String?): PendingIntent {
        val intent = Intent(context, RenderCancelReceiver::class.java).apply {
            putExtra(RenderWorker.KEY_RECORD_ID, recordId)
        }
        return PendingIntent.getBroadcast(
            context,
            recordId?.hashCode() ?: 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun progress(context: Context, progress: Float, stage: String): Notification {
        ensureChannel(context)
        val percent = (progress.coerceIn(0f, 1f) * 100f).toInt()
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_press)
            .setContentTitle(context.getString(R.string.render_notification_title))
            .setContentText(stage)
            .setSubText(context.getString(R.string.render_notification_percent, percent))
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setColor(context.getColor(R.color.vynyl_amber))
            .setContentIntent(openApp(context, null))
            // Three things a person wants from a running press, in the order they want them: open the app,
            // cancel it, and know how far along it is.
            .addAction(0, context.getString(R.string.render_notification_open), openApp(context, null))
            .addAction(0, context.getString(R.string.render_notification_cancel), cancelIntent(context, null))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun complete(context: Context, recordId: String, title: String) {
        ensureChannel(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_press)
            .setContentTitle(context.getString(R.string.render_notification_ready))
            .setContentText(title)
            .setAutoCancel(true)
            .setColor(context.getColor(R.color.vynyl_success))
            .setContentIntent(openApp(context, recordId))
        post(context, COMPLETE_ID, builder.build())
    }

    /**
     * Failure says what happened, unless the user has asked notifications to stay quiet about titles.
     *
     * The Settings switch for that is not paranoia: this notification can appear on a lock screen, and the
     * title of a record can be a person's name.
     */
    fun failed(context: Context, title: String, message: String) {
        ensureChannel(context)
        val hideDetail = runCatching { VynylGraph.of(context).preferences.current().hideNotificationDetail }
            .getOrDefault(false)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_press)
            .setContentTitle(context.getString(R.string.render_notification_failed))
            .setContentText(if (hideDetail) message else "\"$title\" — $message")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(if (hideDetail) message else "\"$title\" — $message"),
            )
            .setAutoCancel(true)
            .setColor(context.getColor(R.color.vynyl_error))
            .setContentIntent(openApp(context, null))
        post(context, FAILED_ID, builder.build())
    }

    fun dismiss(context: Context, id: Int) {
        runCatching { NotificationManagerCompat.from(context).cancel(id) }
    }

    private fun post(context: Context, id: Int, notification: Notification) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        try {
            manager.notify(id, notification)
        } catch (error: SecurityException) {
            // Refused by the user; the record is still finished and the Vault shows it.
        }
    }
}

/** The notification's Cancel action, kept out of the worker so it also works for a queued job. */
class RenderCancelReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val recordId = intent.getStringExtra(RenderWorker.KEY_RECORD_ID)
        val scheduler = RenderScheduler(context)
        if (recordId != null) {
            scheduler.cancel(recordId)
        } else {
            scheduler.cancelAll()
        }
        RenderNotifications.dismiss(context, RenderNotifications.PROGRESS_ID)
    }
}
