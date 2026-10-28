package com.example.screennotes

import android.content.ContentValues
import android.content.Context
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

object PdfSplitterHelper {

    class SplitRange(val startPage: Int, val endPage: Int) {
        val name: String get() = "pages_${startPage}_to_${endPage}.pdf"
    }

    fun parseRanges(rangeText: String, totalPages: Int): List<SplitRange> {
        val trimmed = rangeText.trim()
        if (trimmed.isBlank()) {
            // Default: split every 20 pages or whole document if small
            val ranges = mutableListOf<SplitRange>()
            var p = 1
            while (p <= totalPages) {
                val end = minOf(p + 19, totalPages)
                ranges.add(SplitRange(p, end))
                p += 20
            }
            return ranges
        }

        val ranges = mutableListOf<SplitRange>()
        val parts = trimmed.split(Regex("[,;\\n]"))
        for (part in parts) {
            val pTrim = part.trim()
            if (pTrim.isBlank()) continue
            val dash = pTrim.split(Regex("[-–to]+"))
            if (dash.size == 2) {
                val start = dash[0].trim().toIntOrNull()
                val end = dash[1].trim().toIntOrNull()
                if (start != null && end != null) {
                    val s = start.coerceIn(1, totalPages)
                    val e = end.coerceIn(1, totalPages)
                    if (s <= e) ranges.add(SplitRange(s, e))
                }
            } else {
                val single = pTrim.toIntOrNull()
                if (single != null) {
                    val p = single.coerceIn(1, totalPages)
                    ranges.add(SplitRange(p, p))
                }
            }
        }
        return if (ranges.isNotEmpty()) ranges else parseRanges("", totalPages)
    }

    fun executeSplit(
        ctx: Context,
        pfd: ParcelFileDescriptor,
        ranges: List<SplitRange>,
        originalName: String
    ): List<PdfSaveResult> {
        val results = mutableListOf<PdfSaveResult>()
        val renderer = android.graphics.pdf.PdfRenderer(pfd)
        val stem = originalName.removeSuffix(".pdf").take(30)

        for ((index, range) in ranges.withIndex()) {
            val doc = PdfDocument()
            try {
                for (pageNum in range.startPage..range.endPage) {
                    val pageIndex = pageNum - 1
                    if (pageIndex < 0 || pageIndex >= renderer.pageCount) continue
                    val page = renderer.openPage(pageIndex)
                    val width = page.width
                    val height = page.height
                    val bmp = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                    page.render(
                        bmp,
                        null,
                        null,
                        android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                    )
                    page.close()
                    val pageInfo = PdfDocument.PageInfo.Builder(width, height, pageNum).create()
                    val pdfPage = doc.startPage(pageInfo)
                    pdfPage.canvas.drawBitmap(bmp, 0f, 0f, null)
                    bmp.recycle()
                    doc.finishPage(pdfPage)
                }

                val fileName = "${stem}_part${index + 1}_p${range.startPage}-${range.endPage}.pdf"
                val result = savePdfDoc(ctx, doc, fileName)
                results.add(result)
            } finally {
                doc.close()
            }
        }
        renderer.close()
        pfd.close()
        return results
    }

    private fun savePdfDoc(ctx: Context, doc: PdfDocument, fileName: String): PdfSaveResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/ScreenNotes")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create file in Downloads/ScreenNotes.")
            ctx.contentResolver.openOutputStream(uri, "w")?.use { output ->
                doc.writeTo(output)
            }
            val published = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            ctx.contentResolver.update(uri, published, null, null)
            return PdfSaveResult(uri, "Downloads/ScreenNotes/$fileName")
        }

        @Suppress("DEPRECATION")
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val folder = File(downloads, "ScreenNotes")
        if (!folder.exists()) folder.mkdirs()
        val target = File(folder, fileName)
        FileOutputStream(target).use { doc.writeTo(it) }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", target)
        return PdfSaveResult(uri, "Downloads/ScreenNotes/${target.name}")
    }
}
