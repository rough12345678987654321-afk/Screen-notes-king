package com.example.screennotes

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** One captured screen: the OCR text hint plus the screenshot itself (JPEG bytes, may be null). */
class Slide(val ocr: String, val jpeg: ByteArray?)

/** What a notes call produced: the text, the model that wrote it, and whether we had to fall back. */
data class NotesResult(val text: String, val model: String, val fellBack: Boolean)

/**
 * Calls Google's Gemini API (free tier). Get a key at https://aistudio.google.com/apikey
 *
 * Free-tier quota is per Google project and per model, so this client:
 *  - accepts several keys (the owner can create a second free project) and rotates on quota errors,
 *  - shrinks screenshots before uploading so each call spends far fewer tokens,
 *  - falls back to a lighter model when the preferred model's daily limit is used up, and says so.
 */
object Gemini {
    /** Models offered in the app's picker. If Google retires a name, remove it here. */
    val MODELS = listOf("gemini-3.8-flash", "gemini-3.6-flash", "gemini-3.5-flash-lite")
    const val DEFAULT_MODEL = "gemini-3.8-flash"

    /** Used automatically when the preferred model's free daily limit is used up. */
    const val FALLBACK_MODEL = "gemini-3.5-flash-lite"

    private const val MAX_IMAGES = 40
    private const val IMG_WIDTH = 1024 // enough to read slides; about half the tokens of the old 1280px uploads
    private const val IMG_QUALITY = 80

    private val PROMPT = """You are an expert JEE study note-taker. Below are consecutive screenshots from a lecture or video,
in order. Each screenshot comes with OCR text that may contain mistakes, so trust the IMAGE over the OCR text.
Read slide text, handwriting, equations, diagrams, graphs and tables directly from the images.
Ignore interface clutter: clock/battery bars, video-call tiles, participant names, toolbars, floating buttons.

Write exceptionally clean, beautifully formatted study notes in Markdown:
- Use clear headings (# Topic, ## Subtopic) with appropriate emojis (e.g. ⚛️, 🧪, 📐, ⚡, 📌, ⚠️, 💡, ⭐).
- Use structured bullet points, numbered lists for derivation steps, and neat Unicode tables for comparisons.
- Bold key definitions and important formulas. Keep every formula, reaction step, and condition.
- For diagrams and graphs (like adsorption isotherms, curves, or geometry), describe them clearly in words (axes, curves, regions, trends) and render clean text/ASCII schematics when helpful.
- Mention "[Screenshot N]" where a slide's visual graph or diagram matters.
- IMPORTANT formatting rule: do NOT use LaTeX or dollar signs. Write all math, chemistry and physics formulas in plain text with Unicode, for example H₂O, P₄, SO₄²⁻, x/m = aP / (1 + bP), θ = KP / (1 + KP), ΔH, α, β, ∫, Σ, √, →, ⇌, ≈, ≥, ≤, °C.
- End with a concise summary and a list of high-yield JEE exam questions.
- Do not invent facts that are not on the screens.

SCREENSHOTS:
"""

    private fun callApi(apiKey: String, model: String, body: JSONObject): Pair<Int, String> {
        val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("x-goog-api-key", apiKey)
        conn.doOutput = true
        conn.connectTimeout = 30000
        conn.readTimeout = 180000
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.readText() ?: ""
        return Pair(code, text)
    }

    /** Downscale + recompress one screenshot so the free quota lasts longer. */
    private fun shrink(jpeg: ByteArray): ByteArray = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0) return jpeg
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= IMG_WIDTH) sample *= 2
        var bmp = BitmapFactory.decodeByteArray(
            jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return jpeg
        if (bmp.width > IMG_WIDTH) {
            val scaled = Bitmap.createScaledBitmap(
                bmp, IMG_WIDTH, (bmp.height * IMG_WIDTH.toFloat() / bmp.width).toInt(), true
            )
            bmp.recycle()
            bmp = scaled
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, IMG_QUALITY, out)
        bmp.recycle()
        out.toByteArray()
    } catch (e: Exception) {
        jpeg
    }

    /**
     * Makes the study notes for one session. Tries every key with the preferred model first
     * (a 429 means that key's free quota is used up, so the next key gets a turn), then repeats
     * with the fallback model. Never throws: problems come back as [NotesResult.text].
     */
    fun makeNotes(apiKeys: List<String>, slides: List<Slide>, preferredModel: String = DEFAULT_MODEL): NotesResult {
        val keys = apiKeys.map { it.trim() }.filter { it.isNotBlank() }
        if (keys.isEmpty()) {
            return NotesResult("Paste your Gemini API key on the home screen first.", preferredModel, false)
        }
        val parts = JSONArray()
        parts.put(JSONObject().put("text", PROMPT))
        slides.forEachIndexed { i, s ->
            parts.put(JSONObject().put("text", "[Screenshot ${i + 1}] OCR hint:\n${s.ocr}"))
            if (s.jpeg != null && i < MAX_IMAGES) {
                parts.put(
                    JSONObject().put(
                        "inline_data", JSONObject()
                            .put("mime_type", "image/jpeg")
                            .put("data", Base64.encodeToString(shrink(s.jpeg), Base64.NO_WRAP))
                    )
                )
            }
        }
        val body = JSONObject().put(
            "contents", JSONArray().put(JSONObject().put("parts", parts))
        )

        val models = if (preferredModel == FALLBACK_MODEL) listOf(FALLBACK_MODEL)
        else listOf(preferredModel, FALLBACK_MODEL)
        for ((mi, model) in models.withIndex()) {
            var ki = 0
            var waitS = 0
            while (ki < keys.size) {
                val (code, reply) = callApi(keys[ki], model, body)
                when {
                    code in 200..299 -> return NotesResult(parseReply(reply), model, mi > 0)
                    // Quota or bad key for THIS project: the next key is a different project.
                    code == 429 || code == 401 || code == 403 -> ki++
                    // Model name retired by Google: skip to the next model.
                    code == 404 -> break
                    // Gemini busy (503) or hiccup (500/504): back off, then try the next key.
                    code in 500..599 -> {
                        waitS = if (waitS == 0) 4 else waitS * 2
                        if (waitS > 32) { ki++; waitS = 0 } else Thread.sleep(waitS * 1000L)
                    }
                    else -> return NotesResult("Gemini error $code: $reply", model, mi > 0)
                }
            }
        }
        return NotesResult(
            "All Gemini keys are used up for today (the free limit resets at midnight US Pacific). " +
                "Try again tomorrow, or add another free key from a second Google project on the home screen.",
            models.last(), true
        )
    }

    private fun parseReply(reply: String): String = try {
        JSONObject(reply).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
    } catch (e: Exception) {
        "Could not read Gemini's reply: ${reply.take(500)}"
    }
}
