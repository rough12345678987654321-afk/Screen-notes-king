package com.example.screennotes

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

/** A captured screen: OCR text plus its JPEG screenshot (if available). */
class Slide(
    val ocr: String,
    val jpeg: ByteArray?,
    val screenshotNumber: Int = 0,
    val jpegFile: File? = null
)

enum class NotesMode { TOP_TIER, FAST }

enum class AiFamily(
    val title: String,
    val preferenceKey: String,
    val hint: String,
    val keyless: Boolean = false
) {
    GEMINI("Google Gemini", "ai_key_gemini", "Google AI Studio key — Gemini can read screenshots."),
    OPENROUTER("OpenRouter", "ai_key_openrouter", "One key unlocks five free models."),
    GROQ("Groq", "ai_key_groq", "One key unlocks three models."),
    CEREBRAS("Cerebras", "ai_key_cerebras", "One key unlocks two models."),
    NVIDIA("NVIDIA", "ai_key_nvidia", "NVIDIA API catalog key."),
    HUGGINGFACE("HuggingFace router", "ai_key_huggingface", "HuggingFace access token."),
    MISTRAL("Mistral", "ai_key_mistral", "Mistral API key."),
    GITHUB_MODELS("GitHub Models", "ai_key_github_models", "GitHub personal access token with Models access."),
    POLLINATIONS("Pollinations", "", "Keyless fallback — no API key is needed.", keyless = true)
}

private enum class ApiStyle { GEMINI, OPENAI_COMPATIBLE }

private data class AiSource(
    val family: AiFamily,
    val name: String,
    val model: String,
    val endpoint: String,
    val style: ApiStyle
) {
    val label: String get() = "${family.title} · $name"
}

/**
 * 24-source notes engine. Credentials stay in the app's existing private SharedPreferences;
 * OpenAI-compatible providers receive OCR only, while Gemini receives OCR plus the screenshots.
 */
object AiNotesEngine {
    const val MODE_PREFERENCE = "notes_ai_mode"
    const val PREF_AI_INSTRUCTIONS = "ai_instructions"

    /** The literal marker the completeness judge inserts where no capture shows the content. */
    const val GAP_MARKER = "[gap: not visible in captures]"

    /** How many gaps the completeness judge marked in a piece of notes text. */
    fun countGaps(text: String): Int {
        var count = 0
        var from = 0
        var at = text.indexOf(GAP_MARKER, from)
        while (at >= 0) {
            count++
            from = at + GAP_MARKER.length
            at = text.indexOf(GAP_MARKER, from)
        }
        return count
    }

    private const val LEGACY_GEMINI_KEY = "key"
    private const val MAX_IMAGES = 24
    private const val IMAGE_WIDTH = 1024
    private const val IMAGE_QUALITY = 80
    private const val MAX_OCR_CHARS = 48_000
    private const val MAX_DRAFT_CHARS = 24_000

    private val geminiEndpoint = "https://generativelanguage.googleapis.com/v1beta/models/"
    private val openRouter = "https://openrouter.ai/api/v1/chat/completions"
    private val groq = "https://api.groq.com/openai/v1/chat/completions"
    private val cerebras = "https://api.cerebras.ai/v1/chat/completions"
    private val nvidia = "https://integrate.api.nvidia.com/v1/chat/completions"
    private val huggingFace = "https://router.huggingface.co/v1/chat/completions"
    private val mistral = "https://api.mistral.ai/v1/chat/completions"
    private val githubModels = "https://models.github.ai/inference/chat/completions"
    private val pollinations = "https://text.pollinations.ai/openai/chat/completions"

    // Order is intentional: best vision/large-model choices first, then smaller and keyless fallbacks.
    private fun gemini(name: String, model: String) = AiSource(
        AiFamily.GEMINI, name, model, "$geminiEndpoint$model:generateContent", ApiStyle.GEMINI
    )
    private fun chat(family: AiFamily, name: String, model: String, endpoint: String) =
        AiSource(family, name, model, endpoint, ApiStyle.OPENAI_COMPATIBLE)

    private val sources = listOf(
        gemini("Flash", "gemini-3.8-flash"),
        gemini("Flash-Lite", "gemini-3.5-flash-lite"),
        chat(AiFamily.OPENROUTER, "Llama 3.3 70B", "meta-llama/llama-3.3-70b-instruct:free", openRouter),
        chat(AiFamily.OPENROUTER, "Qwen 3 235B", "qwen/qwen3-235b-a22b:free", openRouter),
        chat(AiFamily.OPENROUTER, "DeepSeek Chat", "deepseek/deepseek-chat:free", openRouter),
        chat(AiFamily.OPENROUTER, "Gemma 3 27B", "google/gemma-3-27b-it:free", openRouter),
        chat(AiFamily.OPENROUTER, "Phi 4", "microsoft/phi-4:free", openRouter),
        chat(AiFamily.GROQ, "Llama 3.3 70B Versatile", "llama-3.3-70b-versatile", groq),
        chat(AiFamily.GROQ, "QwQ 32B", "qwq-32b", groq),
        chat(AiFamily.GROQ, "Llama 3.1 8B Instant", "llama-3.1-8b-instant", groq),
        chat(AiFamily.CEREBRAS, "Llama 3.3 70B", "llama-3.3-70b", cerebras),
        chat(AiFamily.CEREBRAS, "Qwen 3 32B", "qwen-3-32b", cerebras),
        chat(AiFamily.NVIDIA, "Nemotron 70B", "nvidia/llama-3.1-nemotron-70b-instruct", nvidia),
        chat(AiFamily.NVIDIA, "Llama 3.3 70B", "meta/llama-3.3-70b-instruct", nvidia),
        chat(AiFamily.HUGGINGFACE, "Qwen 2.5 72B", "Qwen/Qwen2.5-72B-Instruct", huggingFace),
        chat(AiFamily.HUGGINGFACE, "Llama 3.3 70B", "meta-llama/Llama-3.3-70B-Instruct", huggingFace),
        chat(AiFamily.HUGGINGFACE, "Mistral Small 24B", "mistralai/Mistral-Small-24B-Instruct-2501", huggingFace),
        chat(AiFamily.MISTRAL, "Mistral Small", "mistral-small-latest", mistral),
        chat(AiFamily.MISTRAL, "Open Mistral Nemo", "open-mistral-nemo", mistral),
        chat(AiFamily.GITHUB_MODELS, "GPT-4o mini", "gpt-4o-mini", githubModels),
        chat(AiFamily.GITHUB_MODELS, "Llama 3.3 70B", "meta-llama/Llama-3.3-70B-Instruct", githubModels),
        chat(AiFamily.POLLINATIONS, "OpenAI", "openai", pollinations),
        chat(AiFamily.POLLINATIONS, "Mistral", "mistral", pollinations),
        chat(AiFamily.POLLINATIONS, "Llama", "llama", pollinations)
    )

    val totalSources: Int get() = sources.size
    fun sourceCount(family: AiFamily): Int = sources.count { it.family == family }
    fun families(): List<AiFamily> = AiFamily.values().toList()

    /** One key per provider family. Old installs keep using their existing Gemini key. */
    fun readKey(prefs: SharedPreferences, family: AiFamily): String {
        if (family.keyless) return ""
        val saved = prefs.getString(family.preferenceKey, null)
        if (saved != null) return saved
        if (family == AiFamily.GEMINI) {
            val legacy = prefs.getString(LEGACY_GEMINI_KEY, "").orEmpty()
            // Earlier versions accepted comma-separated Gemini keys. Keep the first as this family's key.
            val migrated = legacy.split(Regex("[,;\\n]")).firstOrNull()?.trim().orEmpty()
            if (migrated.isNotBlank()) prefs.edit().putString(family.preferenceKey, migrated).apply()
            return migrated
        }
        return ""
    }

    fun readKeys(prefs: SharedPreferences): Map<AiFamily, String> =
        families().associateWith { readKey(prefs, it) }

    fun saveKey(prefs: SharedPreferences, family: AiFamily, value: String) {
        if (family.keyless) return
        prefs.edit().putString(family.preferenceKey, value).apply()
        // Keep the legacy preference in sync so existing local installs remain compatible.
        if (family == AiFamily.GEMINI) prefs.edit().putString(LEGACY_GEMINI_KEY, value).apply()
    }

    fun readySourceCount(keys: Map<AiFamily, String>): Int = sources.count { ready(it, keys) }
    fun readyFamilyCount(keys: Map<AiFamily, String>): Int =
        families().count { it.keyless || !keys[it].isNullOrBlank() }

    fun defaultMode(keys: Map<AiFamily, String>): NotesMode =
        if (readyFamilyCount(keys) >= 2) NotesMode.TOP_TIER else NotesMode.FAST

    fun configuredMode(prefs: SharedPreferences, keys: Map<AiFamily, String>): NotesMode =
        when (prefs.getString(MODE_PREFERENCE, null)) {
            "FAST" -> NotesMode.FAST
            "TOP_TIER" -> NotesMode.TOP_TIER
            else -> defaultMode(keys)
        }

    /** Runs one short request against this family. The row's Test button uses this. */
    fun testFamily(family: AiFamily, key: String): String {
        if (!family.keyless && key.isBlank()) return "Add a key first."
        val candidates = sources.filter { it.family == family }
        var lastError = "No model responded."
        for (source in candidates) {
            val response = request(source, key.trim(), "You are checking an API connection.", "Reply with exactly: OK.", emptyList())
            if (response.status in 200..299) {
                val reply = parseReply(source, response.body).trim()
                if (reply.isNotEmpty()) return "Connected · ${source.name}"
            }
            if (response.status == 401 || response.status == 403) {
                return "Key rejected · ${describeFailure(response, key)}"
            }
            lastError = describeFailure(response, key)
        }
        return "Not ready · ${lastError.take(150)}"
    }

    /**
     * Makes notes in fast mode or in a three-call draft / draft / evidence-checked merge, and then
     * runs one final "thinking" pass that judges the result against the captures for completeness.
     */
    fun makeNotes(
        keys: Map<AiFamily, String>,
        slides: List<Slide>,
        mode: NotesMode,
        studentInstructions: String = ""
    ): NotesResult {
        val candidates = sources.filter { ready(it, keys) }
        if (candidates.isEmpty()) return failed("No AI sources are configured.")

        fun withInstructions(basePrompt: String): String =
            if (studentInstructions.isNotBlank()) {
                basePrompt + "\n\nSTUDENT'S OWN INSTRUCTIONS — follow these strictly:\n" + studentInstructions.trim()
            } else basePrompt

        // Avoid spending provider context and vision slots on repeated captures. Keep the original
        // screenshot numbers so any [Screenshot N] references still point at the right saved image.
        val distinctSlides = deduplicateSlides(slides)
        val drafted = compose(candidates, keys, distinctSlides, mode, ::withInstructions)
        if (!drafted.succeeded) return drafted
        return think(candidates, keys, distinctSlides, drafted)
    }

    /**
     * Drop visually repeated shots before any provider call. Signatures are only 32x18 pixels, so
     * even a long session is cheap to compare; OCR-only captures fall back to exact normalized text.
     */
    private fun deduplicateSlides(slides: List<Slide>): List<Slide> {
        val kept = ArrayList<Slide>(slides.size)
        val signatures = ArrayList<IntArray>()
        val ocrOnly = HashSet<String>()
        for ((index, raw) in slides.withIndex()) {
            val slide = if (raw.screenshotNumber > 0) raw
                else Slide(raw.ocr, raw.jpeg, index + 1, raw.jpegFile)
            val sig = slide.jpeg?.let { imageSignature(it) }
                ?: slide.jpegFile?.let { imageSignature(it) }
            if (sig != null) {
                if (signatures.any { nearDuplicate(it, sig) }) continue
                signatures += sig
            } else {
                val normalized = slide.ocr.lowercase().replace(Regex("\\s+"), " ").trim()
                if (normalized.isNotEmpty() && !ocrOnly.add(normalized)) continue
            }
            kept += slide
        }
        return kept
    }

    /** Keep an even temporal spread of the distinct, ordered images when a session has over 24. */
    private fun visionSlides(slides: List<Slide>): List<Slide> {
        val images = slides.filter { it.jpeg != null || it.jpegFile?.isFile == true }
        if (images.size <= MAX_IMAGES) return images
        val last = images.lastIndex.toLong()
        return (0 until MAX_IMAGES).map { i ->
            val index = (i.toLong() * last / (MAX_IMAGES - 1)).toInt()
            images[index]
        }
    }

    private fun imageSignature(bytes: ByteArray): IntArray? = imageSignature { options ->
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun imageSignature(file: File): IntArray? = imageSignature { options ->
        BitmapFactory.decodeFile(file.absolutePath, options)
    }

    private fun imageSignature(decode: (BitmapFactory.Options) -> Bitmap?): IntArray? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            decode(bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 64) sample *= 2
            val bitmap = decode(BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            val thumb = Bitmap.createScaledBitmap(bitmap, 32, 18, true)
            if (thumb !== bitmap) bitmap.recycle()
            val pixels = IntArray(32 * 18)
            thumb.getPixels(pixels, 0, 32, 0, 0, 32, 18)
            thumb.recycle()
            IntArray(pixels.size) { i ->
                val c = pixels[i]
                (((c shr 16) and 0xFF) * 3 + ((c shr 8) and 0xFF) * 6 + (c and 0xFF)) / 10
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun nearDuplicate(a: IntArray, b: IntArray): Boolean {
        if (a.size != b.size || a.isEmpty()) return false
        var total = 0L
        val limit = 3.0 * a.size
        for (i in a.indices) {
            total += abs(a[i] - b[i])
            if (total >= limit) return false
        }
        return total.toDouble() / a.size < 3.0
    }

    /** Fast mode: one call. Top tier: two independent drafts plus an evidence-checked merge. */
    private fun compose(
        candidates: List<AiSource>,
        keys: Map<AiFamily, String>,
        slides: List<Slide>,
        mode: NotesMode,
        withInstructions: (String) -> String
    ): NotesResult {
        if (mode == NotesMode.FAST) return fast(candidates, keys, slides, withInstructions = withInstructions)
        if (readyFamilyCount(keys) < 2) {
            val result = fast(candidates, keys, slides, withInstructions = withInstructions)
            return result.copy(notice = "Top tier needs two ready AI families; used Fast instead.")
        }

        val draftSystem = withInstructions(
            "$NOTES_PROMPT\n\nWrite a complete first draft of the study notes. Be faithful to the evidence."
        )
        val first = runLadder(candidates, keys, slides, draftSystem)
        val draftOne = first.output ?: return failed(
            "Could not reach an AI source for the first draft. ${first.lastError}"
        )

        // Excluding the first family guarantees that the two drafts come from different providers.
        val secondSystem = withInstructions(
            "$NOTES_PROMPT\n\nWrite an independent complete study-notes draft. Be faithful to the evidence."
        )
        val second = runLadder(
            candidates.filter { it.family != draftOne.source.family }, keys, slides,
            secondSystem
        )
        val draftTwo = second.output ?: return NotesResult(
            draftOne.text, listOf(draftOne.source.label),
            "A second provider was unavailable, so these are the first provider's notes. ${second.lastError}",
            NotesMode.FAST
        )

        val mergeEvidence = buildString {
            append("OCR EVIDENCE:\n")
            append(stitchedOcrEvidence(slides))
            append("\n\nDRAFT FROM ").append(draftOne.source.label).append(":\n")
            append(draftOne.text.take(MAX_DRAFT_CHARS))
            append("\n\nDRAFT FROM ").append(draftTwo.source.label).append(":\n")
            append(draftTwo.text.take(MAX_DRAFT_CHARS))
        }
        val mergeSystem = withInstructions(
            "$NOTES_PROMPT\n\n" +
                "Merge the two drafts into one accurate, coherent set of JEE study notes. Re-check every " +
                "claim against the OCR evidence; resolve disagreements using only that evidence (on disagreement " +
                "about edge/split content prefer the version consistent with the OCR evidence). Keep useful " +
                "details from both drafts, remove unsupported claims, and return only the finished notes."
        )
        // Prefer a third provider for the merge; if only two families are ready, use the best available model.
        val draftFamilies = setOf(draftOne.source.family, draftTwo.source.family)
        val mergeOrder = candidates.filter { it.family !in draftFamilies } +
            candidates.filter { it.family in draftFamilies }
        val merged = runLadder(mergeOrder, keys, slides, mergeSystem, mergeEvidenceOverride = mergeEvidence)
        val final = merged.output
        if (final != null) {
            return NotesResult(
                final.text,
                listOf(draftOne.source.label, draftTwo.source.label, final.source.label),
                "Top tier combined two independent drafts against the OCR evidence.",
                NotesMode.TOP_TIER
            )
        }
        return NotesResult(
            draftOne.text, listOf(draftOne.source.label),
            "The merge service was unavailable, so these are the first provider's notes. ${merged.lastError}",
            NotesMode.FAST
        )
    }

    /**
     * The final "thinking" pass: a completeness judge. It receives the stitched OCR evidence of all
     * captures plus the finished notes, and it has to
     *  - verify sequence continuity (numbered steps, sentences cut mid-line, diagrams split across captures),
     *  - reconstruct cut content from the two captures where that evidence exists, and
     *  - mark content that is in NO capture with the literal [GAP_MARKER] text.
     * It never invents formulas, constants or steps. If no provider can be reached for the judge,
     * the notes are returned exactly as they were.
     */
    private fun think(
        candidates: List<AiSource>,
        keys: Map<AiFamily, String>,
        slides: List<Slide>,
        notes: NotesResult
    ): NotesResult {
        // Without captures there is no evidence to judge against, so the notes stay as they are.
        if (notes.text.isBlank() || slides.isEmpty()) return notes
        val evidence = buildString {
            append("STITCHED OCR EVIDENCE FROM THE CAPTURES (in order):\n")
            append(stitchedOcrEvidence(slides))
            append("\n\nFINAL NOTES TO JUDGE (complete text):\n")
            append(notes.text)
        }
        // Send the stitched evidence and finished notes as text to every provider. Vision-capable
        // Gemini candidates also get the captures so split diagrams can be checked from both sides.
        // Keep student style instructions out of this final guard so they cannot relax its no-invention rule.
        val result = runLadder(
            candidates, keys, slides, GAP_PROMPT, mergeEvidenceOverride = evidence
        )
        val output = result.output ?: return notes.copy(
            notice = (notes.notice + " Completeness check unavailable: ${result.lastError}").trim()
        )
        // A judge that answered with much less than it was given did not do its job: keep the notes.
        if (output.text.length < notes.text.length / 2) return notes.copy(
            notice = (notes.notice + " Completeness check returned an unusable reply; the notes were kept as they were.").trim()
        )
        return notes.copy(
            text = output.text,
            sources = (notes.sources + output.source.label).distinct(),
            notice = notes.notice
        )
    }

    private fun fast(
        candidates: List<AiSource>,
        keys: Map<AiFamily, String>,
        slides: List<Slide>,
        notice: String = "",
        withInstructions: (String) -> String
    ): NotesResult {
        val result = runLadder(
            candidates, keys, slides,
            withInstructions("$NOTES_PROMPT\n\nWrite the finished study notes from the evidence below.")
        )
        val output = result.output ?: return failed(
            "All configured AI sources were unavailable. Check your connection and saved API keys, " +
                "or use Test next to a source. ${result.lastError}"
        )
        return NotesResult(output.text, listOf(output.source.label), notice, NotesMode.FAST)
    }

    private data class ApiResponse(val status: Int, val body: String)
    private data class AiOutput(val source: AiSource, val text: String)
    private data class LadderResult(val output: AiOutput?, val lastError: String)

    private fun runLadder(
        candidates: List<AiSource>,
        keys: Map<AiFamily, String>,
        slides: List<Slide>,
        systemPrompt: String,
        mergeEvidenceOverride: String? = null
    ): LadderResult {
        var lastError = "No eligible source."
        val badKeys = mutableSetOf<AiFamily>()
        for (source in candidates) {
            if (source.family in badKeys) continue
            val key = keys[source.family].orEmpty().trim()
            val userEvidence = mergeEvidenceOverride ?: (
                if (source.style == ApiStyle.GEMINI) geminiOcrEvidence(slides) else stitchedOcrEvidence(slides)
            )
            val response = request(source, key, systemPrompt, userEvidence, slides)
            if (response.status in 200..299) {
                val text = NotesClean.cleanNotes(parseReply(source, response.body)).trim()
                if (text.isNotBlank()) return LadderResult(AiOutput(source, text), lastError)
                lastError = "${source.label} returned an empty reply."
            } else {
                lastError = "${source.label}: ${describeFailure(response, key)}"
                if (response.status == 401 || response.status == 403) badKeys += source.family
            }
            // Move down the quality ladder on quota, server, missing-model, auth, network, and request errors.
        }
        return LadderResult(null, lastError)
    }

    private fun ready(source: AiSource, keys: Map<AiFamily, String>): Boolean =
        source.family.keyless || !keys[source.family].isNullOrBlank()

    private fun request(
        source: AiSource,
        key: String,
        systemPrompt: String,
        userEvidence: String,
        slides: List<Slide>
    ): ApiResponse {
        var connection: HttpURLConnection? = null
        return try {
            val body = if (source.style == ApiStyle.GEMINI) {
                geminiBody(systemPrompt, userEvidence, slides)
            } else {
                JSONObject()
                    .put("model", source.model)
                    .put("messages", JSONArray()
                        .put(JSONObject().put("role", "system").put("content", systemPrompt))
                        .put(JSONObject().put("role", "user").put("content", userEvidence)))
            }
            connection = (URL(source.endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 75_000
                doOutput = true
                useCaches = false
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                if (source.style == ApiStyle.GEMINI) setRequestProperty("x-goog-api-key", key)
                else if (key.isNotBlank()) setRequestProperty("Authorization", "Bearer $key")
            }
            connection!!.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection!!.responseCode
            val stream = if (code in 200..299) connection!!.inputStream else connection!!.errorStream
            ApiResponse(code, stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty())
        } catch (e: Exception) {
            ApiResponse(0, e.message ?: e.javaClass.simpleName)
        } finally {
            connection?.disconnect()
        }
    }

    private fun geminiBody(systemPrompt: String, userEvidence: String, slides: List<Slide>): JSONObject {
        val parts = JSONArray()
        parts.put(JSONObject().put("text", "$systemPrompt\n\n$userEvidence"))
        visionSlides(slides).forEachIndexed { index, slide ->
            val jpeg = slide.jpeg ?: slide.jpegFile?.let { file -> runCatching { file.readBytes() }.getOrNull() }
                ?: return@forEachIndexed
            val number = slide.screenshotNumber.takeIf { it > 0 } ?: index + 1
            parts.put(JSONObject().put("text", "Screenshot $number (use this image as evidence):"))
            parts.put(JSONObject().put(
                "inline_data", JSONObject()
                    .put("mime_type", "image/jpeg")
                    .put("data", Base64.encodeToString(shrinkForUpload(jpeg), Base64.NO_WRAP))
            ))
        }
        return JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", parts)))
    }

    private fun parseReply(source: AiSource, body: String): String {
        return try {
            val root = JSONObject(body)
            if (root.has("error")) return ""
            if (source.style == ApiStyle.GEMINI) {
                val candidates = root.optJSONArray("candidates") ?: return ""
                val parts = candidates.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: return ""
                (0 until parts.length()).mapNotNull { parts.optJSONObject(it)?.optString("text") }
                    .filter { it.isNotBlank() }.joinToString("\n")
            } else {
                val choices = root.optJSONArray("choices") ?: return ""
                val message = choices.optJSONObject(0)?.optJSONObject("message") ?: return ""
                when (val content = message.opt("content")) {
                    is String -> content
                    is JSONArray -> (0 until content.length()).mapNotNull { i ->
                        content.optJSONObject(i)?.optString("text")
                    }.joinToString("\n")
                    else -> ""
                }
            }
        } catch (_: Exception) {
            if (body.trimStart().startsWith("{")) "" else body
        }
    }

    private fun describeFailure(response: ApiResponse, key: String): String {
        fun scrub(value: String): String = if (key.isBlank()) value else value.replace(key, "[key]")
        if (response.status == 0) return scrub(response.body).take(130).ifBlank { "Network error." }
        val detail = try {
            val root = JSONObject(response.body)
            root.optJSONObject("error")?.optString("message")
                ?.takeIf { it.isNotBlank() } ?: root.optString("message").takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }
        val safe = scrub(detail.orEmpty()).replace(Regex("[\\r\\n]+"), " ").take(130)
        return if (safe.isBlank()) "HTTP ${response.status}" else "HTTP ${response.status}: $safe"
    }

    private fun geminiOcrEvidence(slides: List<Slide>): String {
        if (slides.isEmpty()) return "No captured slide text was available."
        val out = StringBuilder()
        var remaining = MAX_OCR_CHARS
        slides.forEachIndexed { index, slide ->
            if (remaining <= 0) return@forEachIndexed
            val text = slide.ocr.trim().ifBlank { "[No OCR text detected]" }
            val chunk = text.take(remaining)
            val number = slide.screenshotNumber.takeIf { it > 0 } ?: index + 1
            val section = "[Screenshot $number OCR]\n$chunk\n\n"
            out.append(section)
            remaining -= chunk.length
        }
        if (remaining <= 0) out.append("\n[Remaining OCR text omitted to fit provider context limits.]\n")
        return out.toString()
    }

    private fun stitchedOcrEvidence(slides: List<Slide>): String {
        if (slides.isEmpty()) return "No captured slide text was available."
        val ocrs = slides.map { it.ocr }
        val stitched = OcrStitcher.stitchOcr(ocrs)
        return if (stitched.isBlank()) "No captured slide text was available." else stitched.take(MAX_OCR_CHARS)
    }

    private fun failed(message: String) = NotesResult(
        text = message,
        notice = "",
        modeUsed = NotesMode.FAST,
        succeeded = false
    )

    /** Downscale and recompress screenshots before both Gemini requests and the HTML preview. */
    fun shrinkForUpload(jpeg: ByteArray): ByteArray {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
            if (bounds.outWidth <= 0) return jpeg
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= IMAGE_WIDTH) sample *= 2
            var bitmap = BitmapFactory.decodeByteArray(
                jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample }
            ) ?: return jpeg
            if (bitmap.width > IMAGE_WIDTH) {
                val scaled = Bitmap.createScaledBitmap(
                    bitmap, IMAGE_WIDTH, (bitmap.height * IMAGE_WIDTH.toFloat() / bitmap.width).toInt(), true
                )
                bitmap.recycle()
                bitmap = scaled
            }
            val output = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, IMAGE_QUALITY, output)
            bitmap.recycle()
            output.toByteArray()
        } catch (_: Exception) {
            jpeg
        }
    }

    private const val OVERLAP_RULE = "Consecutive captures overlap; lines, equations and diagrams are often CUT across two captures — reconstruct anything cut ONCE and COMPLETE using both captures, never repeat the overlapping region, never drop partial edge content; a diagram split over two captures is ONE complete diagram"

    private val NOTES_PROMPT = """You are an expert JEE teacher and note-taker for Physics, Chemistry and Maths.
Write complete, clear, revision-ready Markdown notes from the supplied lecture evidence.
- Start with a useful # topic title and a one-line explanation of why it matters for JEE.
- Use ## sections, ### subsections, and short numbered derivation steps.
- Explain the idea, reasoning/derivation, then formulas. Preserve every visible formula, condition, unit, reaction step and important example.
- Describe diagrams, graphs and tables accurately. Add [Screenshot N] where a visual is important.
- Consecutive captures overlap; lines, equations and diagrams are often CUT across two captures — reconstruct anything cut ONCE and COMPLETE using both captures, never repeat the overlapping region, never drop partial edge content; a diagram split over two captures is ONE complete diagram.
- Do not invent facts. If OCR is unclear, say so instead of guessing.
- FORMULAS MUST BE PLAIN TEXT WITH UNICODE, NEVER LATEX: use H₂O, x², √, →, ⇌, ΔH, α, β, ∫, Σ, ≥ and similar symbols. Do not use dollar math delimiters or backslash commands.
- Finish with ## ⭐ Quick recap, ## ⚠️ Exam traps, and ## 📌 Likely JEE questions.
Return only the finished notes in Markdown."""

    /** The final "thinking" pass: completeness judge. It may only rebuild what the captures contain. */
    private val GAP_PROMPT = """You are the completeness judge for JEE study notes written from screen captures of a lecture.
You receive the stitched OCR evidence from every capture in order, followed by the complete final notes. If screenshots are attached, inspect them in order too.
Your only job is to make those notes complete and honest. Follow these rules exactly:
1. Verify sequence continuity: numbered steps that skip a number, sentences cut off mid-line, derivations missing a line, and tables or diagrams split across neighboring captures.
2. For split text, formulas or diagrams, compare the two neighboring captures. Reconstruct the missing part ONCE and COMPLETE only when their visible evidence supports it; do not repeat overlap.
3. If content is genuinely absent from ALL captures, insert exactly this marker on its own line: [gap: not visible in captures]
4. NEVER invent anything. Do not supply a formula, constant, value, numbered step, reaction or example from memory or convention. If the captures do not show it and no neighboring capture completes it, mark a gap instead of guessing.
5. Change nothing else: keep the notes' order, wording, headings, Markdown, [Screenshot N] references and plain Unicode formulas exactly as they are.
Return only the finished notes in Markdown."""
}

data class NotesResult(
    val text: String,
    val sources: List<String> = emptyList(),
    val notice: String = "",
    val modeUsed: NotesMode = NotesMode.FAST,
    val succeeded: Boolean = true
)
