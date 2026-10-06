package com.example.screennotes

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PageRange
import android.provider.MediaStore
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.File

/**
 * Renders the notes HTML into a real PDF file and puts it in Downloads/ScreenNotes.
 * No print dialog and no print-framework support needed (the owner's tablet said
 * "not supported" about the print dialog), because the WebView print adapter is
 * driven straight into a file here.
 */
object PdfExport {

    /** A4 at 96 dpi: the page size the off-screen WebView is laid out at. */
    private const val PAGE_W = 794
    private const val PAGE_H = 1123

    fun save(ctx: Context, fileName: String, html: String, onDone: (msg: String?, err: String?) -> Unit) {
        try {
            val webView = WebView(ctx)
            webView.settings.javaScriptEnabled = false
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    view.post { render(ctx, view, fileName, onDone) }
                }
            }
            webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        } catch (e: Exception) {
            onDone(null, e.message ?: "WebView failed")
        }
    }

    private fun render(ctx: Context, view: WebView, fileName: String, onDone: (String?, String?) -> Unit) {
        try {
            view.measure(
                View.MeasureSpec.makeMeasureSpec(PAGE_W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(PAGE_H, View.MeasureSpec.EXACTLY)
            )
            view.layout(0, 0, PAGE_W, PAGE_H)
            val adapter = view.createPrintDocumentAdapter(fileName)
            val attrs = PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                .setResolution(PrintAttributes.Resolution("pdf", "screennotes", 72, 72))
                .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                .build()
            adapter.onLayout(null, attrs, CancellationSignal(), object : PrintDocumentAdapter.LayoutResultCallback() {
                override fun onLayoutFinished(info: PrintDocumentInfo?, changed: Boolean) {
                    writeToFile(ctx, adapter, fileName, onDone)
                }

                override fun onLayoutFailed(error: CharSequence?) {
                    onDone(null, error?.toString() ?: "PDF layout failed")
                }
            }, Bundle())
        } catch (e: Exception) {
            onDone(null, e.message ?: "PDF render failed")
        }
    }

    private fun writeToFile(
        ctx: Context,
        adapter: PrintDocumentAdapter,
        fileName: String,
        onDone: (String?, String?) -> Unit
    ) {
        val tmp = File(ctx.cacheDir, fileName)
        try {
            val pfd = ParcelFileDescriptor.open(
                tmp,
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or
                    ParcelFileDescriptor.MODE_READ_WRITE
            )
            adapter.onWrite(
                arrayOf(PageRange.ALL_PAGES), pfd, CancellationSignal(),
                object : PrintDocumentAdapter.WriteResultCallback() {
                    override fun onWriteFinished(pages: Array<out PageRange>?) {
                        pfd.close()
                        onDone(publish(ctx, tmp, fileName), null)
                    }

                    override fun onWriteFailed(error: CharSequence?) {
                        pfd.close()
                        onDone(null, error?.toString() ?: "PDF write failed")
                    }
                }
            )
        } catch (e: Exception) {
            onDone(null, e.message ?: "PDF write failed")
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
