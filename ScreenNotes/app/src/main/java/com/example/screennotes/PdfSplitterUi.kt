package com.example.screennotes

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.graphics.pdf.PdfRenderer
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PdfSplitterTab() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var fileName by remember { mutableStateOf("") }
    var totalPages by remember { mutableIntStateOf(0) }
    var splitRangesInput by remember { mutableStateOf("") }
    var splitting by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }
    var splitResults by remember { mutableStateOf<List<PdfSaveResult>>(emptyList()) }
    var aiSplitPrompt by remember { mutableStateOf("") }
    var aiBusy by remember { mutableStateOf(false) }

    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            selectedUri = uri
            splitting = true
            statusMessage = "Loading PDF info..."
            scope.launch(Dispatchers.IO) {
                try {
                    val pfd = ctx.contentResolver.openFileDescriptor(uri, "r")
                    if (pfd != null) {
                        val renderer = PdfRenderer(pfd)
                        val count = renderer.pageCount
                        renderer.close()
                        pfd.close()
                        // Get display name
                        var name = "document.pdf"
                        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                            val nameIdx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (c.moveToFirst() && nameIdx >= 0) {
                                c.getString(nameIdx)?.let { name = it }
                            }
                        }
                        withContext(Dispatchers.Main) {
                            totalPages = count
                            fileName = name
                            splitting = false
                            statusMessage = "Loaded $name ($count pages). Ready to split!"
                            // Default range suggestion
                            splitRangesInput = if (count > 20) "1-20, 21-$count" else "1-$count"
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            splitting = false
                            statusMessage = "Could not open file descriptor."
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        splitting = false
                        statusMessage = "Error reading PDF: ${e.message}"
                    }
                }
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        item {
            Text("PDF Splitter & AI Split", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Upload large PDFs (100 MB, 117 MB or any size). Split them manually or ask AI to suggest subdocument ranges.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("1. Select PDF Document", style = MaterialTheme.typography.titleMedium)
                    if (fileName.isNotBlank()) {
                        Text("Selected: $fileName ($totalPages pages)", style = MaterialTheme.typography.bodyLarge)
                    }
                    Button(
                        onClick = { pdfLauncher.launch(arrayOf("application/pdf")) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (fileName.isBlank()) "Choose PDF (Any Size)" else "Change PDF")
                    }
                }
            }
        }

        if (fileName.isNotBlank()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("2. AI Automatic Split (Optional)", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Describe how you want the AI to split this $totalPages-page document (e.g. \"Split by chapters\", \"Topics 1 to 5\", \"Every 20 pages\").",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = aiSplitPrompt,
                            onValueChange = { aiSplitPrompt = it },
                            label = { Text("AI splitting instructions") },
                            placeholder = { Text("e.g. Split into Mechanics, Optics, Modern Physics...") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            enabled = !aiBusy,
                            onClick = {
                                aiBusy = true
                                statusMessage = "AI is analyzing document structure..."
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        val prefs = ctx.getSharedPreferences("p", Context.MODE_PRIVATE)
                                        val keys = AiNotesEngine.readKeys(prefs)
                                        val prompt = "You are a JEE study planner. The user has a $totalPages-page PDF titled \"$fileName\". " +
                                            "User instructions: \"${aiSplitPrompt.ifBlank { "Split logically into study subdocuments of around 20-30 pages each" }}\". " +
                                            "Return ONLY a comma-separated list of page ranges in the exact format: start-end, start-end (for example: 1-25, 26-50, 51-$totalPages). No other text."
                                        val slide = Slide("PDF filename: $fileName, total pages: $totalPages", null)
                                        val res = AiNotesEngine.makeNotes(keys, listOf(slide), NotesMode.FAST, prompt)
                                        val cleaned = res.text.replace(Regex("[^0-9,-]"), " ").trim()
                                        withContext(Dispatchers.Main) {
                                            aiBusy = false
                                            if (cleaned.isNotBlank() && cleaned.contains("-")) {
                                                splitRangesInput = cleaned
                                                statusMessage = "AI suggested ranges successfully!"
                                            } else {
                                                statusMessage = "AI response: ${res.text.take(100)}"
                                            }
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            aiBusy = false
                                            statusMessage = "AI split failed: ${e.message}"
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (aiBusy) "AI is planning splits..." else "Ask AI to Plan Splits")
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("3. Manual or Range Splitting", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Enter page ranges separated by commas (e.g. 1-20, 21-41, 42-$totalPages).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = splitRangesInput,
                            onValueChange = { splitRangesInput = it },
                            label = { Text("Splitting Ranges") },
                            placeholder = { Text("1-20, 21-40, 41-$totalPages") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            enabled = !splitting && selectedUri != null && splitRangesInput.isNotBlank(),
                            onClick = {
                                splitting = true
                                statusMessage = "Executing PDF split..."
                                val uri = selectedUri!!
                                val rangesText = splitRangesInput
                                val name = fileName
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        val pfd = ctx.contentResolver.openFileDescriptor(uri, "r")
                                            ?: error("Could not open PDF file descriptor.")
                                        val ranges = PdfSplitterHelper.parseRanges(rangesText, totalPages)
                                        val results = PdfSplitterHelper.executeSplit(ctx, pfd, ranges, name)
                                        withContext(Dispatchers.Main) {
                                            splitting = false
                                            splitResults = results
                                            statusMessage = "Successfully created ${results.size} subdocument PDFs in Downloads/ScreenNotes!"
                                            Toast.makeText(ctx, "Split into ${results.size} PDFs in Downloads", Toast.LENGTH_LONG).show()
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            splitting = false
                                            statusMessage = "Split error: ${e.message}"
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (splitting) "Splitting PDF..." else "Execute PDF Split")
                        }
                    }
                }
            }
        }

        if (statusMessage.isNotBlank()) {
            item {
                Text(
                    statusMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        if (splitResults.isNotEmpty()) {
            item {
                Text("Split Subdocuments (${splitResults.size})", style = MaterialTheme.typography.titleMedium)
            }
            items(splitResults) { res ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(res.path.substringAfterLast('/'), style = MaterialTheme.typography.titleSmall)
                            Text(res.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(onClick = {
                            runCatching {
                                val intent = Intent(Intent.ACTION_VIEW)
                                    .setDataAndType(res.uri, "application/pdf")
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                ctx.startActivity(intent)
                            }.onFailure {
                                Toast.makeText(ctx, "Saved to ${res.path}", Toast.LENGTH_LONG).show()
                            }
                        }) {
                            Text("Open")
                        }
                    }
                }
            }
        }
    }
}
