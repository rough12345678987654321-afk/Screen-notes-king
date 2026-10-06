package com.example.screennotes

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

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
    var pendingUrl by remember { mutableStateOf("") }
    var urlInput by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
    var importStatus by remember { mutableStateOf("") }

    val captureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            ctx.startForegroundService(
                Intent(ctx, CaptureService::class.java).putExtra("code", result.resultCode).putExtra("data", result.data)
            )
            val url = pendingUrl
            if (url.isNotBlank()) {
                pendingUrl = ""
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
            }
        }
    }

    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importing = true
            importStatus = "Importing PDF pages..."
            scope.launch(Dispatchers.IO) {
                try {
                    val noteId = importPdfPages(ctx, dao) { ctx.contentResolver.openFileDescriptor(uri, "r") }
                    withContext(Dispatchers.Main) {
                        importing = false
                        if (noteId != null) onOpen(noteId) else importStatus = "Could not open that file."
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

    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Screen Notes", style = MaterialTheme.typography.headlineMedium)
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (running) {
                    Button(onClick = {
                        ctx.startService(Intent(ctx, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
                    }) { Text("Stop capturing") }
                    Text("Capturing... switch to your lecture or video now.")
                } else {
                    Button(onClick = {
                        val manager = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        captureLauncher.launch(manager.createScreenCaptureIntent())
                    }) { Text("Start capturing") }
                }
            }
        }
        item { AiSourcesCard(prefs) }
        item {
            OutlinedButton(
                enabled = !importing,
                onClick = { pdfLauncher.launch(arrayOf("application/pdf")) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (importing && importStatus.contains("PDF")) importStatus else "Import PDF / Slides")
            }
        }
        item {
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
                                    val connection = URL("https://drive.google.com/uc?export=download&id=$driveId")
                                        .openConnection() as HttpURLConnection
                                    connection.instanceFollowRedirects = true
                                    connection.connectTimeout = 20_000
                                    connection.readTimeout = 60_000
                                    val file = File(ctx.cacheDir, "drive-$driveId.pdf")
                                    file.outputStream().use { output -> connection.inputStream.use { it.copyTo(output) } }
                                    val magic = ByteArray(5)
                                    file.inputStream().use { it.read(magic) }
                                    if (String(magic, Charsets.US_ASCII) == "%PDF-") {
                                        importStatus = "Importing PDF pages..."
                                        val noteId = importPdfPages(ctx, dao) {
                                            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                                        }
                                        withContext(Dispatchers.Main) {
                                            importing = false
                                            if (noteId != null) onOpen(noteId) else importStatus = "Could not open that PDF."
                                        }
                                    } else {
                                        file.delete()
                                        withContext(Dispatchers.Main) {
                                            importing = false
                                            importStatus = "That Drive link is not a public PDF file. For videos and web pages, " +
                                                "put the link here and capture instead."
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
                            // Videos cannot be downloaded directly. Start capture, then open the supplied link.
                            pendingUrl = link
                            importStatus = "Accept the capture prompt; your link opens right after."
                            val manager = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                            captureLauncher.launch(manager.createScreenCaptureIntent())
                        }
                    }
                ) { Text("Import / Capture") }
            }
        }
        if (importStatus.isNotBlank()) {
            item {
                Text(
                    importStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        item { Text("History", style = MaterialTheme.typography.titleMedium) }
        items(notes, key = { it.id }) { note ->
            Card(onClick = { onOpen(note.id) }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(note.title)
                        Text(
                            java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale.getDefault())
                                .format(java.util.Date(note.createdAt)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = { scope.launch { deleteNote(ctx, note.id) } }) { Text("Delete") }
                }
            }
        }
    }
}

@Composable
private fun AiSourcesCard(prefs: android.content.SharedPreferences) {
    val scope = rememberCoroutineScope()
    var credentials by remember { mutableStateOf(AiNotesEngine.readKeys(prefs)) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var selectedModePref by remember { mutableStateOf(prefs.getString(AiNotesEngine.MODE_PREFERENCE, null)) }
    var tests by remember { mutableStateOf(AiNotesEngine.families().associateWith { "" }) }
    var testing by remember { mutableStateOf(emptySet<AiFamily>()) }
    val ready = AiNotesEngine.readySourceCount(credentials)
    val mode = when (selectedModePref) {
        "FAST" -> NotesMode.FAST
        "TOP_TIER" -> NotesMode.TOP_TIER
        else -> AiNotesEngine.defaultMode(credentials)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "AI sources ($ready/${AiNotesEngine.totalSources} ready)",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(if (expanded) "Hide" else "Set up")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = mode == NotesMode.TOP_TIER,
                    onClick = {
                        selectedModePref = "TOP_TIER"
                        prefs.edit().putString(AiNotesEngine.MODE_PREFERENCE, "TOP_TIER").apply()
                    },
                    label = { Text("Top tier when possible") }
                )
                FilterChip(
                    selected = mode == NotesMode.FAST,
                    onClick = {
                        selectedModePref = "FAST"
                        prefs.edit().putString(AiNotesEngine.MODE_PREFERENCE, "FAST").apply()
                    },
                    label = { Text("Fast (one AI)") }
                )
            }
            Text(
                if (mode == NotesMode.TOP_TIER)
                    "Top tier asks two providers for drafts, then a third AI to merge them against the OCR evidence."
                else "Fast uses the first available source and falls down the quality ladder if it is busy.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (expanded) {
                Text(
                    "Keys stay on this device. A saved key enables every model in that provider family.",
                    style = MaterialTheme.typography.bodySmall
                )
                AiNotesEngine.families().forEach { family ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "${family.title} · ${AiNotesEngine.sourceCount(family)} model${if (AiNotesEngine.sourceCount(family) == 1) "" else "s"}",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(family.hint, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (family.keyless) {
                                Text("No key required", modifier = Modifier.weight(1f))
                            } else {
                                OutlinedTextField(
                                    value = credentials[family].orEmpty(),
                                    onValueChange = { value ->
                                        credentials = credentials + (family to value)
                                        AiNotesEngine.saveKey(prefs, family, value)
                                    },
                                    label = { Text("API key") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                                )
                            }
                            OutlinedButton(
                                enabled = family !in testing,
                                onClick = {
                                    val key = credentials[family].orEmpty()
                                    if (!family.keyless && key.isBlank()) {
                                        tests = tests + (family to "Add a key first.")
                                    } else {
                                        testing = testing + family
                                        tests = tests + (family to "Testing…")
                                        scope.launch {
                                            val status = withContext(Dispatchers.IO) {
                                                AiNotesEngine.testFamily(family, key)
                                            }
                                            tests = tests + (family to status)
                                            testing = testing - family
                                        }
                                    }
                                }
                            ) { Text(if (family in testing) "…" else "Test") }
                        }
                        if (tests[family].orEmpty().isNotBlank()) {
                            Text(tests[family].orEmpty(), style = MaterialTheme.typography.bodySmall,
                                color = if (tests[family].orEmpty().startsWith("Connected"))
                                    MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
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
    var previewMode by remember { mutableStateOf("markdown") }
    var notice by remember { mutableStateOf("") }
    var confirmRegen by remember { mutableStateOf(false) }
    var pdfBusy by remember { mutableStateOf(false) }
    var zoomPath by remember { mutableStateOf<String?>(null) }

    // Captured slides keyed by the [Screenshot N] number used in notes and PDF exports.
    val shotData = remember(shots) {
        shots.mapIndexedNotNull { index, shot ->
            runCatching { File(shot.path).readBytes() }.getOrNull()?.let { bytes -> (index + 1) to bytes }
        }.toMap()
    }
    val shotImages = remember(shotData) {
        shotData.mapValues { AiNotesEngine.shrinkForUpload(it.value).let { bytes -> Base64.encodeToString(bytes, Base64.NO_WRAP) } }
    }

    val showNotesResult: (NotesResult) -> Unit = { result ->
        text = result.text
        val sourceLine = if (result.sources.isEmpty()) "" else "Sources: ${result.sources.joinToString(" → ")}"
        notice = listOf(result.notice, sourceLine).filter { it.isNotBlank() }.joinToString("\n")
    }

    val startPdfExport: () -> Unit = {
        if (!pdfBusy) {
            pdfBusy = true
            val fileName = ((note?.title ?: "ScreenNotes")
                .replace(Regex("[^A-Za-z0-9 -]"), "").trim().take(40).ifBlank { "ScreenNotes" }) + ".pdf"
            scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        PdfExport.save(ctx, fileName, text, note?.title ?: "Study Notes", shotData)
                    }
                    pdfBusy = false
                    Toast.makeText(ctx, "Saved ${result.path}", Toast.LENGTH_LONG).show()
                    try {
                        val openPdf = Intent(Intent.ACTION_VIEW)
                            .setDataAndType(result.uri, "application/pdf")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        ctx.startActivity(openPdf)
                    } catch (_: Exception) {
                        Toast.makeText(ctx, "PDF saved, but no PDF viewer is installed.", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    pdfBusy = false
                    Toast.makeText(
                        ctx,
                        e.message ?: "Could not save the PDF. Please try again.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
    val legacyStoragePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startPdfExport()
        else Toast.makeText(ctx, "Storage access is needed to save in Downloads.", Toast.LENGTH_LONG).show()
    }
    val savePdf: () -> Unit = {
        if (text.isBlank()) {
            Toast.makeText(ctx, "There are no notes to export yet. Generate or write notes first.", Toast.LENGTH_LONG).show()
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            legacyStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            startPdfExport()
        }
    }

    val regenerate: () -> Unit = {
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val slideList = shots.map { shot ->
                        Slide(shot.ocrText, runCatching { File(shot.path).readBytes() }.getOrNull())
                    }
                    val keys = AiNotesEngine.readKeys(prefs)
                    AiNotesEngine.makeNotes(keys, slideList, AiNotesEngine.configuredMode(prefs, keys))
                }.getOrElse { error ->
                    NotesResult("AI notes failed: ${error.message ?: "Unknown error"}", succeeded = false)
                }
            }
            showNotesResult(result)
            busy = false
        }
    }

    // Automatically try the keyless Pollinations route too when no provider keys are configured.
    LaunchedEffect(note?.id, shots.size) {
        val currentNote = note
        if (currentNote != null && currentNote.aiNotes.isBlank() && shots.isNotEmpty() && !busy) {
            busy = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val slideList = shots.map { shot ->
                        Slide(shot.ocrText, runCatching { File(shot.path).readBytes() }.getOrNull())
                    }
                    val keys = AiNotesEngine.readKeys(prefs)
                    AiNotesEngine.makeNotes(keys, slideList, AiNotesEngine.configuredMode(prefs, keys))
                }.getOrElse { error ->
                    NotesResult("AI notes failed: ${error.message ?: "Unknown error"}", succeeded = false)
                }
            }
            showNotesResult(result)
            busy = false
            if (result.succeeded) dao.updateNote(currentNote.copy(aiNotes = NotesClean.cleanNotes(result.text)))
        }
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = onBack) { Text("Back") }
                    Button(enabled = !busy && shots.isNotEmpty(), onClick = {
                        if (text.isNotBlank()) confirmRegen = true else regenerate()
                    }) { Text(if (busy) "Writing..." else "Make AI notes") }
                    Spacer(Modifier.weight(1f))
                }
                if (notice.isNotBlank()) {
                    Text(notice, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                Button(onClick = {
                    val cleanText = NotesClean.cleanNotes(text)
                    text = cleanText
                    note?.let { n -> scope.launch { dao.updateNote(n.copy(aiNotes = cleanText)) } }
                }) { Text("Save") }
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
