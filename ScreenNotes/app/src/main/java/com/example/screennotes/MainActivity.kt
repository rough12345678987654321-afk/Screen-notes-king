package com.example.screennotes

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.graphics.Color
import android.util.Base64
import android.graphics.pdf.PdfDocument
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import java.io.FileOutputStream
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as UiColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.DateFormat
import java.util.Date

/** The owner's Gemini keys: one field, several free projects separated by commas or new lines. */
fun geminiKeys(prefs: android.content.SharedPreferences): List<String> =
    (prefs.getString("key", "") ?: "").split(Regex("[,;\n]")).map { it.trim() }.filter { it.isNotBlank() }

/** The model the owner picked for notes (default when nothing picked yet). */
fun geminiModel(prefs: android.content.SharedPreferences): String =
    prefs.getString("model", "")?.takeIf { it.isNotBlank() } ?: Gemini.DEFAULT_MODEL

/** Matches the file id in any shareable Google Drive file link. */
val DRIVE_FILE_ID = Regex("""(?:drive\.google\.com/(?:file/d/|open\?id=|uc\?id=)|[?&]id=)([-\w]{10,})""")

/** Reads up to 50 pages of a PDF into a new session's screenshots - on-device, zero API quota. */
suspend fun importPdfPages(ctx: Context, dao: NoteDao, open: () -> ParcelFileDescriptor?): Long? =
    withContext(Dispatchers.IO) {
        val pfd = open() ?: return@withContext null
        val renderer = PdfRenderer(pfd)
        val title = "PDF " + java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date())
        val nId = dao.insertNote(Note(title = title, createdAt = System.currentTimeMillis()))
        val shotsDir = File(ctx.filesDir, "shots/$nId").apply { mkdirs() }
        val pageCount = minOf(renderer.pageCount, 50)
        for (i in 0 until pageCount) {
            val page = renderer.openPage(i)
            val bmp = Bitmap.createBitmap(1280, (1280f * page.height / page.width).toInt(), Bitmap.Config.ARGB_8888)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            val file = File(shotsDir, "${i * 1000L}.jpg")
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            dao.insertShot(Shot(noteId = nId, path = file.absolutePath, ocrText = "PDF page ${i + 1}", timeMs = i * 1000L))
        }
        renderer.close()
        pfd.close()
        nId
    }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        openFromNotification(intent)
        UpdatePoller.start(this) // keeps an eye on running AI updates (only if GitHub is connected)
        setContent { MaterialTheme { App() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openFromNotification(intent)
    }

    /** A tap on an update notification opens that request in the Updates tab. */
    private fun openFromNotification(i: Intent?) {
        val n = i?.getIntExtra(UpdateNotifier.EXTRA_ISSUE, 0) ?: 0
        if (n > 0) {
            AppNav.openUpdate.value = n
            i?.removeExtra(UpdateNotifier.EXTRA_ISSUE)
        }
    }
}

/** Lets notifications open a specific update request. */
object AppNav {
    val openUpdate = MutableStateFlow<Int?>(null)
}

@Composable
fun App() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var openId by remember { mutableStateOf<Long?>(null) }
    var openRequest by rememberSaveable { mutableStateOf<Int?>(null) }
    val pending by AppNav.openUpdate.collectAsState()
    LaunchedEffect(pending) {
        pending?.let {
            tab = 1
            openRequest = it
            AppNav.openUpdate.value = null
        }
    }
    BackHandler(enabled = tab == 0 && openId != null) { openId = null }
    BackHandler(enabled = tab == 1 && openRequest == null) { tab = 0 }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.Edit, contentDescription = null) }, label = { Text("Notes") },
                )
                NavigationBarItem(
                    selected = tab == 1, onClick = { tab = 1 },
                    icon = { Icon(Icons.Default.Build, contentDescription = null) }, label = { Text("Updates") },
                )
            }
        }
    ) { inner ->
        Box(Modifier.padding(inner).statusBarsPadding().padding(16.dp)) {
            if (tab == 0) {
                val id = openId
                if (id == null) Home { openId = it } else Detail(id) { openId = null }
            } else {
                UpdatesTab(openRequest) { openRequest = it }
            }
        }
    }
}

@Composable
fun Home(onOpen: (Long) -> Unit) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).dao() }
    val notes by dao.notes().collectAsState(initial = emptyList())
    val running by CaptureService.running.collectAsState()
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("p", Context.MODE_PRIVATE) }
    var key by remember { mutableStateOf(prefs.getString("key", "") ?: "") }
    var pendingUrl by remember { mutableStateOf("") }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK && r.data != null) {
            ctx.startForegroundService(
                Intent(ctx, CaptureService::class.java).putExtra("code", r.resultCode).putExtra("data", r.data)
            )
            val url = pendingUrl
            if (url.isNotBlank()) {
                pendingUrl = ""
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Screen Notes", style = MaterialTheme.typography.headlineMedium)
        if (running) {
            Button(onClick = {
                ctx.startService(Intent(ctx, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
            }) { Text("Stop capturing") }
            Text("Capturing... switch to your lecture or video now.")
        } else {
            Button(onClick = {
                val mpm = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                launcher.launch(mpm.createScreenCaptureIntent())
            }) { Text("Start capturing") }
        }
        OutlinedTextField(
            value = key,
            onValueChange = { key = it; prefs.edit().putString("key", it).apply() },
            label = { Text("Gemini API key(s), free from aistudio.google.com/apikey - several keys? separate with commas") },
            modifier = Modifier.fillMaxWidth(), singleLine = true
        )
        var model by remember { mutableStateOf(geminiModel(prefs)) }
        var modelMenu by remember { mutableStateOf(false) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Notes AI model:", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { modelMenu = true }) { Text(model) }
            DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                Gemini.MODELS.forEach { m ->
                    DropdownMenuItem(
                        text = { Text(m) },
                        onClick = { model = m; prefs.edit().putString("model", m).apply(); modelMenu = false }
                    )
                }
            }
        }
        Text(
            "Free quota is per model and per Google project. When a limit is hit the app " +
                "automatically finishes the notes with the lighter flash-lite model and tells you.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        var urlInput by remember { mutableStateOf("") }
        var importing by remember { mutableStateOf(false) }
        var importStatus by remember { mutableStateOf("") }

        val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                importing = true
                importStatus = "Importing PDF pages..."
                scope.launch(Dispatchers.IO) {
                    try {
                        val nId = importPdfPages(ctx, dao) { ctx.contentResolver.openFileDescriptor(uri, "r") }
                        withContext(Dispatchers.Main) {
                            importing = false
                            if (nId != null) onOpen(nId) else importStatus = "Could not open that file."
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            importing = false
                            importStatus = "Error: ${e.message}"
                        }
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                enabled = !importing,
                onClick = { pdfLauncher.launch(arrayOf("application/pdf")) },
                modifier = Modifier.weight(1f)
            ) {
                Text(if (importing && importStatus.contains("PDF")) importStatus else "Import PDF / Slides")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = urlInput,
                onValueChange = { urlInput = it },
                label = { Text("Drive PDF link, or any video/web link to capture") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            Button(
                enabled = !importing && urlInput.isNotBlank(),
                onClick = {
                    val link = urlInput.trim()
                    urlInput = ""
                    val driveId = DRIVE_FILE_ID.find(link)?.groupValues?.get(1)
                    if (driveId != null) {
                        importing = true
                        importStatus = "Downloading from Drive..."
                        scope.launch(Dispatchers.IO) {
                            try {
                                val conn = URL("https://drive.google.com/uc?export=download&id=$driveId")
                                    .openConnection() as HttpURLConnection
                                conn.instanceFollowRedirects = true
                                conn.connectTimeout = 20000
                                conn.readTimeout = 60000
                                val f = File(ctx.cacheDir, "drive-$driveId.pdf")
                                f.outputStream().use { out -> conn.inputStream.use { it.copyTo(out) } }
                                val magic = ByteArray(5)
                                f.inputStream().use { it.read(magic) }
                                if (String(magic, Charsets.US_ASCII) == "%PDF-") {
                                    importStatus = "Importing PDF pages..."
                                    val nId = importPdfPages(ctx, dao) {
                                        ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
                                    }
                                    withContext(Dispatchers.Main) {
                                        importing = false
                                        if (nId != null) onOpen(nId)
                                        else importStatus = "Could not open that PDF."
                                    }
                                } else {
                                    f.delete()
                                    withContext(Dispatchers.Main) {
                                        importing = false
                                        importStatus = "That Drive link is not a public PDF file. " +
                                            "For videos and web pages, put the link here and capture instead."
                                    }
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    importing = false
                                    importStatus = "Drive download failed: ${e.message}"
                                }
                            }
                        }
                    } else {
                        // YouTube / any other site: videos cannot be downloaded - capture them instead.
                        pendingUrl = link
                        importStatus = "Accept the capture prompt - your link opens right after, " +
                            "and slides are captured while you watch."
                        val mpm = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        launcher.launch(mpm.createScreenCaptureIntent())
                    }
                }
            ) {
                Text("Import / Capture")
            }
        }
        if (importing && !importStatus.contains("PDF")) {
            Text(importStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        Text("History", style = MaterialTheme.typography.titleMedium)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(notes) { n ->
                Card(onClick = { onOpen(n.id) }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(n.title)
                            Text(
                                java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(n.createdAt)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = { scope.launch { deleteNote(ctx, n.id) } }) { Text("Delete") }
                    }
                }
            }
        }
    }
}

suspend fun deleteNote(ctx: Context, id: Long) = withContext(Dispatchers.IO) {
    val dao = AppDb.get(ctx).dao()
    File(ctx.filesDir, "shots/$id").deleteRecursively()
    dao.deleteShots(id)
    dao.deleteNote(id)
}

@Composable
fun Detail(id: Long, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).dao() }
    val note by dao.note(id).collectAsState(initial = null)
    val shots by dao.shots(id).collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("p", Context.MODE_PRIVATE) }
    var text by remember(note?.id) { mutableStateOf(note?.aiNotes ?: "") }
    var busy by remember { mutableStateOf(false) }
    var previewMode by remember { mutableStateOf("markdown") } // "markdown", "html", "images"
    var notice by remember { mutableStateOf("") }
    var confirmRegen by remember { mutableStateOf(false) }
    var pdfBusy by remember { mutableStateOf(false) }
    var pdfMsg by remember { mutableStateOf("") }
    var zoomPath by remember { mutableStateOf<String?>(null) }

    // base64 of every captured slide (downscaled), keyed by the [Screenshot N] number
    val shotImages = remember(shots) {
        shots.mapIndexedNotNull { i, sh ->
            runCatching { File(sh.path).readBytes() }.getOrNull()
                ?.let { b -> (i + 1) to Base64.encodeToString(Gemini.shrinkForUpload(b), Base64.NO_WRAP) }
        }.toMap()
    }
    val savePdf: () -> Unit = {
        pdfBusy = true
        pdfMsg = "Making PDF..."
        val html = markdownToHtml(note?.title ?: "Study Notes", text, shotImages)
        val name = ((note?.title ?: "ScreenNotes").replace(Regex("[^A-Za-z0-9 -]"), "").trim().take(40)
            .ifBlank { "ScreenNotes" }) + ".pdf"
        PdfExport.save(ctx, name, html) { msg, err ->
            pdfBusy = false
            pdfMsg = msg ?: ("PDF failed: $err")
        }
    }

    // (Re)generate the notes with the owner's keys and model; reports a fallback honestly.
    val regenerate: () -> Unit = {
        busy = true
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                try {
                    val slides = shots.map { s ->
                        Slide(s.ocrText, runCatching { File(s.path).readBytes() }.getOrNull())
                    }
                    Gemini.makeNotes(geminiKeys(prefs), slides, geminiModel(prefs))
                } catch (e: Exception) {
                    NotesResult("Error: ${e.message}", geminiModel(prefs), false)
                }
            }
            text = res.text
            notice = if (res.fellBack) {
                "Your main model's free quota was used up, so ${res.model} (lighter) wrote these notes."
            } else ""
            busy = false
        }
    }

    // Auto-generate AI notes when opening the page if aiNotes is empty and shots are available
    LaunchedEffect(note?.id, shots.size) {
        if (note != null && note!!.aiNotes.isBlank() && shots.isNotEmpty() && !busy) {
            val keys = geminiKeys(prefs)
            if (keys.isNotEmpty()) {
                busy = true
                val res = withContext(Dispatchers.IO) {
                    try {
                        val slideList = shots.map { s ->
                            Slide(s.ocrText, runCatching { File(s.path).readBytes() }.getOrNull())
                        }
                        Gemini.makeNotes(keys, slideList, geminiModel(prefs))
                    } catch (e: Exception) {
                        NotesResult("Error: ${e.message}", geminiModel(prefs), false)
                    }
                }
                text = res.text
                notice = if (res.fellBack) {
                    "Your main model's free quota was used up, so ${res.model} (lighter) wrote these notes."
                } else ""
                busy = false
                dao.updateNote(note!!.copy(aiNotes = res.text))
            }
        }
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = onBack) { Text("Back") }
                    Button(enabled = !busy && shots.isNotEmpty(), onClick = {
                        if (geminiKeys(prefs).isEmpty()) {
                            text = "Paste your Gemini API key on the home screen first."
                            return@Button
                        }
                        if (text.isNotBlank()) confirmRegen = true else regenerate()
                    }) { Text(if (busy) "Writing..." else "Make AI notes") }
                    Spacer(Modifier.weight(1f))
                }
                if (notice.isNotBlank()) {
                    Text(notice, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (pdfMsg.isNotBlank()) {
                    Text(pdfMsg, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                }
                if (confirmRegen) {
                    AlertDialog(
                        onDismissRequest = { confirmRegen = false },
                        title = { Text("Rewrite these notes?") },
                        text = { Text("The AI replaces the current notes (including your edits) with a fresh version.") },
                        confirmButton = {
                            TextButton(onClick = { confirmRegen = false; regenerate() }) { Text("Rewrite") }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmRegen = false }) { Text("Keep mine") }
                        }
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = previewMode == "markdown", onClick = { previewMode = "markdown" }, label = { Text("Preview") })
                    FilterChip(selected = previewMode == "html", onClick = { previewMode = "html" }, label = { Text("HTML view") })
                    FilterChip(selected = previewMode == "images", onClick = { previewMode = "images" }, label = { Text("Images & Sources") })
                    FilterChip(selected = previewMode == "edit", onClick = { previewMode = "edit" }, label = { Text("Edit") })
                }
            }
        }
        item {
            when (previewMode) {
                "markdown" -> {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Notes Preview", style = MaterialTheme.typography.titleMedium)
                            HorizontalDivider()
                            if (text.isBlank()) {
                                Text("No notes yet. Tap \"Make AI notes\" above.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                MarkdownText(text)
                            }
                        }
                    }
                }
                "html" -> {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("HTML Preview & PDF Export", style = MaterialTheme.typography.titleMedium)
                            HorizontalDivider()
                            val htmlContent = remember(text, shotImages) {
                                markdownToHtml(note?.title ?: "Study Notes", text, shotImages)
                            }
                            Box(Modifier.fillMaxWidth().height(350.dp)) {
                                AndroidView(
                                    factory = { c ->
                                        WebView(c).apply {
                                            webViewClient = WebViewClient()
                                            settings.javaScriptEnabled = true
                                        }
                                    },
                                    update = { webView ->
                                        webView.loadDataWithBaseURL(null, htmlContent, "text/html", "UTF-8", null)
                                    },
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            Button(enabled = !pdfBusy, onClick = savePdf, modifier = Modifier.fillMaxWidth()) {
                                Text("Save as PDF file")
                            }
                        }
                    }
                }
                "images" -> {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Captured screens & Google sources (${shots.size})", style = MaterialTheme.typography.titleMedium)
                        if (shots.isEmpty()) {
                            Text("No captured screens.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            shots.forEachIndexed { i, s ->
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("Screenshot ${i + 1}", style = MaterialTheme.typography.titleSmall)
                                        AsyncImage(
                                            model = File(s.path), contentDescription = "Screenshot ${i + 1}",
                                            modifier = Modifier.fillMaxWidth().clickable { zoomPath = s.path }
                                        )
                                        if (s.ocrText.isNotBlank()) Text(s.ocrText, style = MaterialTheme.typography.bodySmall)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                            val query = s.ocrText.take(60).trim().ifBlank { note?.title ?: "JEE study" }
                                            OutlinedButton(onClick = {
                                                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com/search?q=${android.net.Uri.encode(query)}"))
                                                ctx.startActivity(intent)
                                            }) { Text("Search on Google") }
                                            Spacer(Modifier.weight(1f))
                                            TextButton(onClick = {
                                                scope.launch(Dispatchers.IO) { File(s.path).delete(); dao.deleteShot(s) }
                                            }) { Text("Delete this screenshot") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                else -> {
                    OutlinedTextField(
                        value = text, onValueChange = { text = it },
                        label = { Text("Your notes (editable)") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp)
                    )
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { note?.let { n -> scope.launch { dao.updateNote(n.copy(aiNotes = text)) } } }) { Text("Save") }
                OutlinedButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                    ctx.startActivity(Intent.createChooser(send, "Share notes"))
                }) { Text("Share / Export") }
                OutlinedButton(enabled = !pdfBusy, onClick = savePdf) { Text("Save as PDF") }
            }
        }
    }

    val zp = zoomPath
    if (zp != null) {
        Dialog(onDismissRequest = { zoomPath = null }) {
            Box(
                Modifier.fillMaxSize().background(UiColor.Black.copy(alpha = 0.85f))
                    .clickable { zoomPath = null }
            ) {
                AsyncImage(model = File(zp), contentDescription = null, modifier = Modifier.fillMaxSize())
            }
        }
    }
}
