package com.example.screennotes

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK && r.data != null) {
            ctx.startForegroundService(
                Intent(ctx, CaptureService::class.java).putExtra("code", r.resultCode).putExtra("data", r.data)
            )
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
            label = { Text("Gemini API key (free, from aistudio.google.com/apikey)") },
            modifier = Modifier.fillMaxWidth(), singleLine = true
        )
        Text("History", style = MaterialTheme.typography.titleMedium)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(notes) { n ->
                Card(onClick = { onOpen(n.id) }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(n.title, Modifier.weight(1f))
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

    // Auto-generate AI notes when opening the page if aiNotes is empty and shots are available
    LaunchedEffect(note?.id, shots.size) {
        if (note != null && note!!.aiNotes.isBlank() && shots.isNotEmpty() && !busy) {
            val key = prefs.getString("key", "") ?: ""
            if (key.isNotBlank()) {
                busy = true
                val generated = withContext(Dispatchers.IO) {
                    try {
                        val slideList = shots.map { s ->
                            Slide(s.ocrText, runCatching { File(s.path).readBytes() }.getOrNull())
                        }
                        Gemini.makeNotes(key, slideList)
                    } catch (e: Exception) { "Error: ${e.message}" }
                }
                text = generated
                busy = false
                dao.updateNote(note!!.copy(aiNotes = generated))
            }
        }
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = onBack) { Text("Back") }
                    Button(enabled = !busy && shots.isNotEmpty(), onClick = {
                        val key = prefs.getString("key", "") ?: ""
                        if (key.isBlank()) { text = "Paste your Gemini API key on the home screen first."; return@Button }
                        busy = true
                        scope.launch {
                            text = withContext(Dispatchers.IO) {
                                try {
                                    val slides = shots.map { s ->
                                        Slide(s.ocrText, runCatching { File(s.path).readBytes() }.getOrNull())
                                    }
                                    Gemini.makeNotes(key, slides)
                                } catch (e: Exception) { "Error: ${e.message}" }
                            }
                            busy = false
                        }
                    }) { Text(if (busy) "Writing..." else "Make AI notes") }
                    Spacer(Modifier.weight(1f))
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
                            val htmlContent = remember(text) { markdownToHtml(note?.title ?: "Study Notes", text) }
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
                            Button(onClick = {
                                val html = markdownToHtml(note?.title ?: "Study Notes", text)
                                val printManager = ctx.getSystemService(Context.PRINT_SERVICE) as PrintManager
                                val printAdapter = object : PrintDocumentAdapter() {
                                    private var webView: WebView? = null
                                    override fun onLayout(
                                        oldAttributes: PrintAttributes?,
                                        newAttributes: PrintAttributes?,
                                        cancellationSignal: android.os.CancellationSignal?,
                                        callback: LayoutResultCallback?,
                                        extras: Bundle?
                                    ) {
                                        webView = WebView(ctx).apply {
                                            webViewClient = object : WebViewClient() {
                                                override fun onPageFinished(view: WebView?, url: String?) {
                                                    val builder = android.print.PrintDocumentInfo.Builder("${note?.title ?: "Notes"}.pdf")
                                                        .setContentType(android.print.PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                                                        .setPageCount(android.print.PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                                                    callback?.onLayoutFinished(builder.build(), true)
                                                }
                                            }
                                            loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                                        }
                                    }
                                    override fun onWrite(
                                        pages: Array<out android.print.PageRange>?,
                                        destination: android.os.ParcelFileDescriptor?,
                                        cancellationSignal: android.os.CancellationSignal?,
                                        callback: WriteResultCallback?
                                    ) {
                                        webView?.let {
                                            val adapter = it.createPrintDocumentAdapter("StudyNotes")
                                            adapter.onWrite(pages, destination, cancellationSignal, callback)
                                        }
                                    }
                                }
                                printManager.print("${note?.title ?: "Study Notes"} PDF", printAdapter, PrintAttributes.Builder().build())
                            }, modifier = Modifier.fillMaxWidth()) {
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
                            shots.forEach { s ->
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        AsyncImage(model = File(s.path), contentDescription = null, modifier = Modifier.fillMaxWidth())
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
            }
        }
    }
}

