package com.example.screennotes

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.min

/** The completed file's content URI and visible Downloads path. */
data class PdfSaveResult(val uri: Uri, val path: String)

/**
 * Builds and writes a PDF directly to Downloads/ScreenNotes with PdfDocument. Call from an IO
 * dispatcher; there is deliberately no Android print framework or print dialog involved.
 */
object PdfExport {
    private const val PAGE_W = 595f // A4 in points
    private const val PAGE_H = 842f
    private const val MARGIN = 40f
    private const val BOTTOM_MARGIN = 45f
    private const val AUTHORITY_SUFFIX = ".files"

    /** [images] maps [Screenshot N] references to the original captured JPEG bytes. */
    fun save(
        ctx: Context,
        fileName: String,
        md: String,
        title: String,
        images: Map<Int, ByteArray>
    ): PdfSaveResult {
        require(md.isNotBlank()) { "There are no notes to export yet. Generate or write notes first." }
        val safeMd = NotesClean.cleanNotes(md)

        // Deduplicate captures before embedding: compare consecutive captures visually
        val (filteredImages, keptIndices) = deduplicateImages(images)

        // Drop [Screenshot N] references to skipped duplicates so surrounding text flows smoothly
        val flowMd = dropSkippedScreenshotRefs(safeMd, keptIndices)

        val doc = PdfDocument()
        var insertedUri: Uri? = null
        try {
            renderPdf(doc, title, flowMd, filteredImages)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/ScreenNotes")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("Android could not create a file in Downloads/ScreenNotes.")
                insertedUri = uri
                val output = ctx.contentResolver.openOutputStream(uri, "w")
                    ?: error("Android could not open the new PDF file.")
                output.use { doc.writeTo(it) }
                val published = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                ctx.contentResolver.update(uri, published, null, null)
                return PdfSaveResult(uri, "Downloads/ScreenNotes/$fileName")
            }

            @Suppress("DEPRECATION")
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val folder = File(downloads, "ScreenNotes")
            if (!folder.exists() && !folder.mkdirs()) error("Could not create Downloads/ScreenNotes.")
            val target = uniqueFile(folder, fileName)
            FileOutputStream(target).use { doc.writeTo(it) }
            val uri = FileProvider.getUriForFile(
                ctx, ctx.packageName + AUTHORITY_SUFFIX, target
            )
            return PdfSaveResult(uri, "Downloads/ScreenNotes/${target.name}")
        } catch (e: Exception) {
            insertedUri?.let { runCatching { ctx.contentResolver.delete(it, null, null) } }
            throw e
        } finally {
            doc.close()
        }
    }

    private fun uniqueFile(folder: File, requested: String): File {
        val original = File(folder, requested)
        if (!original.exists()) return original
        val stem = requested.removeSuffix(".pdf")
        var suffix = 2
        while (true) {
            val candidate = File(folder, "$stem ($suffix).pdf")
            if (!candidate.exists()) return candidate
            suffix++
        }
    }

    /**
     * Deduplicates consecutive captures visually (~32x18 grayscale, mean abs diff < 3 = same photo).
     * Embeds only the first of each run.
     */
    private fun deduplicateImages(images: Map<Int, ByteArray>): Pair<Map<Int, ByteArray>, Set<Int>> {
        val sortedKeys = images.keys.sorted()
        val keptImages = mutableMapOf<Int, ByteArray>()
        val keptIndices = mutableSetOf<Int>()

        var prevSig: IntArray? = null
        for (k in sortedKeys) {
            val bytes = images[k] ?: continue
            val sig = imageSignature(bytes)
            if (sig != null) {
                if (prevSig != null && signatureDiff(prevSig, sig) < 3.0) {
                    // Duplicate screenshot in this run; skip embedding
                    continue
                }
                prevSig = sig
            }
            keptImages[k] = bytes
            keptIndices.add(k)
        }
        return keptImages to keptIndices
    }

    private fun imageSignature(bytes: ByteArray): IntArray? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 64) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
            val thumb = Bitmap.createScaledBitmap(bmp, 32, 18, true)
            if (thumb != bmp) bmp.recycle()
            val px = IntArray(32 * 18)
            thumb.getPixels(px, 0, 32, 0, 0, 32, 18)
            thumb.recycle()
            IntArray(px.size) { i ->
                val c = px[i]
                (((c shr 16) and 0xFF) * 3 + ((c shr 8) and 0xFF) * 6 + (c and 0xFF)) / 10
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun signatureDiff(a: IntArray, b: IntArray): Double {
        var sum = 0L
        for (i in a.indices) sum += abs(a[i] - b[i])
        return sum.toDouble() / a.size
    }

    private fun dropSkippedScreenshotRefs(text: String, keptIndices: Set<Int>): String {
        val pattern = Regex("""\[Screenshot\s+(\d+)\]""", RegexOption.IGNORE_CASE)
        val replaced = pattern.replace(text) { m ->
            val idx = m.groupValues[1].toIntOrNull()
            if (idx != null && idx in keptIndices) m.value else ""
        }
        return replaced
            .replace(Regex("""[ \t]{2,}"""), " ")
            .replace(Regex(""" +([,.:;!?])"""), "$1")
    }

    private sealed class PdfItem {
        abstract val height: Float

        data class TextBlock(
            val layout: StaticLayout,
            val spaceBefore: Float = 0f,
            val spaceAfter: Float = 6f,
            val isCode: Boolean = false
        ) : PdfItem() {
            override val height: Float get() = spaceBefore + layout.height + spaceAfter
        }

        data class ImageBlock(
            val bitmap: Bitmap,
            val width: Float,
            val imgHeight: Float,
            val spaceBefore: Float = 4f,
            val spaceAfter: Float = 10f
        ) : PdfItem() {
            override val height: Float get() = spaceBefore + imgHeight + spaceAfter
        }

        data class RuleBlock(
            val spaceBefore: Float = 8f,
            val spaceAfter: Float = 8f
        ) : PdfItem() {
            override val height: Float get() = spaceBefore + spaceAfter
        }
    }

    private fun renderPdf(doc: PdfDocument, title: String, md: String, images: Map<Int, ByteArray>) {
        val contentW = (PAGE_W - 2 * MARGIN).toInt()
        val maxContentH = PAGE_H - MARGIN - BOTTOM_MARGIN
        val maxImgH = maxContentH * 0.45f

        val items = mutableListOf<PdfItem>()

        // Title
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 20f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            color = Color.BLACK
        }
        val titleLayout = StaticLayout.Builder.obtain(title, 0, title.length, titlePaint, contentW)
            .setLineSpacing(0f, 1.25f).build()
        items.add(PdfItem.TextBlock(titleLayout, spaceBefore = 4f, spaceAfter = 14f))

        fun buildSpan(
            text: String,
            size: Float,
            baseBold: Boolean = false,
            baseItalic: Boolean = false,
            color: Int = Color.BLACK
        ): CharSequence {
            val ssb = SpannableStringBuilder()
            val runs = parseMarkdownSpans(text)
            for (r in runs) {
                val start = ssb.length
                ssb.append(r.text)
                val isBold = baseBold || r.bold
                val isItalic = baseItalic || r.italic
                if (isBold && isItalic) {
                    ssb.setSpan(StyleSpan(Typeface.BOLD_ITALIC), start, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                } else if (isBold) {
                    ssb.setSpan(StyleSpan(Typeface.BOLD), start, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                } else if (isItalic) {
                    ssb.setSpan(StyleSpan(Typeface.ITALIC), start, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                if (r.code) {
                    ssb.setSpan(TypefaceSpan("monospace"), start, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            return ssb
        }

        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

        fun createLayout(cs: CharSequence, size: Float, mono: Boolean = false, color: Int = Color.BLACK): StaticLayout {
            textPaint.textSize = size
            textPaint.typeface = if (mono) Typeface.MONOSPACE else Typeface.DEFAULT
            textPaint.color = color
            return StaticLayout.Builder.obtain(cs, 0, cs.length, textPaint, contentW)
                .setLineSpacing(0f, 1.25f).build()
        }

        val screenshotRef = Regex("""\[Screenshot\s+(\d+)\]""", RegexOption.IGNORE_CASE)

        fun addTextWithImages(rawText: String, size: Float, bold: Boolean, color: Int = Color.BLACK, spaceAfter: Float = 6f) {
            var pos = 0
            for (match in screenshotRef.findAll(rawText)) {
                if (match.range.first > pos) {
                    val sub = rawText.substring(pos, match.range.first).trim()
                    if (sub.isNotEmpty()) {
                        val cs = buildSpan(sub, size, baseBold = bold, color = color)
                        items.add(PdfItem.TextBlock(createLayout(cs, size, color = color), spaceAfter = 4f))
                    }
                }
                val idx = match.groupValues[1].toIntOrNull()
                if (idx != null && images.containsKey(idx)) {
                    val bytes = images[idx]!!
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                        var sample = 1
                        while (bounds.outWidth / (sample * 2) >= contentW * 2) sample *= 2
                        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                        if (bmp != null) {
                            val wScale = contentW / bmp.width.toFloat()
                            val hScale = maxImgH / bmp.height.toFloat()
                            val finalScale = minOf(wScale, hScale)
                            val w = bmp.width * finalScale
                            val h = bmp.height * finalScale
                            items.add(PdfItem.ImageBlock(bmp, w, h))
                        }
                    }
                }
                pos = match.range.last + 1
            }
            if (pos < rawText.length) {
                val sub = rawText.substring(pos).trim()
                if (sub.isNotEmpty()) {
                    val cs = buildSpan(sub, size, baseBold = bold, color = color)
                    items.add(PdfItem.TextBlock(createLayout(cs, size, color = color), spaceAfter = spaceAfter))
                }
            }
        }

        val blocks = parseBlocks(md)
        for (b in blocks) {
            when (b) {
                is MdBlock.Heading -> {
                    val size = when (b.level) { 1 -> 18f; 2 -> 15f; else -> 13f }
                    addTextWithImages(b.text, size, bold = true, spaceAfter = 6f)
                }
                is MdBlock.Para -> {
                    addTextWithImages(b.text, 11f, bold = false, spaceAfter = 6f)
                }
                is MdBlock.Bullet -> {
                    val marker = if (b.marker.isBlank()) "•" else b.marker
                    val indent = "  ".repeat(b.indent)
                    addTextWithImages("$indent$marker ${b.text}", 11f, bold = false, spaceAfter = 4f)
                }
                is MdBlock.Code -> {
                    val codeLayout = createLayout(b.text, 9f, mono = true, color = Color.DKGRAY)
                    items.add(PdfItem.TextBlock(codeLayout, spaceBefore = 4f, spaceAfter = 8f, isCode = true))
                }
                is MdBlock.Quote -> {
                    addTextWithImages(b.text, 11f, bold = false, color = Color.GRAY, spaceAfter = 6f)
                }
                is MdBlock.Rule -> {
                    items.add(PdfItem.RuleBlock(spaceBefore = 6f, spaceAfter = 6f))
                }
            }
        }

        // Pagination with deferred image logic
        val pages = paginatePdf(items, maxContentH)

        val bgPaint = Paint()
        val linePaint = Paint().apply {
            color = Color.LTGRAY
            strokeWidth = 1f
        }
        val footerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 10f
            color = Color.GRAY
            textAlign = Paint.Align.CENTER
        }

        for ((pageIndex, pageItems) in pages.withIndex()) {
            val pageNo = pageIndex + 1
            val pageInfo = PdfDocument.PageInfo.Builder(PAGE_W.toInt(), PAGE_H.toInt(), pageNo).create()
            val page = doc.startPage(pageInfo)
            val canvas = page.canvas
            var currY = MARGIN

            for (item in pageItems) {
                when (item) {
                    is PdfItem.TextBlock -> {
                        currY += item.spaceBefore
                        if (item.isCode) {
                            bgPaint.color = Color.parseColor("#EEEEEE")
                            canvas.drawRoundRect(
                                MARGIN - 4f,
                                currY - 3f,
                                PAGE_W - MARGIN + 4f,
                                currY + item.layout.height + 4f,
                                4f, 4f,
                                bgPaint
                            )
                        }
                        canvas.save()
                        canvas.translate(MARGIN, currY)
                        item.layout.draw(canvas)
                        canvas.restore()
                        currY += item.layout.height + item.spaceAfter
                    }
                    is PdfItem.ImageBlock -> {
                        currY += item.spaceBefore
                        val left = MARGIN + (contentW - item.width) / 2f
                        canvas.drawBitmap(
                            item.bitmap,
                            null,
                            RectF(left, currY, left + item.width, currY + item.imgHeight),
                            null
                        )
                        currY += item.imgHeight + item.spaceAfter
                    }
                    is PdfItem.RuleBlock -> {
                        currY += item.spaceBefore
                        canvas.drawLine(MARGIN, currY, PAGE_W - MARGIN, currY, linePaint)
                        currY += item.spaceAfter
                    }
                }
            }

            // Page numbers centered at the bottom of every page
            canvas.drawText("Page $pageNo of ${pages.size}", PAGE_W / 2f, PAGE_H - 18f, footerPaint)
            doc.finishPage(page)
        }

        // Recycle bitmaps after rendering is complete
        for (item in items) {
            if (item is PdfItem.ImageBlock && !item.bitmap.isRecycled) {
                item.bitmap.recycle()
            }
        }
    }

    private fun paginatePdf(items: List<PdfItem>, pageCapacity: Float): List<List<PdfItem>> {
        val pages = mutableListOf<List<PdfItem>>()
        val currentPage = mutableListOf<PdfItem>()
        var currentY = 0f

        val pending = items.toMutableList()

        while (pending.isNotEmpty()) {
            val head = pending[0]
            val spaceLeft = pageCapacity - currentY

            if (head.height <= spaceLeft) {
                currentPage.add(pending.removeAt(0))
                currentY += head.height
            } else if (head is PdfItem.ImageBlock) {
                // When an image doesn't fit, defer it to the next page but first fill leftover space with text blocks
                val imageItem = pending.removeAt(0)
                var i = 0
                while (i < pending.size) {
                    val candidate = pending[i]
                    if (candidate !is PdfItem.ImageBlock && candidate.height <= (pageCapacity - currentY)) {
                        currentPage.add(pending.removeAt(i))
                        currentY += candidate.height
                    } else {
                        i++
                    }
                }
                pages.add(currentPage.toList())
                currentPage.clear()
                currentPage.add(imageItem)
                currentY = imageItem.height
            } else {
                if (currentPage.isEmpty()) {
                    // Item larger than whole page, place it anyway
                    currentPage.add(pending.removeAt(0))
                    pages.add(currentPage.toList())
                    currentPage.clear()
                    currentY = 0f
                } else {
                    pages.add(currentPage.toList())
                    currentPage.clear()
                    currentY = 0f
                }
            }
        }

        if (currentPage.isNotEmpty()) {
            pages.add(currentPage.toList())
        }
        return pages
    }

    private data class SpanRun(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val code: Boolean = false
    )

    private val INLINE_PATTERN = Regex(
        """`([^`]+)`|""" +
            """\*\*([^*]+)\*\*|__([^_]+)__|""" +
            """(?<![\w*])\*([^*\s](?:[^*]*?[^*\s])?)\*(?![\w*])|""" +
            """(?<![\w_])_([^_\s](?:[^_]*?[^_\s])?)_(?![\w_])"""
    )

    private fun parseMarkdownSpans(text: String): List<SpanRun> {
        val runs = mutableListOf<SpanRun>()
        var lastIdx = 0
        for (m in INLINE_PATTERN.findAll(text)) {
            if (m.range.first > lastIdx) {
                runs.add(SpanRun(text.substring(lastIdx, m.range.first)))
            }
            val g = m.groupValues
            when {
                g[1].isNotEmpty() -> runs.add(SpanRun(g[1], code = true))
                g[2].isNotEmpty() -> runs.add(SpanRun(g[2], bold = true))
                g[3].isNotEmpty() -> runs.add(SpanRun(g[3], bold = true))
                g[4].isNotEmpty() -> runs.add(SpanRun(g[4], italic = true))
                g[5].isNotEmpty() -> runs.add(SpanRun(g[5], italic = true))
            }
            lastIdx = m.range.last + 1
        }
        if (lastIdx < text.length) {
            runs.add(SpanRun(text.substring(lastIdx)))
        }
        return runs
    }
}
