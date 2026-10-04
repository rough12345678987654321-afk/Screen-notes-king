package com.example.screennotes

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/** A GitHub error with a message that's safe to show to the user. */
class GitHubException(val code: Int, message: String) : IOException(message)

/**
 * Tiny GitHub REST client for the Updates tab (no extra libraries).
 * The token is a fine-grained personal access token with Contents, Issues and
 * Pull requests set to "Read and write" for the app's repository.
 */
class GitHub(private val token: String, val repo: String) {

    companion object {
        const val API = "https://api.github.com"
        const val REQUEST_LABEL = "app-update"
        const val UPLOAD_BRANCH = "app-uploads"

        // Answers that haven't changed come back as "304 Not Modified", which GitHub doesn't count
        // against the rate limit, so refreshing often is cheap.
        private val etags = ConcurrentHashMap<String, Pair<String, String>>()

        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        fun encPath(path: String): String = path.split("/").joinToString("/") { enc(it) }
    }

    private val owner get() = repo.substringBefore("/")

    private fun open(url: String, method: String, accept: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 20_000
        c.readTimeout = 60_000
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Accept", accept)
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        c.setRequestProperty("User-Agent", "ScreenNotes-Android")
        return c
    }

    /** Calls the GitHub API and returns the response body. */
    fun call(method: String, path: String, body: JSONObject? = null, cache: Boolean = false): String {
        val url = if (path.startsWith("http")) path else API + path
        val c = open(url, method, "application/vnd.github+json")
        try {
            val cached = if (cache && method == "GET") etags[url] else null
            cached?.let { c.setRequestProperty("If-None-Match", it.first) }
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = c.responseCode
            if (code == 304 && cached != null) return cached.second
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw GitHubException(code, friendlyError(code, text, c))
            if (cache && method == "GET") c.getHeaderField("ETag")?.let { etags[url] = it to text }
            return text
        } finally {
            c.disconnect()
        }
    }

    private fun friendlyError(code: Int, text: String, c: HttpURLConnection): String {
        val msg = runCatching { JSONObject(text).optString("message") }.getOrNull().orEmpty()
        return when {
            code == 401 ->
                "GitHub didn't accept your token (it may be wrong or expired). Create a new one in Updates → Settings."
            (code == 403 || code == 429) && c.getHeaderField("X-RateLimit-Remaining") == "0" ->
                "GitHub's limit for this hour is used up. Try again in a few minutes."
            code == 403 ->
                "Your token isn't allowed to do this. It needs Contents, Issues and Pull requests set to " +
                    "\"Read and write\" for $repo. ($msg)"
            code == 404 ->
                "Not found on GitHub. Check the repository name ($repo) and that your token has access to it."
            msg.isNotBlank() -> "GitHub: $msg"
            else -> "GitHub error $code"
        }
    }

    private fun obj(method: String, path: String, body: JSONObject? = null, cache: Boolean = false) =
        JSONObject(call(method, path, body, cache))

    private fun arr(path: String, cache: Boolean = false) = JSONArray(call("GET", path, null, cache))

    // ------------------------------------------------------------------ repository / user
    fun repoInfo(): JSONObject = obj("GET", "/repos/$repo")

    fun login(): String = obj("GET", "/user").optString("login")

    // ------------------------------------------------------------------ update requests (issues)
    fun listRequests(): List<UpdateRequest> {
        val a = arr("/repos/$repo/issues?labels=$REQUEST_LABEL&state=all&sort=created&direction=desc&per_page=50", true)
        return (0 until a.length()).map { a.getJSONObject(it) }
            .filter { !it.has("pull_request") }
            .map { UpdateRequest.from(it) }
    }

    fun issue(n: Int): UpdateRequest = UpdateRequest.from(obj("GET", "/repos/$repo/issues/$n", cache = true))

    fun comments(n: Int): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        for (page in 1..10) {
            val a = arr("/repos/$repo/issues/$n/comments?per_page=100&page=$page", true)
            for (i in 0 until a.length()) out += a.getJSONObject(i)
            if (a.length() < 100) break
        }
        return out
    }

    fun createIssue(title: String, body: String): JSONObject {
        ensureLabel()
        val labels = JSONArray().put(REQUEST_LABEL)
        return obj("POST", "/repos/$repo/issues", JSONObject().put("title", title).put("body", body).put("labels", labels))
    }

    private fun ensureLabel() {
        try {
            call("POST", "/repos/$repo/labels", JSONObject().put("name", REQUEST_LABEL).put("color", "1f6feb")
                .put("description", "Change request sent from the Screen Notes app"))
        } catch (e: GitHubException) {
            if (e.code != 422) throw e // 422 = the label already exists
        }
    }

    fun comment(n: Int, body: String): JSONObject =
        obj("POST", "/repos/$repo/issues/$n/comments", JSONObject().put("body", body))

    /** Closes an issue. Uses GraphQL so we don't need HTTP PATCH. */
    fun closeIssue(nodeId: String, completed: Boolean) {
        val q = "mutation(\$id: ID!, \$r: IssueClosedStateReason) { closeIssue(input: {issueId: \$id, stateReason: \$r}) { issue { number } } }"
        val vars = JSONObject().put("id", nodeId).put("r", if (completed) "COMPLETED" else "NOT_PLANNED")
        val res = obj("POST", "$API/graphql", JSONObject().put("query", q).put("variables", vars))
        val errors = res.optJSONArray("errors")
        if (errors != null && errors.length() > 0) {
            throw GitHubException(422, "GitHub: " + errors.getJSONObject(0).optString("message"))
        }
    }

    // ------------------------------------------------------------------ screenshots / files
    /** Makes sure the separate "app-uploads" branch exists (it holds request screenshots, no app code). */
    fun ensureUploadBranch() {
        try {
            call("GET", "/repos/$repo/branches/$UPLOAD_BRANCH")
            return
        } catch (e: GitHubException) {
            if (e.code != 404) throw e
        }
        val readme = "# Uploads from the Screen Notes app\n\nScreenshots and files attached to update requests. " +
            "Old folders can be deleted at any time.\n"
        val entry = JSONObject().put("path", "README.md").put("mode", "100644").put("type", "blob").put("content", readme)
        val tree = obj("POST", "/repos/$repo/git/trees", JSONObject().put("tree", JSONArray().put(entry)))
        val commit = obj("POST", "/repos/$repo/git/commits", JSONObject().put("message", "Start the app-uploads branch")
            .put("tree", tree.getString("sha")).put("parents", JSONArray()))
        try {
            call("POST", "/repos/$repo/git/refs", JSONObject().put("ref", "refs/heads/$UPLOAD_BRANCH")
                .put("sha", commit.getString("sha")))
        } catch (e: GitHubException) {
            if (e.code != 422) throw e // created in the meantime
        }
    }

    /** Uploads a file to the uploads branch and returns a link GitHub shows inline in issues. */
    fun upload(path: String, bytes: ByteArray): String {
        val body = JSONObject().put("message", "Upload $path").put("branch", UPLOAD_BRANCH)
            .put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
        call("PUT", "/repos/$repo/contents/${encPath(path)}", body)
        return "https://github.com/$repo/blob/$UPLOAD_BRANCH/${encPath(path)}?raw=true"
    }

    // ------------------------------------------------------------------ pull requests
    fun pull(n: Int): JSONObject = obj("GET", "/repos/$repo/pulls/$n")

    fun openPullFor(branch: String): JSONObject? {
        val a = arr("/repos/$repo/pulls?state=open&head=${enc("$owner:$branch")}")
        return if (a.length() > 0) a.getJSONObject(0) else null
    }

    fun createPull(branch: String, base: String, title: String, body: String): JSONObject =
        obj("POST", "/repos/$repo/pulls", JSONObject().put("head", branch).put("base", base).put("title", title).put("body", body))

    fun mergePull(n: Int, title: String): JSONObject =
        obj("PUT", "/repos/$repo/pulls/$n/merge", JSONObject().put("merge_method", "squash").put("commit_title", title))

    /** Closes a pull request without merging (GraphQL, so no HTTP PATCH needed). */
    fun closePull(nodeId: String) {
        val q = "mutation(\$id: ID!) { closePullRequest(input: {pullRequestId: \$id}) { pullRequest { number } } }"
        val res = obj("POST", "$API/graphql", JSONObject().put("query", q).put("variables", JSONObject().put("id", nodeId)))
        val errors = res.optJSONArray("errors")
        if (errors != null && errors.length() > 0) {
            throw GitHubException(422, "GitHub: " + errors.getJSONObject(0).optString("message"))
        }
    }

    fun deleteBranch(branch: String) {
        call("DELETE", "/repos/$repo/git/refs/heads/${encPath(branch)}")
    }

    // ------------------------------------------------------------------ releases / downloads
    /** The newest build of main (published by the "Build APK" workflow), or null if none yet. */
    fun latestRelease(): JSONObject? = try {
        obj("GET", "/repos/$repo/releases/latest", cache = true)
    } catch (e: GitHubException) {
        if (e.code == 404) null else throw e
    }

    /**
     * Downloads a release asset to [dest]. [apiUrl] works for private repos too: GitHub answers
     * with a redirect to a temporary storage link, which must be opened WITHOUT our token.
     */
    fun download(apiUrl: String?, browserUrl: String?, dest: File, progress: (Float) -> Unit) {
        var url = apiUrl ?: browserUrl ?: throw IOException("No download link")
        var auth = apiUrl != null
        var hops = 0
        var c: HttpURLConnection
        while (true) {
            c = URL(url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 20_000
            c.readTimeout = 60_000
            c.setRequestProperty("User-Agent", "ScreenNotes-Android")
            if (auth) {
                c.setRequestProperty("Authorization", "Bearer $token")
                c.setRequestProperty("Accept", "application/octet-stream")
            }
            val code = c.responseCode
            if (code in 300..399) {
                val location = c.getHeaderField("Location") ?: throw IOException("Bad redirect from GitHub")
                c.disconnect()
                val next = URL(URL(url), location)
                auth = auth && next.host == URL(url).host
                url = next.toString()
                if (++hops > 6) throw IOException("Too many redirects")
                continue
            }
            if (code !in 200..299) {
                val text = c.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                c.disconnect()
                throw GitHubException(code, friendlyError(code, text, c))
            }
            break
        }
        try {
            val total = c.contentLengthLong
            dest.parentFile?.mkdirs()
            val tmp = File(dest.path + ".part")
            c.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastReport = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastReport) { lastReport = pct; progress(done.toFloat() / total) }
                        }
                    }
                }
            }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) throw IOException("Couldn't save the download")
        } finally {
            c.disconnect()
        }
    }

    /** Converts an uploaded-screenshot link to a direct link + whether it needs our token. */
    fun imageUrl(link: String): Pair<String, Boolean> {
        val m = Regex("""https://github\.com/([^/]+/[^/]+)/(?:blob|raw)/([^/]+)/([^?#]+)""").find(link)
        if (m != null && m.groupValues[1].equals(repo, ignoreCase = true)) {
            val (_, ref, path) = m.destructured
            return "https://raw.githubusercontent.com/$repo/$ref/$path" to true
        }
        return link to false
    }

    val authHeader get() = "Bearer $token"
}
