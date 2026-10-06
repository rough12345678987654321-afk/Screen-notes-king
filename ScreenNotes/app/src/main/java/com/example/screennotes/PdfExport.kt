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
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.StaticLayout
import java.io.File

/**
 * Writes the notes to a real PDF file in Downloads/ScreenNotes - no print dialog and no
 * print-framework support needed (the owner's tablet answered "not supported" to the print
 * dialog). Renders the Markdown blocks and the captured slides straight onto PDF pages.
 */
object PdfExport {

    private const val PAGE_W = 595f // A4 in points
    private const val PAGE_H = 842f
    private const val MARGIN = 40f

    /** [images] maps the [Screenshot N] numbers to the captured JPEG bytes. */
    fun save(
        ctx: Context,
        fileName: String,
        md: String,
        title: String,
        images: Map<Int, ByteArray>,
        onDone: (msg: String?, err: String?) -> Unit
    ) {
        try {
            val doc = PdfDocument()
            val pager = Pager(doc)
            pager.startPage()
            pager.y += 8f
            pager.layout(title, 20f, bold = true, spaceAfter = 14f)
            for (b in parseBlocks(md)) pager.block(b, images)
            pager.finishPage()
            val tmp = File(ctx.cacheDir, fileName)
            tmp.outputStream().use { doc.write(it) }
            doc.close()
            onDone(publish(ctx, tmp, fileName), null)
        } catch (e: Exception) {
            onDone(null, e.message ?: "PDF failed")
        }
    }

    /** One A4 page at a time; blocks that do not fit flow onto the next page. */
    private class Pager(private val doc: PdfDocument) {
        var y = MARGIN
        private var page: PdfDocument.Page? = null
        private var canvas: Canvas? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bgPaint = Paint()
        private val contentW = (PAGE_W - 2 * MARGIN).toInt()

        fun startPage() {
            page = doc.startPage(
                PdfDocument.PageInfo.Builder(PAGE_W.toInt(), PAGE_H.toInt(), doc.pageCount + 1).create()
            )
            canvas = page!!.canvas
            y = MARGIN
        }

        fun finishPage() {
            page?.let { doc.finishPage(it) }
            page = null
            canvas = null
        }

        private fun ensureSpace(h: Float) {
            if (y + h > PAGE_H - MARGIN) {
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
            val sl = StaticLayout.Builder.obtain(text, 0, text.length, paint, contentW)
                .setLineSpacing(0f, 1.25f).build()
            ensureSpace(sl.height.toFloat() + spaceAfter)
            val c = canvas!!
            if (bg) {
                bgPaint.color = Color.parseColor("#f2f2f2")
                c.drawRect(MARGIN - 6f, y - 4f, PAGE_W - MARGIN + 6f, y + sl.height + 4f, bgPaint)
            }
            c.save()
            c.translate(MARGIN, y)
            sl.draw(c)
            c.restore()
            y += sl.height + spaceAfter
        }

        fun image(bytes: ByteArray, spaceAfter: Float = 12f) {
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
            val scale = contentW / bmp.width.toFloat()
            val h = bmp.height * scale
            ensureSpace(h + spaceAfter)
            canvas!!.drawBitmap(bmp, null, RectF(MARGIN, y, MARGIN + contentW, y + h), null)
            y += h + spaceAfter
            bmp.recycle()
        }

        /** Draws one Markdown block; [Screenshot N] inside it becomes the actual slide. */
        fun block(b: MdBlock, images: Map<Int, ByteArray>) {
            when (b) {
                is MdBlock.Heading -> {
                    y += 6f
                    withImages(b.text, when (b.level) { 1 -> 18f; 2 -> 15f; else -> 13f }, bold = true, images)
                    y += 4f
                }
                is MdBlock.Para -> withImages(b.text, 11f, bold = false, images = images)
                is MdBlock.Bullet -> withImages("${b.marker} ${b.text}", 11f, bold = false, images = images)
                is MdBlock.Code -> layout(b.text, 9f, bold = false, mono = true, bg = true)
                is MdBlock.Quote -> withImages(b.text, 11f, bold = false, color = Color.GRAY, images = images)
                MdBlock.Rule -> {
                    ensureSpace(14f)
                    bgPaint.color = Color.LTGRAY
                    bgPaint.strokeWidth = 1f
                    canvas!!.drawLine(MARGIN, y, PAGE_W - MARGIN, y, bgPaint)
                    y += 14f
                }
            }
        }

        private val SHOT_REF = Regex("""\[Screenshot (\d+)]""")

        private fun withImages(
            text: String,
            size: Float,
            bold: Boolean,
            images: Map<Int, ByteArray>,
            color: Int = Color.BLACK
        ) {
            var pos = 0
            for (m in SHOT_REF.findAll(text)) {
                if (m.range.first > pos) layout(text.substring(pos, m.range.first), size, bold, color = color)
                images[m.groupValues[1].toIntOrNull() ?: -1]?.let { image(it) }
                pos = m.range.last + 1
            }
            if (pos < text.length) layout(text.substring(pos), size, bold, color = color)
        }
    }

    /** Copies the finished PDF into the public Downloads folder (Android 10+), no permission needed. */
    private fun publish(ctx: Context, tmp: File, fileName: String): String {
        if (Build.VERSION.SDK_INT >= 29) {
            return try {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/ScreenNotes")
                }
                val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return "PDF ready, but Downloads refused it - use Share / Export instead."
                ctx.contentResolver.openOutputStream(uri)?.use { out ->
                    tmp.inputStream().use { it.copyTo(out) }
                }
                "Saved: Downloads/ScreenNotes/$fileName"
            } catch (e: Exception) {
                "PDF ready, but copying to Downloads failed: ${e.message}"
            }
        }
        return "PDF ready inside the app (Android 9 keeps it private) - use Share / Export to send it."
    }
}
