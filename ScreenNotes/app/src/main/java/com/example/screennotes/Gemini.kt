package com.example.screennotes

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** One captured screen: the OCR text hint plus the screenshot itself (JPEG bytes, may be null). */
class Slide(val ocr: String, val jpeg: ByteArray?)

/** Calls Google's Gemini API (free tier). Get a key at https://aistudio.google.com/apikey */
object Gemini {
    // If Google retires this model name, change it here.
    private const val MODEL = "gemini-3.8-flash"
    private const val MAX_IMAGES = 40

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

    private fun callApi(apiKey: String, body: JSONObject): Pair<Int, String> {
        val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent")
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

    fun makeNotes(apiKey: String, slides: List<Slide>): String {
        val parts = JSONArray()
        parts.put(JSONObject().put("text", PROMPT))
        slides.forEachIndexed { i, s ->
            parts.put(JSONObject().put("text", "[Screenshot ${i + 1}] OCR hint:\n${s.ocr}"))
            if (s.jpeg != null && i < MAX_IMAGES) {
                parts.put(
                    JSONObject().put(
                        "inline_data", JSONObject()
                            .put("mime_type", "image/jpeg")
                            .put("data", Base64.encodeToString(s.jpeg, Base64.NO_WRAP))
                    )
                )
            }
        }
        val body = JSONObject().put(
            "contents", JSONArray().put(JSONObject().put("parts", parts))
        )

        // Gemini is sometimes busy (503) or rate limited (429): wait and retry a few times.
        var last = Pair(0, "")
        for (attempt in 1..5) {
            last = callApi(apiKey, body)
            val code = last.first
            if (code in 200..299) break
            if (code == 429 || code == 500 || code == 503 || code == 504) {
                Thread.sleep(4000L * attempt)
            } else {
                break
            }
        }
        if (last.first !in 200..299) return "Gemini error ${last.first}: ${last.second}"

        return try {
            JSONObject(last.second).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
        } catch (e: Exception) {
            "Could not read Gemini's reply: ${last.second.take(500)}"
        }
    }
}
