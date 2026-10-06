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
import java.util.ArrayDeque
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Looks at the screen four times a second ([TICK_MS]).
 *
 * While the screen is moving (a video at 2x speed, a fast scroll) a MOTION EPISODE starts and only
 * the SHARPEST frame of that episode is kept - sharpness is the gradient-energy sum of [features],
 * so a half-drawn transition frame can never win. When content changes to a different slide
 * ([compare] returns 2), or the screen settles, that sharpest frame is saved instead of the last
 * raw frame. A long smooth pan is also sampled every
 * [MAX_EPISODE_MS], so a slide visible for only ~1 second can still be caught.
 *
 * Writes are throttled to one screenshot per [MIN_SAVE_GAP_MS]. A small oldest-first queue keeps
 * finished slides in order while the gap is closed; fast scrolling cannot flood storage or let a
 * sharper later slide replace an earlier one.
 *
 * What is saved (unchanged):
 *   - nothing new            -> ignore
 *   - same content + MORE    -> REPLACE the old screenshot with the fuller one (teacher keeps writing)
 *   - old content changed    -> keep the old one and SAVE a new screenshot (a new slide)
 */
class CaptureService : Service() {

    companion object {
        val running = MutableStateFlow(false)
        const val ACTION_STOP = "stop"

        // --- Tuning knobs ---
        const val TICK_MS = 250L           // how often we look at the screen (4 looks a second catches ~1 s slides)
        const val MOVING_THRESHOLD = 3.0   // bigger change between two looks = screen still moving (video/animation)
        const val SETTLE_TICKS = 2         // screen must stay still this many looks before we judge it
        const val GW = 64                  // the screen is split into GW x GH small cells
        const val GH = 36
        const val CONTENT_T = 6f           // how "detailed" a cell must be to count as content (text/drawing)
        const val LOST_LIMIT = 0.06f       // up to 6% of old content may change and it still counts as "same slide + more"
        const val MIN_ADDED = 2            // new content cells needed before we replace the old screenshot
        const val MIN_CONTENT_CELLS = 12   // an almost empty old screen counts as having no content
        const val WARMUP_MS = 3000L        // ignore the first seconds after Start (player UI, file picker)

        // --- Sharpest-frame capture (fast playback) ---
        const val MIN_SAVE_GAP_MS = 1200L  // at most one screenshot per ~1.2 s, so fast scrolling cannot flood storage
        const val MAX_EPISODE_MS = 1000L   // a long pan is sampled at least once a second
        const val MAX_PENDING_SHOTS = 3    // bounded oldest-first backlog while the save throttle is closed
        const val NEAR_DUP_SIG_DIFF = 3.0  // mean absolute difference on the 32x18 gray signature
        const val MAX_SCROLL_SHIFT = 9     // 9 of 36 feature rows = 25% of the screen height
        const val MIN_SCROLL_COVERAGE = 0.50f // require enough overlapping content to trust an alignment
        const val STILL_EPS = 0.6          // a settled screen that changed less than this is not judged again

        // --- Border and player-overlay crop ---
        const val BORDER_DIV = 8           // borders are measured on a small copy: cheap, and it can only crop too little
        const val UNIFORM_TOL = 16         // biggest gray spread a row/column may have and still count as uniform
        const val DARK_T = 26              // a row/column darker than this is letterbox or player chrome
        const val EDGE_T = CONTENT_T       // average edge strength at which a row/column holds content (same measure as CONTENT_T)
        const val MAX_V_SIDE = 0.42f       // the top or the bottom edge may never eat more than 42% of the height
        const val MAX_H_SIDE = 0.30f       // the left or the right edge may never eat more than 30% of the width
        const val MIN_KEEP_V = 0.16f       // at least 16% of the height survives cropping
        const val MIN_KEEP_H = 0.40f       // at least 40% of the width survives cropping
        const val OVERLAY_MAX = 0.12f      // a player-controls band may take at most 12% of the height
        const val OVERLAY_SPIKE = 3.0f     // row brightness-variance spike that marks such a band
        const val OVERLAY_VAR_FLOOR = 150f // ... and the spike must reach at least this (blank rows have ~0 variance)
        const val OVERLAY_STEP = 8         // a translucent scrim also steps the row brightness by this much
        const val OVERLAY_MIN_ROWS = 28    // a band shorter than this is a line of text, not a control bar
        const val OVERLAY_SEGMENTS = 4     // the band must span the width, not just sit where some text happens to be
    }

    private class Feat(val mean: FloatArray, val energy: FloatArray)

    /**
     * A frame worth keeping: its bitmap (owned here until it is saved or recycled), its features, how
     * sharp it is and the moment it was seen (which becomes the screenshot's place in the session).
     */
    private class Cand(
        val bmp: Bitmap,
        val feat: Feat,
        val sharp: Float,
        val timeMs: Long,
        val signature: IntArray
    ) {
        fun recycle() { if (!bmp.isRecycled) bmp.recycle() }
    }

    private class Cur(val noteId: Long, val timeMs: Long, var feat: Feat) {
        @Volatile var id = 0L
        @Volatile var path = ""
        @Volatile var version = 0
    }

    /** A small grayscale copy of the frame, used to find uniform borders on all four sides. */
    private class Grid(val w: Int, val h: Int, val g: IntArray) {
        fun at(x: Int, y: Int) = g[y * w + x]
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
    private var lastJudgeSig: IntArray? = null
    private var stableTicks = 0
    private var moving = false
    private var episodeStartMs = 0L
    private var best: Cand? = null        // sharpest frame of the running motion episode
    private val pending = ArrayDeque<Cand>() // bounded, ordered frames waiting out the save throttle
    private var lastSaveMs = 0L
    private var lastSavedSig: IntArray? = null
    private var cur: Cur? = null
    private var captureScale = -1f
    private var rowBuf: IntArray? = null  // one row of pixels, reused by the player-overlay check

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
        val nativeMaxSide = max(dm.widthPixels, dm.heightPixels)
        val scale = min(nativeMaxSide, 2560).toFloat() / nativeMaxSide
        captureScale = scale
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
        drainPending()
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
        if (raw != full) full.recycle()
        val bmp = cleanFrame(raw)
        if (bmp != raw) raw.recycle()

        val now = System.currentTimeMillis()
        val sig = signature(bmp)
        val prev = lastFrame
        lastFrame = sig

        if (prev != null && diff(prev, sig) > MOVING_THRESHOLD) {
            // Keep the best frame inside each visual segment. A compare() == 2 boundary closes the
            // preceding segment even if playback never pauses between slides.
            if (!moving) {
                moving = true
                episodeStartMs = now
                dropBest()
            }
            stableTicks = 0
            considerEpisodeFrame(bmp, now, sig)
            // A long pan or continuous animation still yields a candidate at least once a second.
            if (now - episodeStartMs >= MAX_EPISODE_MS) {
                val c = best
                best = null
                episodeStartMs = now
                if (c != null) judge(c)
            }
            return
        }

        stableTicks++
        if (stableTicks < SETTLE_TICKS) {
            // Not settled yet. Inside an episode these steady frames join the sharpness contest too.
            if (moving) considerEpisodeFrame(bmp, now, sig) else bmp.recycle()
            return
        }

        if (moving) {
            // Settling closes the episode; keep its sharpest frame, not the last raw frame.
            considerEpisodeFrame(bmp, now, sig)
            moving = false
            val c = best
            best = null
            lastJudgeSig = sig
            if (c != null) judge(c)
            return
        }

        // A quiet screen: judge only when something changed, or when a finished frame is waiting to be written.
        val judged = lastJudgeSig
        if (pending.isEmpty() && judged != null && diff(judged, sig) < STILL_EPS) {
            bmp.recycle()
            return
        }
        lastJudgeSig = sig
        judge(candidate(bmp, now, sig))
    }

    // ------------------------------------------------------- sharpest frame of a motion episode

    /** Sharpness of a frame: the gradient-energy sum of [features]. Blurry transition frames score low. */
    private fun sharpness(f: Feat): Float = f.energy.sum()

    private fun candidate(bmp: Bitmap, now: Long, sig: IntArray): Cand {
        val f = features(bmp)
        return Cand(bmp, f, sharpness(f), now, sig)
    }

    /**
     * Collect the sharpest frame in the current visual segment. If its content changes into a
     * different slide before settling, close the previous segment and start a fresh contest.
     */
    private fun considerEpisodeFrame(bmp: Bitmap, now: Long, sig: IntArray) {
        val c = candidate(bmp, now, sig)
        val previous = best
        if (previous != null && compare(previous.feat, c.feat) == 2) {
            best = null
            judge(previous)
            episodeStartMs = now
            best = c
        } else if (previous == null || c.sharp > previous.sharp) {
            previous?.recycle()
            best = c
        } else {
            c.recycle()
        }
    }

    private fun dropBest() {
        best?.recycle()
        best = null
    }

    /**
     * Queue finished frames in capture order and write no more than one per [MIN_SAVE_GAP_MS].
     * Adjacent candidates for the same slide are coalesced; distinct slides are never replaced
     * merely because another slide happened to have a larger sharpness score.
     */
    private fun judge(c: Cand) {
        // Do not let blank transition frames occupy the bounded backlog ahead of real slides.
        if (c.feat.energy.count { it >= CONTENT_T } < MIN_CONTENT_CELLS) {
            c.recycle()
            return
        }
        val queued = pending.peekLast()
        if (queued == null) {
            pending.addLast(c)
        } else {
            when (compare(queued.feat, c.feat)) {
                2 -> if (pending.size < MAX_PENDING_SHOTS) pending.addLast(c) else c.recycle()
                1 -> {
                    pending.removeLast().recycle()
                    pending.addLast(c) // same slide, but this capture contains more of it
                }
                else -> if (c.sharp > queued.sharp) {
                    pending.removeLast().recycle()
                    pending.addLast(c)
                } else c.recycle()
            }
        }
        drainPending()
    }

    /** Write the oldest eligible candidate; a bounded queue prevents fast scrolling using unbounded memory. */
    private fun drainPending() {
        if (System.currentTimeMillis() - lastSaveMs < MIN_SAVE_GAP_MS) return
        while (pending.isNotEmpty()) {
            val c = pending.removeFirst()
            if (commit(c)) return
        }
    }

    /** Saves this frame when it carries content and adds something. True when it reached the disk. */
    private fun commit(c: Cand): Boolean {
        // Black frames, empty players and other content-less screens are never worth keeping.
        if (c.feat.energy.count { it >= CONTENT_T } < MIN_CONTENT_CELLS) { c.recycle(); return false }
        // A scrolling/signature fluctuation must not create another shot of the same saved image.
        val saved = lastSavedSig
        if (saved != null && diff(saved, c.signature) < NEAR_DUP_SIG_DIFF) { c.recycle(); return false }
        val current = cur
        if (current == null) { startShot(c); return true }
        return when (compare(current.feat, c.feat)) {
            2 -> { startShot(c); true }
            1 -> { replaceShot(current, c); true }
            else -> { c.recycle(); false }
        }
    }

    // ---------------------------------------------------------------- frame cleaning

    private var chromeTop = -1
    private var chromeBottom = -1

    /** Height of the status bar and navigation bar in capture pixels (they are never slide content). */
    private fun chromeCrop(): Pair<Int, Int> {
        if (chromeTop < 0) {
            val res = resources
            val dm = res.displayMetrics
            val scale = if (captureScale > 0f) captureScale else {
                val nativeMaxSide = max(dm.widthPixels, dm.heightPixels)
                min(nativeMaxSide, 2560).toFloat() / nativeMaxSide
            }
            val sb = res.getIdentifier("status_bar_height", "dimen", "android")
            val nb = res.getIdentifier("navigation_bar_height", "dimen", "android")
            chromeTop = ((if (sb > 0) res.getDimensionPixelSize(sb) else 0) * scale).toInt()
            chromeBottom = ((if (nb > 0) res.getDimensionPixelSize(nb) else 0) * scale).toInt()
        }
        return chromeTop to chromeBottom
    }

    /**
     * Crops away the status/navigation bars, a detected translucent player-controls band, and
     * uniform dark or light borders on every side. Border crops are checked against the same
     * gradient-energy content cells used by [compare], so a suspected border is left in place
     * whenever a detected content cell would be removed.
     */
    private fun cleanFrame(b: Bitmap): Bitmap {
        val (top, bottom) = chromeCrop()
        var y0 = top.coerceIn(0, b.height - 1)
        var y1 = (b.height - bottom).coerceIn(y0 + 1, b.height)
        var x0 = 0
        var x1 = b.width

        // Re-detect on every sample: never keep cropping a stale controls band after it disappears.
        val overlay = try { controlsOverlay(b, x0, x1, y0, y1) } catch (_: Exception) { 0 }
        if (overlay > 0) y1 = max(y0 + 1, y1 - overlay)

        val grid = gridOf(b)
        if (grid != null) {
            val minKeepY = max(16, (b.height * MIN_KEEP_V).toInt())
            val minKeepX = max(16, (b.width * MIN_KEEP_H).toInt())
            var borderFeatures: Feat? = null
            fun hasContentIn(left: Int, upper: Int, right: Int, lower: Int): Boolean {
                val feat = borderFeatures ?: features(b).also { borderFeatures = it }
                return hasDetectedContent(feat, b, left, upper, right, lower)
            }

            // Rows first, then columns, twice: cropping a sidebar can uncover a letterbox bar.
            repeat(2) {
                val rows = uniformRows(grid)
                val ny0 = max(y0, rows.first * BORDER_DIV)
                val ny1 = if (rows.second < grid.h) min(y1, rows.second * BORDER_DIV) else y1
                if (ny0 > y0 && y1 - ny0 >= minKeepY &&
                    !hasContentIn(0, y0, b.width, ny0)
                ) y0 = ny0
                if (ny1 < y1 && ny1 - y0 >= minKeepY &&
                    !hasContentIn(0, ny1, b.width, y1)
                ) y1 = ny1

                val cols = uniformCols(grid)
                val nx0 = max(x0, cols.first * BORDER_DIV)
                val nx1 = if (cols.second < grid.w) min(x1, cols.second * BORDER_DIV) else x1
                if (nx0 > x0 && x1 - nx0 >= minKeepX &&
                    !hasContentIn(x0, y0, nx0, y1)
                ) x0 = nx0
                if (nx1 < x1 && nx1 - x0 >= minKeepX &&
                    !hasContentIn(nx1, y0, x1, y1)
                ) x1 = nx1
            }
        }

        if (x0 == 0 && y0 == 0 && x1 == b.width && y1 == b.height) return b
        if (x1 - x0 < 8 || y1 - y0 < 8) return b
        return Bitmap.createBitmap(b, x0, y0, x1 - x0, y1 - y0)
    }

    /** A small grayscale copy of the frame: enough detail to see borders, cheap at 4 looks a second. */
    private fun gridOf(b: Bitmap): Grid? {
        return try {
            val sw = b.width / BORDER_DIV
            val sh = b.height / BORDER_DIV
            if (sw < 16 || sh < 16) return null
            val s = Bitmap.createScaledBitmap(b, sw, sh, true)
            val px = IntArray(sw * sh)
            s.getPixels(px, 0, sw, 0, 0, sw, sh)
            if (s != b) s.recycle()
            Grid(sw, sh, IntArray(px.size) { gray(px[it]) })
        } catch (_: Exception) {
            null
        }
    }

    /** True when any feature cell at or above CONTENT_T intersects the proposed crop rectangle. */
    private fun hasDetectedContent(
        feat: Feat,
        b: Bitmap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): Boolean {
        if (right <= left || bottom <= top) return false
        val x0 = (left.toLong() * GW / b.width).toInt().coerceIn(0, GW)
        val x1 = ((right.toLong() * GW + b.width - 1) / b.width).toInt().coerceIn(0, GW)
        val y0 = (top.toLong() * GH / b.height).toInt().coerceIn(0, GH)
        val y1 = ((bottom.toLong() * GH + b.height - 1) / b.height).toInt().coerceIn(0, GH)
        for (cy in y0 until y1) {
            for (cx in x0 until x1) {
                if (feat.energy[cy * GW + cx] >= CONTENT_T) return true
            }
        }
        return false
    }

    /** Is this small row a border row: uniform in colour (or dark) and free of content edges? */
    private fun borderRow(g: Grid, y: Int): Boolean {
        var lo = 255
        var hi = 0
        var sum = 0L
        var edge = 0L
        var prev = -1
        for (x in 0 until g.w) {
            val v = g.at(x, y)
            if (v < lo) lo = v
            if (v > hi) hi = v
            sum += v
            if (prev >= 0) edge += abs(v - prev)
            prev = v
        }
        val mean = sum / g.w
        val edgeMean = edge.toFloat() / max(g.w - 1, 1)
        return edgeMean < EDGE_T && (hi - lo <= UNIFORM_TOL || mean <= DARK_T)
    }

    /** Is this small column a border column: uniform in colour (or dark) and free of content edges? */
    private fun borderCol(g: Grid, x: Int): Boolean {
        var lo = 255
        var hi = 0
        var sum = 0L
        var edge = 0L
        var prev = -1
        for (y in 0 until g.h) {
            val v = g.at(x, y)
            if (v < lo) lo = v
            if (v > hi) hi = v
            sum += v
            if (prev >= 0) edge += abs(v - prev)
            prev = v
        }
        val mean = sum / g.h
        val edgeMean = edge.toFloat() / max(g.h - 1, 1)
        return edgeMean < EDGE_T && (hi - lo <= UNIFORM_TOL || mean <= DARK_T)
    }

    /** First and (exclusive) last content row of the small copy, in small coordinates. */
    private fun uniformRows(g: Grid): Pair<Int, Int> {
        val maxSide = (g.h * MAX_V_SIDE).toInt()
        val minKeep = max(16, (g.h * MIN_KEEP_V).toInt())
        var t = 0
        while (t < maxSide && g.h - t > minKeep && borderRow(g, t)) t++
        var bot = g.h
        while (bot > t && g.h - bot < maxSide && bot - t > minKeep && borderRow(g, bot - 1)) bot--
        return t to bot
    }

    /** First and (exclusive) last content column of the small copy, in small coordinates. */
    private fun uniformCols(g: Grid): Pair<Int, Int> {
        val maxSide = (g.w * MAX_H_SIDE).toInt()
        val minKeep = max(16, (g.w * MIN_KEEP_H).toInt())
        var l = 0
        while (l < maxSide && g.w - l > minKeep && borderCol(g, l)) l++
        var r = g.w
        while (r > l && g.w - r < maxSide && r - l > minKeep && borderCol(g, r - 1)) r--
        return l to r
    }

    /**
     * Height (in capture pixels) of a translucent player-controls band sitting on the bottom edge,
     * or 0 when there is none. The band has to look like an overlay and not like content: it starts
     * at the very bottom edge, it is at most [OVERLAY_MAX] of the height, its rows spike in
     * brightness variance (icons, progress bar) or step in brightness (the translucent scrim), it
     * contains at least one real variance spike, and it does that across most of the width - a line
     * of notes text does not.
     */
    private fun controlsOverlay(b: Bitmap, x0: Int, x1: Int, y0: Int, y1: Int): Int {
        val width = x1 - x0
        val height = y1 - y0
        if (width < 64 || height < 240) return 0
        val bandMax = (height * OVERLAY_MAX).toInt()
        if (bandMax < OVERLAY_MIN_ROWS) return 0
        val rows = bandMax * 2                       // bottom half = the band, top half = what is above it
        if (height < rows + 40) return 0
        val top = y1 - rows
        val buf = rowBuf?.takeIf { it.size >= width } ?: IntArray(width).also { rowBuf = it }

        val mean = FloatArray(rows)
        val variance = FloatArray(rows)
        val segMean = Array(OVERLAY_SEGMENTS) { FloatArray(rows) }
        val segVar = Array(OVERLAY_SEGMENTS) { FloatArray(rows) }
        val segWidth = max(1, width / OVERLAY_SEGMENTS)

        for (i in 0 until rows) {
            b.getPixels(buf, 0, width, x0, top + i, width, 1)
            var sum = 0L
            var sumSq = 0L
            var n = 0
            val sSum = LongArray(OVERLAY_SEGMENTS)
            val sSumSq = LongArray(OVERLAY_SEGMENTS)
            val sN = IntArray(OVERLAY_SEGMENTS)
            var x = 0
            while (x < width) {
                val v = gray(buf[x])
                sum += v
                sumSq += v.toLong() * v
                n++
                val s = min(x / segWidth, OVERLAY_SEGMENTS - 1)
                sSum[s] += v
                sSumSq[s] += v.toLong() * v
                sN[s]++
                x += 4
            }
            if (n == 0) return 0
            mean[i] = sum.toFloat() / n
            variance[i] = (sumSq.toFloat() / n) - mean[i] * mean[i]
            for (s in 0 until OVERLAY_SEGMENTS) {
                if (sN[s] == 0) continue
                val m = sSum[s].toFloat() / sN[s]
                segMean[s][i] = m
                segVar[s][i] = (sSumSq[s].toFloat() / sN[s]) - m * m
            }
        }

        // What the rows above the band look like: that is the content an overlay would cover.
        val refMean = mean.copyOfRange(0, bandMax).average().toFloat()
        val refVar = median(variance.copyOfRange(0, bandMax))
        val spike = max(refVar * OVERLAY_SPIKE, OVERLAY_VAR_FLOOR)

        fun overlayRow(i: Int): Boolean =
            variance[i] >= spike || abs(mean[i] - refMean) >= OVERLAY_STEP

        var i = rows - 1
        if (!overlayRow(i)) return 0                   // a line of text has quiet rows below it
        while (i - 1 >= bandMax && overlayRow(i - 1)) i--
        val bandRows = rows - i
        if (bandRows < OVERLAY_MIN_ROWS) return 0
        var spikeRow = false
        for (r in i until rows) if (variance[r] >= spike) { spikeRow = true; break }
        if (!spikeRow) return 0                        // a plain letterbox bar is not a controls overlay

        // And it has to span the width, the way a scrim or a progress bar does.
        var wide = 0
        for (s in 0 until OVERLAY_SEGMENTS) {
            val bandM = segMean[s].copyOfRange(i, rows).average().toFloat()
            val bandV = median(segVar[s].copyOfRange(i, rows))
            val refM = segMean[s].copyOfRange(0, bandMax).average().toFloat()
            val refV = median(segVar[s].copyOfRange(0, bandMax))
            if (bandV >= max(refV * OVERLAY_SPIKE, OVERLAY_VAR_FLOOR) || abs(bandM - refM) >= OVERLAY_STEP) wide++
        }
        return if (wide >= OVERLAY_SEGMENTS - 1) bandRows else 0
    }

    private fun median(values: FloatArray): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.copyOf().also { it.sort() }
        return sorted[sorted.size / 2]
    }

    // ---------------------------------------------------------------- motion + content comparison

    /** Tiny 32x18 grayscale fingerprint of the screen (used to detect motion). */
    private fun signature(b: Bitmap): IntArray {
        val s = Bitmap.createScaledBitmap(b, 32, 18, true)
        val px = IntArray(32 * 18)
        s.getPixels(px, 0, 32, 0, 0, 32, 18)
        if (s != b) s.recycle()
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
        if (s != b) s.recycle()
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

    /** 0 = nothing new, 1 = same content plus something extra, 2 = different slide. */
    private fun compare(old: Feat, new: Feat): Int {
        val prevContent = old.energy.count { it >= CONTENT_T }
        if (prevContent < MIN_CONTENT_CELLS) {
            val added = old.energy.indices.count { old.energy[it] < CONTENT_T && new.energy[it] >= CONTENT_T }
            return if (added >= MIN_CONTENT_CELLS) 2 else 0
        }

        var bestShift = 0
        var bestLostFraction = Double.POSITIVE_INFINITY
        var bestCoverage = 1f
        var bestAdded = 0
        var bestScore = Double.POSITIVE_INFINITY
        for (shift in -MAX_SCROLL_SHIFT..MAX_SCROLL_SHIFT) {
            var compared = 0
            var lost = 0
            var added = 0
            for (y in 0 until GH) {
                val newY = y + shift
                if (newY !in 0 until GH) continue // scrolling content can leave the viewport
                for (x in 0 until GW) {
                    val oldIndex = y * GW + x
                    val newIndex = newY * GW + x
                    val pe = old.energy[oldIndex]
                    val ne = new.energy[newIndex]
                    if (pe >= CONTENT_T) {
                        compared++
                        val md = abs(old.mean[oldIndex] - new.mean[newIndex])
                        if (ne < pe * 0.6f || (md > 45f && ne < pe + 6f)) lost++
                    } else if (ne >= CONTENT_T) {
                        added++
                    }
                }
            }
            if (compared == 0) continue
            val coverage = compared.toFloat() / prevContent
            // Do not call a tiny coincidental match a scroll; at least half the old content must align.
            if (shift != 0 && coverage < MIN_SCROLL_COVERAGE) continue
            val lostFraction = lost.toDouble() / compared
            // Prefer no movement when scores tie, while still allowing a real vertical translation to win.
            val score = lostFraction + abs(shift) * 0.001
            if (score < bestScore) {
                bestScore = score
                bestShift = shift
                bestLostFraction = lostFraction
                bestCoverage = coverage
                bestAdded = added
            }
        }

        // A good non-zero alignment means the same document moved vertically, not a new slide.
        if (bestShift != 0 && bestCoverage >= MIN_SCROLL_COVERAGE && bestLostFraction <= LOST_LIMIT) return 0
        return if (bestLostFraction > LOST_LIMIT) 2 else if (bestAdded >= MIN_ADDED) 1 else 0
    }

    // ---------------------------------------------------------------- saving

    /** Save a brand new screenshot (a new slide): the sharpest frame of the finished episode. */
    private fun startShot(c: Cand) {
        val t = max(0L, c.timeMs - startMs)
        val shot = Cur(noteId, t, c.feat)
        cur = shot
        lastSavedSig = c.signature
        lastSaveMs = System.currentTimeMillis()
        val bmp = c.bmp
        scope.launch(worker) {
            val dir = File(filesDir, "shots/${shot.noteId}").apply { mkdirs() }
            val file = File(dir, "$t.jpg")
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            shot.path = file.absolutePath
            shot.id = AppDb.get(this@CaptureService).dao()
                .insertShot(Shot(noteId = shot.noteId, path = shot.path, ocrText = "", timeMs = t))
            runOcr(shot, bmp, 0)
        }
    }

    /** The new frame has everything the old one had, plus more: swap the old screenshot for it. */
    private fun replaceShot(c: Cur, cand: Cand) {
        c.feat = cand.feat
        lastSavedSig = cand.signature
        c.version = c.version + 1
        val ver = c.version
        val t = System.currentTimeMillis() - startMs
        lastSaveMs = System.currentTimeMillis()
        val bmp = cand.bmp
        scope.launch(worker) {
            val old = File(c.path)
            val file = File(old.parentFile, "$t.jpg")
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
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
        dropBest()
        while (pending.isNotEmpty()) pending.removeFirst().recycle()
        display?.release()
        reader?.close()
        projection?.stop()
        running.value = false
        super.onDestroy()
    }
}
