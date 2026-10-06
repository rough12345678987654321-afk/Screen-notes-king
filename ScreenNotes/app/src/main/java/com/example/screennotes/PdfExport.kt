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
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

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
        val doc = PdfDocument()
        var insertedUri: Uri? = null
        try {
            val pager = Pager(doc)
            pager.startPage()
            pager.y += 8f
            pager.layout(title, 20f, bold = true, spaceAfter = 14f)
            for (block in parseBlocks(safeMd)) pager.block(block, images)
            pager.finishPage()

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

    /** One A4 page at a time; blocks that do not fit flow to the next page. */
    private class Pager(private val doc: PdfDocument) {
        var y = MARGIN
        private var pageNo = 0
        private var page: PdfDocument.Page? = null
        private var canvas: Canvas? = null
        private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        private val bgPaint = Paint()
        private val contentW = (PAGE_W - 2 * MARGIN).toInt()

        fun startPage() {
            pageNo++
            page = doc.startPage(
                PdfDocument.PageInfo.Builder(PAGE_W.toInt(), PAGE_H.toInt(), pageNo).create()
            )
            canvas = page!!.canvas
            y = MARGIN
        }

        fun finishPage() {
            page?.let { doc.finishPage(it) }
            page = null
            canvas = null
        }

        private fun ensureSpace(height: Float) {
            if (y + height > PAGE_H - MARGIN) {
                finishPage()
                startPage()
            }
        }

        fun layout(
            text: String,
            size: Float,
            bold: Boolean,
            mono: Boolean = false,
            color: Int = Color.BLACK,
            bg: Boolean = false,
            spaceAfter: Float = 7f
        ) {
            if (text.isEmpty()) return
            paint.textSize = size
            paint.isFakeBoldText = bold
            paint.typeface = if (mono) Typeface.MONOSPACE else Typeface.DEFAULT
            paint.color = color
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, contentW)
                .setLineSpacing(0f, 1.25f).build()
            ensureSpace(layout.height.toFloat() + spaceAfter)
            val currentCanvas = canvas!!
            if (bg) {
                bgPaint.color = Color.parseColor("#f2f2f2")
                currentCanvas.drawRect(MARGIN - 6f, y - 4f, PAGE_W - MARGIN + 6f, y + layout.height + 4f, bgPaint)
            }
            currentCanvas.save()
            currentCanvas.translate(MARGIN, y)
            layout.draw(currentCanvas)
            currentCanvas.restore()
            y += layout.height + spaceAfter
        }

        fun image(bytes: ByteArray, spaceAfter: Float = 12f) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= contentW * 2) sample *= 2
            val bitmap = BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }
            ) ?: return
            val scale = minOf(contentW / bitmap.width.toFloat(), (PAGE_H - 2 * MARGIN) / bitmap.height.toFloat())
            val width = bitmap.width * scale
            val height = bitmap.height * scale
            ensureSpace(height + spaceAfter)
            canvas!!.drawBitmap(bitmap, null, RectF(MARGIN, y, MARGIN + width, y + height), null)
            y += height + spaceAfter
            bitmap.recycle()
        }

        fun block(block: MdBlock, images: Map<Int, ByteArray>) {
            when (block) {
                is MdBlock.Heading -> {
                    y += 6f
                    withImages(block.text, when (block.level) { 1 -> 18f; 2 -> 15f; else -> 13f }, true, images)
                    y += 4f
                }
                is MdBlock.Para -> withImages(block.text, 11f, false, images = images)
                is MdBlock.Bullet -> withImages("${block.marker} ${block.text}", 11f, false, images = images)
                is MdBlock.Code -> layout(block.text, 9f, bold = false, mono = true, bg = true)
                is MdBlock.Quote -> withImages(block.text, 11f, false, color = Color.GRAY, images = images)
                MdBlock.Rule -> {
                    ensureSpace(14f)
                    bgPaint.color = Color.LTGRAY
                    bgPaint.strokeWidth = 1f
                    canvas!!.drawLine(MARGIN, y, PAGE_W - MARGIN, y, bgPaint)
                    y += 14f
                }
            }
        }

        private val screenshotRef = Regex("""\[Screenshot (\d+)]""")

        private fun withImages(
            text: String,
            size: Float,
            bold: Boolean,
            images: Map<Int, ByteArray>,
            color: Int = Color.BLACK
        ) {
            var position = 0
            for (match in screenshotRef.findAll(text)) {
                if (match.range.first > position) layout(text.substring(position, match.range.first), size, bold, color = color)
                images[match.groupValues[1].toIntOrNull() ?: -1]?.let { image(it) }
                position = match.range.last + 1
            }
            if (position < text.length) layout(text.substring(position), size, bold, color = color)
        }
    }
}
