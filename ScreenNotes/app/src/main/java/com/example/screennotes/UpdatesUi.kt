package com.example.screennotes

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * The Updates tab: ask the AI to change this app, watch it work live, install the test
 * version, and approve it. Everything lives on GitHub (issues, pull requests, releases).
 */
@Composable
fun UpdatesTab(openRequest: Int?, onOpenRequest: (Int?) -> Unit) {
    var composing by rememberSaveable { mutableStateOf(false) }
    when {
        composing -> NewRequestScreen(onClose = { composing = false }, onSent = { composing = false; onOpenRequest(it) })
        openRequest != null -> RequestScreen(openRequest, onBack = { onOpenRequest(null) })
        else -> UpdatesHome(onNew = { composing = true }, onOpen = { onOpenRequest(it) })
    }
}

// ============================================================================ home

@Composable
private fun UpdatesHome(onNew: () -> Unit, onOpen: (Int) -> Unit) {
    val ctx = LocalContext.current
    val settings = remember { UpdateSettings(ctx) }
    var connected by remember { mutableStateOf(settings.ready) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var requests by remember { mutableStateOf(UpdateRepo.listCache) }
    var latest by remember { mutableStateOf(UpdateRepo.latestCache) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    if (connected) {
        LaunchedEffect(refreshKey) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    loading = true
                    try {
                        requests = UpdateRepo.list(ctx)
                        latest = try { UpdateRepo.latest(ctx) } catch (e: GitHubException) { latest }
                        error = null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = e.message ?: e.toString()
                    }
                    loading = false
                    delay(if (requests.any { it.status.active }) 8_000 else 45_000)
                }
            }
        }
    }

    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("App updates", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                if (connected) {
                    if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    IconButton(onClick = { refreshKey++ }) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") }
                }
                IconButton(onClick = { showSettings = !showSettings }) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
            }
        }
        item {
            Text(
                "Tell the AI what to change in this app. It makes the change on GitHub, builds a test version " +
                    "you can install right here, and only makes it permanent when you approve.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!connected || showSettings) {
            item {
                ConnectCard(
                    settings = settings,
                    connected = connected,
                    onConnected = { connected = true; showSettings = false; refreshKey++ },
                    onDisconnect = { settings.disconnect(); connected = false },
                )
            }
        }
        if (connected) {
            item { NotificationsOffCard() }
            item { VersionCard(latest) }
            item {
                Button(onClick = onNew, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("New update request")
                }
            }
            error?.let { item { ErrorText(it) } }
            if (requests.isEmpty() && !loading && error == null) {
                item {
                    Text(
                        "No requests yet. Tap \"New update request\" to send your first one.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (requests.isNotEmpty()) item { Text("Your requests", style = MaterialTheme.typography.titleMedium) }
            items(requests, key = { it.number }) { r -> RequestRow(r) { onOpen(r.number) } }
            item { SetupHelpCard(settings) }
        }
    }
}

@Composable
private fun ConnectCard(settings: UpdateSettings, connected: Boolean, onConnected: () -> Unit, onDisconnect: () -> Unit) {
    val ctx = LocalContext.current
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf("") }
    var repo by remember { mutableStateOf(settings.repo) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (connected) "GitHub connection" else "Connect GitHub (one time)", style = MaterialTheme.typography.titleMedium)
            if (connected) {
                Text(
                    "✓ Connected to ${settings.repo}" + if (settings.login.isNotBlank()) " as ${settings.login}" else "",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                "The app sends your requests to GitHub, where the AI works on them. It needs a token for that:",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "1. Tap \"Create token on GitHub\" and sign in.\n" +
                    "2. Under Repository access choose \"Only select repositories\" and pick ${repo.substringAfter('/')}.\n" +
                    "3. The permissions are already filled in: scroll down and tap \"Generate token\".\n" +
                    "4. Copy the token and paste it below.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = { uri.openUri(UpdateRepo.tokenUrl(repo)) }) { Text("Create token on GitHub") }
            OutlinedTextField(
                value = token,
                onValueChange = { token = it.trim() },
                label = { Text(if (connected) "New token (leave empty to keep the current one)" else "Token (starts with github_pat_)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = repo,
                onValueChange = { repo = it },
                label = { Text("Repository (owner/name)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    enabled = !busy && repo.isNotBlank() && (token.isNotBlank() || connected),
                    onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            try {
                                UpdateRepo.connect(ctx, token.ifBlank { settings.token }, repo)
                                token = ""
                                onConnected()
                            } catch (e: Exception) {
                                error = e.message ?: e.toString()
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (connected) "Save" else "Connect")
                }
                if (connected) TextButton(onClick = onDisconnect) { Text("Disconnect") }
            }
            error?.let { ErrorText(it) }
        }
    }
}

@Composable
private fun VersionCard(latest: ReleaseInfo?) {
    val sha = BuildConfig.GIT_SHA
    val label = BuildConfig.BUILD_LABEL
    val isTestBuild = label.startsWith("update #")
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "This app: version ${BuildConfig.VERSION_NAME} · " + when {
                    isTestBuild -> "test version for $label"
                    sha.isBlank() -> "built on a computer"
                    else -> "build ${sha.take(7)}"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            when {
                latest == null -> Text(
                    "No published version yet. Once the setup is merged on GitHub, every approved change is built " +
                        "automatically and shows up here.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                latest.sha.isNotBlank() && latest.sha == sha ->
                    Text("✓ You have the latest version.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                else -> {
                    Text(
                        if (sha.isBlank() || isTestBuild) "Latest version: ${latest.name}" else "🆕 New version available: ${latest.name}",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    latest.note?.let { InlineMd(it, style = MaterialTheme.typography.bodySmall) }
                    InstallButton(latest.apiUrl, latest.downloadUrl, "Install latest version")
                }
            }
        }
    }
}

@Composable
private fun RequestRow(r: UpdateRequest, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatusIcon(r.status)
            Column(Modifier.weight(1f)) {
                Text(r.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "#${r.number} · ${r.status.label} · ${ago(r.updatedAt)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatusIcon(s: ReqStatus, size: Dp = 24.dp) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        if (s.active) {
            CircularProgressIndicator(Modifier.size(size - 4.dp), strokeWidth = 2.5.dp)
        } else {
            val icon = when (s) {
                ReqStatus.READY -> "✅"
                ReqStatus.REPLIED -> "💬"
                ReqStatus.QUESTION -> "❓"
                ReqStatus.FAILED -> "❌"
                ReqStatus.DONE -> "🎉"
                else -> "⚪"
            }
            Text(icon, fontSize = (size.value * 0.65f).sp)
        }
    }
}

@Composable
private fun NotificationsOffCard() {
    val ctx = LocalContext.current
    var enabled by remember { mutableStateOf(true) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            enabled = NotificationManagerCompat.from(ctx).areNotificationsEnabled()
        }
    }
    if (enabled) return
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "🔕 Notifications are off. Turn them on to hear when the AI is done.",
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { UpdateNotifier.openSettings(ctx) }) { Text("Turn on") }
        }
    }
}

@Composable
private fun SetupHelpCard(settings: UpdateSettings) {
    val ctx = LocalContext.current
    val uri = LocalUriHandler.current
    var open by rememberSaveable { mutableStateOf(false) }
    val repo = settings.repo
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
                Text("Finish setup on GitHub", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Icon(if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null)
            }
            if (open) {
                Text("1. Give the AI your Gemini key (once)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Copy your key, open GitHub, type GEMINI_API_KEY as the Name, paste the key as the Secret, and tap \"Add secret\".",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val ok = UpdateRepo.copyGeminiKey(ctx)
                        Toast.makeText(
                            ctx, if (ok) "Gemini key copied" else "Paste your Gemini key on the Notes tab first", Toast.LENGTH_SHORT
                        ).show()
                    }) { Text("Copy my Gemini key") }
                    OutlinedButton(onClick = { uri.openUri("https://github.com/$repo/settings/secrets/actions/new") }) { Text("Open GitHub") }
                }
                Text("2. Optional: let the AI open pull requests", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Under Workflow permissions tick \"Allow GitHub Actions to create and approve pull requests\" and Save. " +
                        "If you skip this, the app creates the pull request itself when you tap Approve.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = { uri.openUri("https://github.com/$repo/settings/actions") }) { Text("Open Actions settings") }
                Text(
                    "Free Gemini keys only allow a few AI runs per day. If you hit the limit, turn on billing for the key " +
                        "in Google AI Studio (a typical update costs well under one US dollar).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ============================================================================ new request

@Composable
private fun NewRequestScreen(onClose: () -> Unit, onSent: (Int) -> Unit) {
    BackHandler(onBack = onClose)
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { UpdateSettings(ctx) }
    var text by rememberSaveable { mutableStateOf("") }
    var title by rememberSaveable { mutableStateOf("") }
    val files = remember { mutableStateListOf<Attachment>() }
    var busy by remember { mutableStateOf(false) }
    var step by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(8)) { uris ->
        uris.forEach { if (files.size < 10) files += Attachments.describe(ctx, it) }
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { if (files.size < 10) files += Attachments.describe(ctx, it) }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text("New update request", style = MaterialTheme.typography.headlineSmall)
        }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("What should change in the app?") },
            placeholder = { Text("Example: On the history list, show the date under each note and make Delete red.") },
            minLines = 5,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("Short title (optional)") },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
        )
        AttachmentStrip(files, enabled = !busy)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy, onClick = {
                pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Text("Add screenshots") }
            OutlinedButton(enabled = !busy, onClick = { pickFiles.launch(arrayOf("*/*")) }) { Text("Add file") }
        }
        if (!settings.repoPrivate) {
            Text(
                "Your GitHub repository is public, so anyone can see this request and its screenshots. " +
                    "Don't include passwords or private information.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
            )
        }
        Button(
            enabled = !busy && text.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                busy = true
                error = null
                scope.launch {
                    try {
                        val n = UpdateRepo.submit(ctx, title, text, files.toList()) { step = it }
                        onSent(n)
                    } catch (e: Exception) {
                        error = e.message ?: e.toString()
                    } finally {
                        busy = false
                    }
                }
            },
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(10.dp))
                Text(step.ifBlank { "Sending…" })
            } else {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Send to AI")
            }
        }
        error?.let { ErrorText(it) }
        Text(
            "Tips: say what you want and where in the app. Screenshots help a lot (you can draw on them " +
                "before attaching). You can reply later to ask for changes.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AttachmentStrip(files: MutableList<Attachment>, enabled: Boolean) {
    if (files.isEmpty()) return
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        files.toList().forEach { a ->
            Box(Modifier.size(84.dp)) {
                if (a.isImage) {
                    AsyncImage(
                        model = a.uri, contentDescription = a.name, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(84.dp).clip(RoundedCornerShape(8.dp)),
                    )
                } else {
                    Box(
                        Modifier.size(84.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("📄\n${a.name}", style = MaterialTheme.typography.labelSmall, maxLines = 4,
                            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    }
                }
                if (enabled) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(3.dp).size(22.dp).clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.6f)).clickable { files.remove(a) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Remove", tint = Color.White, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
    }
}

// ============================================================================ request detail

@Composable
private fun RequestScreen(number: Int, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    val cached = remember(number) { UpdateRepo.cached(number) }
    var req by remember(number) { mutableStateOf(cached?.first) }
    var msgs by remember(number) { mutableStateOf(cached?.second ?: emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val listState = rememberLazyListState()

    LaunchedEffect(number, tick) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                try {
                    val (r, m) = UpdateRepo.detail(ctx, number)
                    req = r
                    msgs = m
                    error = null
                    UpdateNotifier.markSeen(ctx, r, m)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = e.message ?: e.toString()
                }
                val active = req?.let { UpdateParse.status(it, msgs).active } ?: true
                delay(if (active) 5_000 else 30_000)
            }
        }
    }
    LaunchedEffect(msgs.size) {
        if (msgs.isNotEmpty()) runCatching { listState.animateScrollToItem(msgs.size) }
    }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text("Request #$number", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { tick++ }) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") }
        }
        val r = req
        if (r == null) {
            val e = error
            if (e != null) ErrorText(e) else Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            RequestContent(r, msgs, error, listState, onChanged = { tick++ })
        }
    }
}

@Composable
private fun RequestContent(
    r: UpdateRequest,
    msgs: List<Message>,
    error: String?,
    listState: LazyListState,
    onChanged: () -> Unit,
) {
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxSize()) {
        val status = UpdateParse.status(r, msgs)
        val lastAi = msgs.lastOrNull { it.fromAi }
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(r.title, style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusIcon(status, 18.dp)
                        Text(status.label, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { uri.openUri(r.url) }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                            Text("Open on GitHub", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            items(msgs, key = { it.id }) { m ->
                if (m.fromAi) AiCard(m, isLatest = m.id == lastAi?.id, req = r, onChanged = onChanged)
                else UserBubble(m)
            }
            if (status == ReqStatus.WAITING) {
                item { WaitingCard(since = msgs.lastOrNull { !it.fromAi }?.createdAt ?: r.createdAt) }
            }
            error?.let { item { ErrorText(it) } }
            if (!r.open) {
                item {
                    Text(
                        if (r.status == ReqStatus.DONE) "🎉 This change is now part of the app." else "This request is closed.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (status != ReqStatus.WORKING && status != ReqStatus.WAITING) {
                item { CloseRequestButton(r, lastAi?.bot, onDone = onChanged) }
            }
        }
        if (r.open) ReplyBar(r.number, aiBusy = status.active, onSent = onChanged)
    }
}

@Composable
private fun UserBubble(m: Message) {
    val ctx = LocalContext.current
    val uri = LocalUriHandler.current
    val settings = remember { UpdateSettings(ctx) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
            modifier = Modifier.padding(start = 32.dp).widthIn(max = 560.dp),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("You · ${ago(m.createdAt)}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f))
                if (m.text.isNotBlank()) MarkdownText(m.text)
                if (m.images.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        m.images.forEach { link ->
                            RemoteImage(settings, link, Modifier.height(120.dp).widthIn(max = 220.dp)
                                .clip(RoundedCornerShape(8.dp)).clickable { uri.openUri(link) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoteImage(settings: UpdateSettings, link: String, modifier: Modifier) {
    val ctx = LocalContext.current
    val request = remember(link) {
        val gh = settings.client()
        val (url, sameRepo) = gh?.imageUrl(link) ?: (link to false)
        ImageRequest.Builder(ctx).data(url).crossfade(true).apply {
            // Private repositories need the token to show screenshots.
            if (sameRepo && settings.repoPrivate) addHeader("Authorization", "token ${settings.token}")
        }.build()
    }
    AsyncImage(model = request, contentDescription = "Screenshot", contentScale = ContentScale.Fit, modifier = modifier)
}

@Composable
private fun AiCard(m: Message, isLatest: Boolean, req: UpdateRequest, onChanged: () -> Unit) {
    val b = m.bot ?: return
    val uri = LocalUriHandler.current
    val stuck = b.state == "working" && System.currentTimeMillis() - m.updatedAt > 50 * 60_000L
    val working = b.state == "working" && !stuck
    val container = when {
        b.state == "failed" || stuck -> MaterialTheme.colorScheme.errorContainer
        b.state == "question" -> MaterialTheme.colorScheme.tertiaryContainer
        b.state == "done" -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Card(Modifier.fillMaxWidth().padding(end = 16.dp), colors = CardDefaults.cardColors(containerColor = container)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (working) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(
                    when {
                        stuck -> "⚠️"
                        b.state == "done" -> if (b.apk != null) "✅" else "💬"
                        b.state == "question" -> "❓"
                        else -> "❌"
                    }
                )
                Text(
                    when {
                        stuck -> "This run seems to be stuck"
                        working -> "The AI is working on it…"
                        b.state == "done" -> if (b.apk != null) "Ready to test" else "The AI replied"
                        b.state == "question" -> "The AI needs a bit more info"
                        else -> "That didn't work this time"
                    },
                    style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f),
                )
                Text(ago(m.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (working) {
                b.steps.forEach { StepRow(it) }
                if (b.activity.isNotEmpty()) {
                    HorizontalDivider()
                    Text("Live activity", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    b.activity.takeLast(6).forEach { InlineMd(it, style = MaterialTheme.typography.bodySmall) }
                }
            } else {
                b.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
                if (b.reply.isNotBlank()) MarkdownText(b.reply)
                if (b.steps.isNotEmpty() || b.activity.isNotEmpty()) {
                    var showLog by remember { mutableStateOf(false) }
                    TextButton(onClick = { showLog = !showLog }, contentPadding = PaddingValues(0.dp)) {
                        Text(if (showLog) "Hide what the AI did" else "Show what the AI did", style = MaterialTheme.typography.labelMedium)
                    }
                    if (showLog) {
                        b.steps.forEach { StepRow(it) }
                        b.activity.forEach { InlineMd(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }

            if (isLatest && req.open) {
                if (b.state == "done" && b.apk != null) {
                    InstallButton(b.apkApi, b.apk, "Install test version")
                    ApproveButton(req, b, onChanged)
                    Text(
                        "Not quite right? Write what to change below and the AI will update it.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (b.state == "failed" || stuck) {
                    RetryButton(req.number, onChanged)
                }
            }
            b.runUrl?.let { url ->
                TextButton(onClick = { uri.openUri(url) }, contentPadding = PaddingValues(0.dp)) {
                    Text("Technical log" + (b.model?.let { " · $it" } ?: ""), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun StepRow(s: BotStep) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            when (s.status) {
                "active" -> CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
                "done" -> Text("✅", fontSize = 13.sp)
                "failed" -> Text("❌", fontSize = 13.sp)
                "skipped" -> Text("➖", fontSize = 13.sp)
                else -> Text("⬜", fontSize = 13.sp)
            }
        }
        Text(
            s.text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (s.status == "active") FontWeight.SemiBold else null,
            color = if (s.status == "todo" || s.status == "skipped") MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun WaitingCard(since: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            now = System.currentTimeMillis()
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Column {
                Text("Sent! Waiting for the AI to start…", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (now - since > 4 * 60_000L) {
                        "This is taking longer than usual. If nothing happens, check \"Finish setup on GitHub\" on the " +
                            "Updates page (the AI needs the GEMINI_API_KEY secret) or tap Refresh."
                    } else {
                        "It usually starts within a minute."
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ReplyBar(number: Int, aiBusy: Boolean, onSent: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by rememberSaveable(number) { mutableStateOf("") }
    val files = remember(number) { mutableStateListOf<Attachment>() }
    var busy by remember { mutableStateOf(false) }
    var step by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(8)) { uris ->
        uris.forEach { if (files.size < 10) files += Attachments.describe(ctx, it) }
    }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AttachmentStrip(files, enabled = !busy)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(enabled = !busy, onClick = {
                pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Icon(Icons.Default.Add, contentDescription = "Add screenshot") }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(if (aiBusy) "Add more info (the AI reads it next)" else "Reply or ask for changes…") },
                maxLines = 5,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.weight(1f),
            )
            IconButton(
                enabled = !busy && (text.isNotBlank() || files.isNotEmpty()),
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            UpdateRepo.reply(ctx, number, text.ifBlank { "(see the screenshot)" }, files.toList()) { step = it }
                            text = ""
                            files.clear()
                            onSent()
                        } catch (e: Exception) {
                            error = e.message ?: e.toString()
                        } finally {
                            busy = false
                        }
                    }
                },
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
        if (busy && step.isNotBlank()) Text(step, style = MaterialTheme.typography.bodySmall)
        error?.let { ErrorText(it) }
    }
}

// ============================================================================ actions

@Composable
fun InstallButton(apiUrl: String?, browserUrl: String?, label: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Float?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var askPermission by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(enabled = progress == null && (apiUrl != null || browserUrl != null), onClick = {
            if (!ApkInstaller.canInstall(ctx)) {
                askPermission = true
                return@Button
            }
            progress = 0f
            error = null
            scope.launch {
                try {
                    ApkInstaller.downloadAndInstall(ctx, apiUrl, browserUrl) { p -> progress = p }
                } catch (e: Exception) {
                    error = "Download failed: ${e.message ?: e}"
                } finally {
                    progress = null
                }
            }
        }) {
            val p = progress
            if (p != null) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(8.dp))
                Text("Downloading… ${(p * 100).toInt()}%")
            } else {
                Text(label)
            }
        }
        progress?.let { p -> LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth()) }
        error?.let { ErrorText(it) }
    }
    if (askPermission) {
        AlertDialog(
            onDismissRequest = { askPermission = false },
            title = { Text("Allow installing updates") },
            text = {
                Text(
                    "Android needs your OK once: on the next screen switch on \"Allow from this source\", then come " +
                        "back and tap Install again.\n\nAndroid still asks before every install, and your notes stay " +
                        "safe because the update installs over the current app."
                )
            },
            confirmButton = {
                TextButton(onClick = { askPermission = false; ApkInstaller.openPermissionSettings(ctx) }) { Text("Open settings") }
            },
            dismissButton = { TextButton(onClick = { askPermission = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ApproveButton(req: UpdateRequest, bot: BotData, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    OutlinedButton(enabled = !busy && result == null, onClick = { confirm = true }) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Merging…")
        } else {
            Icon(Icons.Default.Check, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Approve & merge")
        }
    }
    result?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
    error?.let { ErrorText(it) }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Make this change permanent?") },
            text = {
                Text(
                    "Do this after you've installed and tried the test version. The change goes into the app's main " +
                        "code and GitHub builds the final version, which then shows up in the Updates tab."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            result = UpdateRepo.approve(ctx, req, bot)
                            onDone()
                        } catch (e: Exception) {
                            error = e.message ?: e.toString()
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("Approve & merge") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Not yet") } },
        )
    }
}

@Composable
private fun RetryButton(number: Int, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Button(enabled = !busy, onClick = {
        busy = true
        error = null
        scope.launch {
            try {
                UpdateRepo.retry(ctx, number)
                onDone()
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            } finally {
                busy = false
            }
        }
    }) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            Spacer(Modifier.width(8.dp))
        }
        Text("Try again")
    }
    error?.let { ErrorText(it) }
}

@Composable
private fun CloseRequestButton(req: UpdateRequest, bot: BotData?, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    TextButton(onClick = { confirm = true }) { Text("Close this request without changing the app") }
    error?.let { ErrorText(it) }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Close this request?") },
            text = { Text("The app stays as it is. You can always send a new request later.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    scope.launch {
                        try {
                            UpdateRepo.close(ctx, req, bot)
                            onDone()
                        } catch (e: Exception) {
                            error = e.message ?: e.toString()
                        }
                    }
                }) { Text("Close request") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

// ============================================================================ helpers

@Composable
private fun ErrorText(msg: String) {
    Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

private fun ago(t: Long): String {
    if (t <= 0) return ""
    val s = (System.currentTimeMillis() - t) / 1000
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60} min ago"
        s < 86_400 -> "${s / 3600} h ago"
        s < 7 * 86_400 -> "${s / 86_400} d ago"
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(t))
    }
}
