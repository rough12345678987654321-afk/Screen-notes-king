package com.example.screennotes

import org.json.JSONObject
import java.time.Instant

/** Where an update request is at, as shown in the app. */
enum class ReqStatus(val label: String, val active: Boolean = false) {
    WAITING("Waiting for the AI to start", true),
    WORKING("AI is working", true),
    READY("Ready to test"),
    REPLIED("The AI replied"),
    QUESTION("The AI has a question"),
    FAILED("Didn't work"),
    DONE("Done"),
    CLOSED("Closed"),
}

/** An update request = a GitHub issue with the "app-update" label. */
data class UpdateRequest(
    val number: Int,
    val nodeId: String,
    val title: String,
    val body: String,
    val author: String,
    val open: Boolean,
    val stateReason: String,
    val labels: Set<String>,
    val createdAt: Long,
    val updatedAt: Long,
    val url: String,
) {
    /** Status from the labels the AI workflow sets (good enough for the list). */
    val status: ReqStatus
        get() = when {
            !open && stateReason == "completed" -> ReqStatus.DONE
            !open -> ReqStatus.CLOSED
            "ai:working" in labels -> ReqStatus.WORKING
            "ai:failed" in labels -> ReqStatus.FAILED
            "ai:question" in labels -> ReqStatus.QUESTION
            "ai:replied" in labels -> ReqStatus.REPLIED
            "ai:done" in labels -> ReqStatus.READY
            else -> ReqStatus.WAITING
        }

    companion object {
        fun from(o: JSONObject): UpdateRequest {
            val labels = mutableSetOf<String>()
            o.optJSONArray("labels")?.let { a -> for (i in 0 until a.length()) labels += a.getJSONObject(i).optString("name") }
            return UpdateRequest(
                number = o.getInt("number"),
                nodeId = o.optString("node_id"),
                title = o.optString("title"),
                body = o.optString("body").takeUnless { o.isNull("body") } ?: "",
                author = o.optJSONObject("user")?.optString("login") ?: "",
                open = o.optString("state") == "open",
                stateReason = o.optString("state_reason").takeUnless { o.isNull("state_reason") } ?: "",
                labels = labels,
                createdAt = parseTime(o.optString("created_at")),
                updatedAt = parseTime(o.optString("updated_at")),
                url = o.optString("html_url"),
            )
        }
    }
}

/** One step of the AI's checklist: status is done / active / todo / failed / skipped. */
data class BotStep(val text: String, val status: String)

/** The machine-readable part of an AI status comment (see .github/scripts/app_update.py). */
data class BotData(
    val run: String,
    val state: String, // working / done / question / failed
    val steps: List<BotStep>,
    val activity: List<String>,
    val reply: String,
    val error: String?,
    val apk: String?,
    val apkApi: String?,
    val apkName: String?,
    val pr: Int?,
    val branch: String?,
    val compare: String?,
    val runUrl: String?,
    val model: String?,
)

/** A message in the conversation: from you (issue/comments) or from the AI (status comments). */
data class Message(
    val id: Long,
    val author: String,
    val text: String,
    val images: List<String>,
    val createdAt: Long,
    val updatedAt: Long,
    val bot: BotData?,
) {
    val fromAi get() = bot != null
}

fun parseTime(s: String?): Long = try {
    if (s.isNullOrBlank()) 0 else Instant.parse(s).toEpochMilli()
} catch (e: Exception) {
    0
}

object UpdateParse {
    private val MARKER = Regex("""<!--\s*sn-bot\s+(\{.*?\})\s*-->""", RegexOption.DOT_MATCHES_ALL)
    private val IMAGE_MD = Regex("""!\[[^\]]*]\(\s*<?([^)\s>]+)>?(?:\s+"[^"]*")?\s*\)""")
    private val IMAGE_HTML = Regex("""<img\b[^>]*?\bsrc=["']([^"']+)["'][^>]*>""", RegexOption.IGNORE_CASE)
    const val NO_AI = "<!-- sn:no-ai -->"

    fun bot(body: String): BotData? {
        val m = MARKER.find(body) ?: return null
        return try {
            val o = JSONObject(m.groupValues[1])
            val steps = mutableListOf<BotStep>()
            o.optJSONArray("steps")?.let { a ->
                for (i in 0 until a.length()) {
                    val s = a.optJSONArray(i) ?: continue
                    steps += BotStep(s.optString(0), s.optString(1))
                }
            }
            val activity = mutableListOf<String>()
            o.optJSONArray("activity")?.let { a -> for (i in 0 until a.length()) activity += a.optString(i) }
            fun str(k: String) = if (o.isNull(k)) null else o.optString(k).ifBlank { null }
            BotData(
                run = o.optString("run"),
                state = o.optString("state", "working"),
                steps = steps,
                activity = activity,
                reply = str("reply") ?: "",
                error = str("error"),
                apk = str("apk"),
                apkApi = str("apk_api"),
                apkName = str("apk_name"),
                pr = if (o.isNull("pr")) null else o.optInt("pr").takeIf { it > 0 },
                branch = str("branch"),
                compare = str("compare"),
                runUrl = str("run_url"),
                model = str("model"),
            )
        } catch (e: Exception) {
            null
        }
    }

    fun images(md: String): List<String> =
        (IMAGE_MD.findAll(md).map { it.groupValues[1] } + IMAGE_HTML.findAll(md).map { it.groupValues[1] })
            .map { it.replace("&amp;", "&") }.distinct().toList()

    /** Message text without images, hidden markers and the app's footer. */
    fun cleanText(md: String): String = md
        .replace(IMAGE_MD, "")
        .replace(IMAGE_HTML, "")
        .replace(Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""<sub>Sent from the Screen Notes app.*?</sub>""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""(?m)^###\s*(Screenshots|Attachments|Files)\s*$"""), "")
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()

    fun messages(req: UpdateRequest, comments: List<JSONObject>): List<Message> {
        val out = mutableListOf(Message(0, req.author, cleanText(req.body), images(req.body), req.createdAt, req.createdAt, null))
        for (c in comments) {
            val body = c.optString("body")
            val login = c.optJSONObject("user")?.optString("login") ?: ""
            val bot = bot(body)
            if (bot == null && login.endsWith("[bot]")) continue // other bots: not part of the conversation
            out += Message(
                id = c.optLong("id"),
                author = login,
                text = if (bot != null) "" else cleanText(body),
                images = if (bot != null) emptyList() else images(body),
                createdAt = parseTime(c.optString("created_at")),
                updatedAt = parseTime(c.optString("updated_at")),
                bot = bot,
            )
        }
        return out
    }

    /** More precise status for the detail screen, using the conversation itself. */
    fun status(req: UpdateRequest, msgs: List<Message>): ReqStatus {
        if (!req.open) return req.status
        val lastAi = msgs.lastOrNull { it.fromAi }
        val lastYou = msgs.lastOrNull { !it.fromAi }
        // You wrote something after the AI's last update: a new run is about to start.
        if (lastYou != null && (lastAi == null || lastYou.createdAt > lastAi.createdAt)) return ReqStatus.WAITING
        return when (lastAi?.bot?.state) {
            "working" -> ReqStatus.WORKING
            "done" -> if (lastAi?.bot?.apk != null) ReqStatus.READY else ReqStatus.REPLIED
            "question" -> ReqStatus.QUESTION
            "failed" -> ReqStatus.FAILED
            else -> req.status
        }
    }
}
