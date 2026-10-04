package com.example.screennotes

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Updates tab settings, stored on this device only. */
class UpdateSettings(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("updates", Context.MODE_PRIVATE)

    var token: String
        get() = p.getString("token", "") ?: ""
        set(v) = p.edit().putString("token", v.trim()).apply()
    var repo: String
        get() = p.getString("repo", null) ?: BuildConfig.DEFAULT_REPO
        set(v) = p.edit().putString("repo", v.trim()).apply()
    var defaultBranch: String
        get() = p.getString("branch", "main") ?: "main"
        set(v) = p.edit().putString("branch", v).apply()
    var repoPrivate: Boolean
        get() = p.getBoolean("private", false)
        set(v) = p.edit().putBoolean("private", v).apply()
    var login: String
        get() = p.getString("login", "") ?: ""
        set(v) = p.edit().putString("login", v).apply()

    /** When you last sent/approved something: background checks run more often for a while after. */
    var lastActionAt: Long
        get() = p.getLong("last_action", 0)
        set(v) = p.edit().putLong("last_action", v).apply()

    val ready get() = token.isNotBlank() && repo.count { it == '/' } == 1

    fun client(): GitHub? = if (ready) GitHub(token, repo) else null

    fun disconnect() = p.edit().remove("token").remove("login").apply()

    // Notification bookkeeping: last AI status we told you about, per request.
    fun seen(n: Int): String? = p.getString("seen_$n", null)
    fun setSeen(n: Int, v: String) = p.edit().putString("seen_$n", v).apply()
    fun lastUpdated(n: Int): Long = p.getLong("upd_$n", 0)
    fun setLastUpdated(n: Int, v: Long) = p.edit().putLong("upd_$n", v).apply()
}

/** A screenshot or file picked to send with a request. */
data class Attachment(val uri: Uri, val name: String, val mime: String) {
    val isImage get() = mime.startsWith("image/")
}

object Attachments {
    private const val MAX_FILE = 8 * 1024 * 1024

    fun describe(ctx: Context, uri: Uri): Attachment {
        var name = "file"
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { name = it }
            }
        }
        val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
        return Attachment(uri, name, mime)
    }

    /** File name + bytes ready to upload. Screenshots are shrunk (max 1600 px, JPEG) to save data. */
    fun prepare(ctx: Context, a: Attachment): Pair<String, ByteArray> {
        val base = safeName(a.name.substringBeforeLast('.'))
        if (a.isImage && !a.mime.contains("gif")) {
            runCatching { shrink(ctx, a.uri) }.getOrNull()?.let { return "$base.jpg" to it }
        }
        val bytes = ctx.contentResolver.openInputStream(a.uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_FILE) throw IOException("${a.name} is too big (max 8 MB).")
            }
            out.toByteArray()
        } ?: throw IOException("Couldn't read ${a.name}")
        val ext = a.name.substringAfterLast('.', "").let { if (it.isNotBlank() && it.length <= 6) "." + safeName(it) else "" }
        return "$base$ext" to bytes
    }

    private fun safeName(s: String) =
        s.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-', '.').take(40).ifBlank { "file" }

    private fun shrink(ctx: Context, uri: Uri, maxSide: Int = 1600): ByteArray {
        val bmp: Bitmap = if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val scale = maxSide.toFloat() / maxOf(w, h)
                if (scale < 1f) decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            val decoded = ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: throw IOException("Not an image")
            val scale = maxSide.toFloat() / maxOf(decoded.width, decoded.height)
            if (scale < 1f) {
                Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1),
                    (decoded.height * scale).toInt().coerceAtLeast(1), true)
            } else decoded
        }
        // Paint onto white so transparent areas don't turn black in the JPEG.
        val flat = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        Canvas(flat).apply { drawColor(Color.WHITE); drawBitmap(bmp, 0f, 0f, null) }
        return ByteArrayOutputStream().also { flat.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
    }
}

/** The newest published build of main (from the "Build APK" workflow). */
data class ReleaseInfo(
    val name: String,
    val sha: String,
    val apiUrl: String?,
    val downloadUrl: String?,
    val publishedAt: Long,
    val note: String?,
)

/** Everything the Updates tab does with GitHub. All functions run off the main thread. */
object UpdateRepo {
    @Volatile var listCache: List<UpdateRequest> = emptyList()
    @Volatile var latestCache: ReleaseInfo? = null
    private val detailCache = ConcurrentHashMap<Int, Pair<UpdateRequest, List<Message>>>()

    fun cached(n: Int) = detailCache[n]

    private fun client(ctx: Context) =
        UpdateSettings(ctx).client() ?: throw IOException("Connect GitHub first (Updates → Settings).")

    /** Link to GitHub's "new token" page with the right permissions already filled in. */
    fun tokenUrl(repo: String): String {
        val desc = "Lets the Screen Notes app send update requests to $repo and install new versions."
        return "https://github.com/settings/personal-access-tokens/new?name=${GitHub.enc("Screen Notes app")}" +
            "&description=${GitHub.enc(desc)}&expires_in=366&contents=write&issues=write&pull_requests=write"
    }

    fun normalizeRepo(input: String): String = input.trim()
        .replace(Regex("^(https?://)?(www\\.)?github\\.com/"), "")
        .removeSuffix("/").removeSuffix(".git").trim('/')
        .split("/").take(2).joinToString("/")

    suspend fun connect(ctx: Context, token: String, repoInput: String): String = withContext(Dispatchers.IO) {
        val repo = normalizeRepo(repoInput)
        if (!Regex("[A-Za-z0-9-]+/[A-Za-z0-9._-]+").matches(repo)) throw IOException("The repository should look like owner/name.")
        val gh = GitHub(token.trim(), repo)
        val info = gh.repoInfo()
        gh.listRequests() // checks the token can read issues
        val login = runCatching { gh.login() }.getOrDefault("")
        val full = info.optString("full_name", repo)
        UpdateSettings(ctx).apply {
            this.token = token.trim()
            this.repo = full
            defaultBranch = info.optString("default_branch", "main")
            repoPrivate = info.optBoolean("private")
            this.login = login
        }
        listCache = emptyList()
        UpdatePoller.start(ctx)
        "Connected to $full"
    }

    suspend fun list(ctx: Context): List<UpdateRequest> = withContext(Dispatchers.IO) {
        client(ctx).listRequests().also { listCache = it }
    }

    suspend fun detail(ctx: Context, n: Int): Pair<UpdateRequest, List<Message>> = withContext(Dispatchers.IO) {
        val gh = client(ctx)
        val req = gh.issue(n)
        // Always re-read comments (cheap thanks to ETags): the AI edits its status comment as it works.
        val msgs = UpdateParse.messages(req, gh.comments(n))
        (req to msgs).also { detailCache[n] = it }
    }

    suspend fun latest(ctx: Context): ReleaseInfo? = withContext(Dispatchers.IO) {
        val r = client(ctx).latestRelease() ?: return@withContext null
        val assets = r.optJSONArray("assets") ?: JSONArray()
        var api: String? = null
        var dl: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk")) {
                api = a.optString("url")
                dl = a.optString("browser_download_url")
                break
            }
        }
        if (api == null) return@withContext null
        val body = r.optString("body")
        val sha = r.optString("target_commitish").takeIf { Regex("[0-9a-f]{40}").matches(it) }
            ?: Regex("Commit: ([0-9a-f]{40})").find(body)?.groupValues?.get(1) ?: ""
        val note = Regex("""\*\*Latest change:\*\*\s*(.+)""").find(body)?.groupValues?.get(1)?.trim()
        ReleaseInfo(r.optString("name"), sha, api, dl, parseTime(r.optString("published_at")), note)
            .also { latestCache = it }
    }

    suspend fun submit(ctx: Context, title: String, text: String, files: List<Attachment>, onStep: (String) -> Unit): Int =
        withContext(Dispatchers.IO) {
            val s = UpdateSettings(ctx)
            val gh = client(ctx)
            val links = uploadAll(ctx, gh, files, onStep)
            onStep("Sending your request…")
            val t = title.trim().ifBlank { autoTitle(text) }
            val issue = gh.createIssue(t, messageBody(text, links, footer = true))
            val n = issue.getInt("number")
            s.setSeen(n, "new")
            s.lastActionAt = System.currentTimeMillis()
            listCache = listOf(UpdateRequest.from(issue)) + listCache
            UpdatePoller.start(ctx)
            n
        }

    suspend fun reply(ctx: Context, n: Int, text: String, files: List<Attachment>, onStep: (String) -> Unit) =
        withContext(Dispatchers.IO) {
            val s = UpdateSettings(ctx)
            val gh = client(ctx)
            val links = uploadAll(ctx, gh, files, onStep)
            onStep("Sending…")
            gh.comment(n, messageBody(text, links, footer = false))
            if (s.seen(n) == null) s.setSeen(n, "new")
            s.lastActionAt = System.currentTimeMillis()
            UpdatePoller.start(ctx)
        }

    suspend fun retry(ctx: Context, n: Int) = reply(ctx, n, "🔁 Please try again.", emptyList()) {}

    /** Merges the AI's change into main (GitHub then builds the final version). */
    suspend fun approve(ctx: Context, req: UpdateRequest, bot: BotData): String = withContext(Dispatchers.IO) {
        val s = UpdateSettings(ctx)
        val gh = client(ctx)
        val branch = bot.branch ?: "ai/update-${req.number}"
        val title = "AI update #${req.number}: ${req.title}"
        val pr = bot.pr?.let { runCatching { gh.pull(it) }.getOrNull() }?.takeIf { it.optString("state") == "open" }
            ?: gh.openPullFor(branch)
            ?: gh.createPull(branch, s.defaultBranch, title, "Closes #${req.number}\n\nApproved in the Screen Notes app after testing.")
        val prNumber = pr.getInt("number")
        try {
            gh.mergePull(prNumber, "$title (#$prNumber)")
        } catch (e: GitHubException) {
            if (e.code == 405 || e.code == 409) {
                throw GitHubException(e.code, "GitHub can't merge this automatically (it clashes with newer changes). " +
                    "Reply \"Please update this to the latest version\" and the AI will redo it.")
            }
            throw e
        }
        runCatching { gh.deleteBranch(branch) }
        runCatching { if (gh.issue(req.number).open) gh.closeIssue(req.nodeId, completed = true) }
        s.lastActionAt = System.currentTimeMillis()
        UpdatePoller.start(ctx)
        "Merged! GitHub is now building the final version. In a few minutes it appears at the top of the " +
            "Updates tab, ready to install."
    }

    /** Closes the request (and the AI's pull request, if any) without changing the app. */
    suspend fun close(ctx: Context, req: UpdateRequest, bot: BotData?) = withContext(Dispatchers.IO) {
        val gh = client(ctx)
        runCatching {
            val pr = bot?.pr?.let { gh.pull(it) } ?: gh.openPullFor(bot?.branch ?: "ai/update-${req.number}")
            if (pr != null && pr.optString("state") == "open") gh.closePull(pr.getString("node_id"))
        }
        gh.closeIssue(req.nodeId, completed = false)
    }

    private fun uploadAll(ctx: Context, gh: GitHub, files: List<Attachment>, onStep: (String) -> Unit): List<Pair<Attachment, String>> {
        if (files.isEmpty()) return emptyList()
        onStep("Preparing upload…")
        gh.ensureUploadBranch()
        val folder = "requests/" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + "-" + (1000..9999).random()
        return files.mapIndexed { i, a ->
            onStep("Uploading ${if (a.isImage) "screenshot" else "file"} ${i + 1} of ${files.size}…")
            val (name, bytes) = Attachments.prepare(ctx, a)
            a to gh.upload("$folder/${i + 1}-$name", bytes)
        }
    }

    private fun messageBody(text: String, links: List<Pair<Attachment, String>>, footer: Boolean) = buildString {
        append(text.trim())
        val images = links.filter { it.first.isImage }
        val others = links.filter { !it.first.isImage }
        if (images.isNotEmpty()) {
            append("\n\n### Screenshots\n")
            images.forEach { (a, url) -> append("\n![${mdSafe(a.name)}]($url)\n") }
        }
        if (others.isNotEmpty()) {
            append("\n\n### Files\n")
            others.forEach { (a, url) -> append("\n- [${mdSafe(a.name)}]($url)") }
        }
        if (footer) {
            val build = BuildConfig.BUILD_LABEL + (if (BuildConfig.GIT_SHA.isNotBlank()) " " + BuildConfig.GIT_SHA.take(7) else "")
            append("\n\n<sub>Sent from the Screen Notes app · v${BuildConfig.VERSION_NAME} ($build) · ")
            append("Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}</sub>")
        }
    }

    private fun mdSafe(s: String) = s.replace(Regex("""[\[\]()<>`*_]"""), " ").trim().ifBlank { "file" }

    private fun autoTitle(text: String): String {
        val first = text.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: "App update"
        return if (first.length <= 70) first else first.take(67).trimEnd() + "…"
    }

    fun copyGeminiKey(ctx: Context): Boolean {
        val key = ctx.getSharedPreferences("p", Context.MODE_PRIVATE).getString("key", "") ?: ""
        if (key.isBlank()) return false
        val clip = ClipData.newPlainText("Gemini API key", key)
        if (Build.VERSION.SDK_INT >= 33) {
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        return true
    }
}
