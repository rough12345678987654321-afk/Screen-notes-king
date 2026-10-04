package com.example.screennotes

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Background checks while an AI update is running, so you get notified even when the app is closed.
 * Checks stop by themselves once nothing is running (and start again when you send something).
 */
object UpdatePoller {
    private const val WORK = "sn-update-poll"

    /** Check soon, e.g. right after you send a request or open the app. */
    fun start(ctx: Context, delaySec: Long = 20) {
        if (!UpdateSettings(ctx).ready) return
        enqueue(ctx, delaySec, ExistingWorkPolicy.REPLACE)
    }

    /** Called by the worker itself to schedule the next check. */
    fun scheduleNext(ctx: Context, delaySec: Long) = enqueue(ctx, delaySec, ExistingWorkPolicy.APPEND_OR_REPLACE)

    private fun enqueue(ctx: Context, delaySec: Long, policy: ExistingWorkPolicy) {
        val req = OneTimeWorkRequestBuilder<UpdateWorker>()
            .setInitialDelay(delaySec, TimeUnit.SECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(WORK, policy, req)
    }
}

class UpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val s = UpdateSettings(ctx)
        val gh = s.client() ?: return Result.success()
        val now = System.currentTimeMillis()
        var keepChecking = now - s.lastActionAt < 30 * 60_000L
        try {
            withContext(Dispatchers.IO) {
                for (r in gh.listRequests()) {
                    val recent = now - r.updatedAt < 2 * 3600_000L
                    if (r.status.active && recent) keepChecking = true
                    if (!recent) continue
                    if (s.lastUpdated(r.number) == r.updatedAt) continue // nothing new
                    val msgs = UpdateParse.messages(r, gh.comments(r.number))
                    UpdateNotifier.onUpdate(ctx, s, r, msgs)
                    val bot = msgs.lastOrNull { it.fromAi }?.bot
                    // Labels change right after the status comment is final; if they don't match yet, look again next time.
                    val settled = !(bot?.state == "working" && !r.status.active)
                    if (settled) s.setLastUpdated(r.number, r.updatedAt)
                }
            }
        } catch (e: Exception) {
            // Offline or a GitHub hiccup: try again later.
            keepChecking = keepChecking || now - s.lastActionAt < 3 * 3600_000L
        }
        if (keepChecking) UpdatePoller.scheduleNext(ctx, 90)
        return Result.success()
    }
}

object UpdateNotifier {
    const val CHANNEL = "updates"
    const val EXTRA_ISSUE = "open_update_request"

    private fun id(n: Int) = 1000 + n

    /** Shows a notification when the AI's status for a request changed since we last looked. */
    fun onUpdate(ctx: Context, s: UpdateSettings, r: UpdateRequest, msgs: List<Message>) {
        val last = msgs.lastOrNull { it.fromAi } ?: return
        val bot = last.bot ?: return
        val key = "${last.id}:${bot.state}"
        val prev = s.seen(r.number)
        if (prev == key) return
        s.setSeen(r.number, key)
        if (prev == null) return // first time we see this request (e.g. made on the website): no old news
        if (!r.open) return
        show(ctx, r, bot)
    }

    /** You're looking at the request in the app: don't notify about what's already on screen. */
    fun markSeen(ctx: Context, r: UpdateRequest, msgs: List<Message>) {
        val last = msgs.lastOrNull { it.fromAi } ?: return
        val bot = last.bot ?: return
        UpdateSettings(ctx).setSeen(r.number, "${last.id}:${bot.state}")
        NotificationManagerCompat.from(ctx).cancel(id(r.number))
    }

    private fun show(ctx: Context, r: UpdateRequest, b: BotData) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel(ctx)
        val firstLine = b.reply.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() }?.take(200) ?: ""
        val (title, text) = when (b.state) {
            "working" -> "🤖 The AI is working on your update" to r.title
            "done" -> if (b.apk != null) "✅ Update ready to test" to "${r.title}: tap to install the test version."
            else "💬 The AI replied" to firstLine.ifBlank { r.title }
            "question" -> "❓ The AI has a question" to firstLine.ifBlank { r.title }
            "failed" -> "❌ The update didn't work" to (b.error?.take(200) ?: r.title)
            else -> return
        }
        val open = Intent(ctx, MainActivity::class.java).putExtra(EXTRA_ISSUE, r.number)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(ctx, r.number, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val working = b.state == "working"
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(if (working) android.R.drawable.stat_notify_sync else android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setOnlyAlertOnce(working)
            .apply {
                if (working) {
                    setProgress(0, 0, true) // the "loading" bar while the AI works
                    setSilent(true)
                } else {
                    setPriority(NotificationCompat.PRIORITY_HIGH)
                }
            }
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(id(r.number), n)
        } catch (e: SecurityException) {
            // Notifications not allowed.
        }
    }

    private fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "App updates (AI)", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "When the AI starts, finishes or has a question about your app update"
                }
            )
        }
    }

    fun openSettings(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** Downloads an APK built on GitHub and hands it to Android's installer. */
object ApkInstaller {
    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    fun openPermissionSettings(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    suspend fun downloadAndInstall(ctx: Context, apiUrl: String?, browserUrl: String?, progress: (Float) -> Unit) {
        val file = withContext(Dispatchers.IO) {
            val s = UpdateSettings(ctx)
            val f = File(ctx.cacheDir, "updates/ScreenNotes-update.apk")
            if (s.ready) GitHub(s.token, s.repo).download(apiUrl, browserUrl, f, progress)
            else GitHub("", s.repo).download(null, browserUrl, f, progress) // public download, no token
            f
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
