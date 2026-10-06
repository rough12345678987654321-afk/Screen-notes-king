package com.example.screennotes

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max

/**
 * Looks at the screen about twice a second. When the screen has settled, it compares it with the
 * last saved screenshot of this slide:
 *   - nothing new            -> ignore
 *   - same content + MORE    -> REPLACE the old screenshot with the fuller one (e.g. teacher keeps writing)
 *   - old content changed    -> keep the old one and SAVE a new screenshot (a new slide)
 */
class CaptureService : Service() {

    companion object {
        val running = MutableStateFlow(false)
        const val ACTION_STOP = "stop"

        // --- Tuning knobs ---
        const val TICK_MS = 500L           // how often we look at the screen
        const val MOVING_THRESHOLD = 3.0   // bigger change between two looks = screen still moving (video/animation)
        const val SETTLE_TICKS = 2         // screen must stay still this many looks before we judge it
        const val GW = 64                  // the screen is split into GW x GH small cells
        const val GH = 36
        const val CONTENT_T = 6f           // how "detailed" a cell must be to count as content (text/drawing)
        const val LOST_LIMIT = 0.06f       // up to 6% of old content may change and it still counts as "same slide + more"
        const val MIN_ADDED = 2            // new content cells needed before we replace the old screenshot
        const val MIN_CONTENT_CELLS = 12   // an almost empty old screen counts as having no content
        const val WARMUP_MS = 3000L        // ignore the first seconds after Start (player UI, file picker)
    }

    private class Feat(val mean: FloatArray, val energy: FloatArray)

    private class Cur(val noteId: Long, val timeMs: Long, var feat: Feat) {
        @Volatile var id = 0L
        @Volatile var path = ""
        @Volatile var version = 0
    }

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    // One worker thread does all file + database work in order, so replacements never race.
    private val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    private var noteId = 0L
    private var startMs = 0L
    private var w = 0
    private var h = 0
    private var lastFrame: IntArray? = null
    private var stableTicks = 0
    private var cur: Cur? = null

    private val tick = object : Runnable {
        override fun run() {
            try { grab() } catch (_: Exception) { }
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (running.value) return START_NOT_STICKY

        startForegroundNow()

        val code = intent?.getIntExtra("code", 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>("data") ?: run { stopSelf(); return START_NOT_STICKY }

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(code, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, handler)

        val dm = resources.displayMetrics
        val scale = 1280f / max(dm.widthPixels, dm.heightPixels)
        w = (dm.widthPixels * scale).toInt()
        h = (dm.heightPixels * scale).toInt()

        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        display = projection?.createVirtualDisplay(
            "ScreenNotes", w, h, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, null
        )

        startMs = System.currentTimeMillis()
        scope.launch {
            val title = "Session " + java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(startMs))
            noteId = AppDb.get(this@CaptureService).dao().insertNote(Note(title = title, createdAt = startMs))
        }
        running.value = true
        handler.postDelayed(tick, 1000)
        return START_NOT_STICKY
    }

    private fun startForegroundNow() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("cap", "Capture", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "cap")
            .setContentTitle("Screen Notes is capturing")
            .setContentText("Tap Stop when your lecture ends")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    private fun grab() {
        if (noteId == 0L) return
        val img = reader?.acquireLatestImage() ?: return
        // Warm-up: the first seconds usually show the file manager or player controls, not the lecture.
        if (System.currentTimeMillis() - startMs < WARMUP_MS) {
            img.close()
            return
        }
        val plane = img.planes[0]
        val rowPixels = plane.rowStride / plane.pixelStride
        val full = Bitmap.createBitmap(rowPixels, h, Bitmap.Config.ARGB_8888)
        full.copyPixelsFromBuffer(plane.buffer)
        img.close()
        val raw = Bitmap.createBitmap(full, 0, 0, w, h)
        val bmp = cleanFrame(raw)
        if (bmp != raw) raw.recycle()

        val sig = signature(bmp)
        val prev = lastFrame
        lastFrame = sig

        if (prev != null && diff(prev, sig) > MOVING_THRESHOLD) {
            stableTicks = 0            // still moving, wait until it settles
            return
        }
        stableTicks++
        if (stableTicks < SETTLE_TICKS) return

        val f = features(bmp)
        // Black frames, empty players and other content-less screens are never worth keeping.
        if (f.energy.count { it >= CONTENT_T } < MIN_CONTENT_CELLS) return
        val c = cur
        if (c == null) { startShot(bmp, f); return }
        when (compare(c.feat, f)) {
            2 -> startShot(bmp, f)
            1 -> replaceShot(c, bmp, f)
            else -> { }
        }
    }

    private var chromeTop = -1
    private var chromeBottom = -1

    /** Height of the status bar and navigation bar in capture pixels (they are never slide content). */
    private fun chromeCrop(): Pair<Int, Int> {
        if (chromeTop < 0) {
            val res = resources
            val dm = res.displayMetrics
            val scale = 1280f / max(dm.widthPixels, dm.heightPixels)
            val sb = res.getIdentifier("status_bar_height", "dimen", "android")
            val nb = res.getIdentifier("navigation_bar_height", "dimen", "android")
            chromeTop = ((if (sb > 0) res.getDimensionPixelSize(sb) else 0) * scale).toInt()
            chromeBottom = ((if (nb > 0) res.getDimensionPixelSize(nb) else 0) * scale).toInt()
        }
        return chromeTop to chromeBottom
    }

    /** Crops away status bar, navigation bar and black letterbox rows so slides arrive clean. */
    private fun cleanFrame(b: Bitmap): Bitmap {
        val (top, bottom) = chromeCrop()
        var y0 = top.coerceIn(0, b.height - 1)
        var y1 = (b.height - bottom).coerceIn(y0 + 1, b.height)
        fun rowDark(y: Int): Boolean {
            var sum = 0L
            var n = 0
            var x = 0
            while (x < b.width) { sum += gray(b.getPixel(x, y)); n++; x += 16 }
            return sum / max(n, 1) < 10
        }
        while (y0 < y1 - 1 && rowDark(y0)) y0++
        while (y1 > y0 + 1 && rowDark(y1 - 1)) y1--
        return if (y0 == 0 && y1 == b.height) b else Bitmap.createBitmap(b, 0, y0, b.width, y1 - y0)
    }

    /** Tiny 32x18 grayscale fingerprint of the screen (used to detect motion). */
    private fun signature(b: Bitmap): IntArray {
        val s = Bitmap.createScaledBitmap(b, 32, 18, true)
        val px = IntArray(32 * 18)
        s.getPixels(px, 0, 32, 0, 0, 32, 18)
        return IntArray(px.size) { gray(px[it]) }
    }

    private fun gray(c: Int): Int =
        (((c shr 16) and 0xFF) * 3 + ((c shr 8) and 0xFF) * 6 + (c and 0xFF)) / 10

    private fun diff(a: IntArray, b: IntArray): Double {
        var sum = 0L
        for (i in a.indices) sum += abs(a[i] - b[i])
        return sum.toDouble() / a.size
    }

    /** For every cell of the screen: average brightness, and how detailed it is (text/drawing = detailed). */
    private fun features(b: Bitmap): Feat {
        val fw = GW * 4
        val fh = GH * 4
        val s = Bitmap.createScaledBitmap(b, fw, fh, true)
        val px = IntArray(fw * fh)
        s.getPixels(px, 0, fw, 0, 0, fw, fh)
        val g = IntArray(fw * fh) { gray(px[it]) }
        val mean = FloatArray(GW * GH)
        val energy = FloatArray(GW * GH)
        for (cy in 0 until GH) {
            for (cx in 0 until GW) {
                var sum = 0
                var grad = 0
                for (y in 0 until 4) {
                    for (x in 0 until 4) {
                        val xx = cx * 4 + x
                        val yy = cy * 4 + y
                        val v = g[yy * fw + xx]
                        sum += v
                        if (xx + 1 < fw) grad += abs(v - g[yy * fw + xx + 1])
                        if (yy + 1 < fh) grad += abs(v - g[(yy + 1) * fw + xx])
                    }
                }
                mean[cy * GW + cx] = sum / 16f
                energy[cy * GW + cx] = grad / 32f
            }
        }
        return Feat(mean, energy)
    }

    /** 0 = nothing new, 1 = same content plus something extra (replace), 2 = different slide (save new). */
    private fun compare(old: Feat, new: Feat): Int {
        var prevContent = 0
        var lost = 0
        var added = 0
        for (i in old.energy.indices) {
            val pe = old.energy[i]
            val ne = new.energy[i]
            if (pe >= CONTENT_T) {
                prevContent++
                val md = abs(old.mean[i] - new.mean[i])
                if (ne < pe * 0.6f || (md > 45f && ne < pe + 6f)) lost++
            } else if (ne >= CONTENT_T) {
                added++
            }
        }
        if (prevContent < MIN_CONTENT_CELLS) return if (added >= MIN_CONTENT_CELLS) 2 else 0
        val lostFrac = lost.toFloat() / prevContent
        return if (lostFrac > LOST_LIMIT) 2 else if (added >= MIN_ADDED) 1 else 0
    }

    /** Save a brand new screenshot (a new slide). */
    private fun startShot(bmp: Bitmap, f: Feat) {
        val t = System.currentTimeMillis() - startMs
        val c = Cur(noteId, t, f)
        cur = c
        scope.launch(worker) {
            val dir = File(filesDir, "shots/${c.noteId}").apply { mkdirs() }
            val file = File(dir, "$t.jpg")
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            c.path = file.absolutePath
            c.id = AppDb.get(this@CaptureService).dao()
                .insertShot(Shot(noteId = c.noteId, path = c.path, ocrText = "", timeMs = t))
            runOcr(c, bmp, 0)
        }
    }

    /** The new frame has everything the old one had, plus more: swap the old screenshot for it. */
    private fun replaceShot(c: Cur, bmp: Bitmap, f: Feat) {
        c.feat = f
        c.version = c.version + 1
        val ver = c.version
        val t = System.currentTimeMillis() - startMs
        scope.launch(worker) {
            val old = File(c.path)
            val file = File(old.parentFile, "$t.jpg")
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            c.path = file.absolutePath
            AppDb.get(this@CaptureService).dao()
                .updateShot(Shot(id = c.id, noteId = c.noteId, path = c.path, ocrText = "", timeMs = c.timeMs))
            if (old.absolutePath != file.absolutePath) old.delete()
            runOcr(c, bmp, ver)
        }
    }

    private fun runOcr(c: Cur, bmp: Bitmap, ver: Int) {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            .process(InputImage.fromBitmap(bmp, 0))
            .addOnCompleteListener { task ->
                val text = if (task.isSuccessful) task.result.text else ""
                scope.launch(worker) {
                    // Only save the text if this screenshot has not been replaced in the meantime.
                    if (c.version == ver) {
                        AppDb.get(this@CaptureService).dao()
                            .updateShot(Shot(id = c.id, noteId = c.noteId, path = c.path, ocrText = text, timeMs = c.timeMs))
                    }
                }
            }
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        display?.release()
        reader?.close()
        projection?.stop()
        running.value = false
        super.onDestroy()
    }
}
