package com.example.screennotes

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A small Markdown renderer for AI replies: headings, lists, quotes, code, bold/italic, links. */
internal sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Para(val text: String) : MdBlock()
    data class Bullet(val text: String, val indent: Int, val marker: String) : MdBlock()
    data class Code(val text: String) : MdBlock()
    data class Quote(val text: String) : MdBlock()
    data object Rule : MdBlock()
}

private val BULLET = Regex("""^[-*+]\s+""")
private val NUMBERED = Regex("""^(\d+)[.)]\s+""")
private val HEADING = Regex("""^(#{1,6})\s+""")
private val RULE = Regex("""^(-{3,}|\*{3,}|_{3,})$""")

internal fun parseBlocks(md: String): List<MdBlock> {
    val src = md.replace("\r\n", "\n")
        .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("<summary>(.*?)</summary>", RegexOption.IGNORE_CASE), "**$1**")
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("</?(details|sub|sup|p|div|span|b|i|em|strong|kbd)\\b[^>]*>", RegexOption.IGNORE_CASE), "")
    val lines = src.split("\n")
    val out = mutableListOf<MdBlock>()
    val para = StringBuilder()
    fun flush() {
        if (para.isNotBlank()) out += MdBlock.Para(para.toString().trim())
        para.clear()
    }
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val t = line.trim()
        val indent = (line.length - line.trimStart().length) / 2
        when {
            t.startsWith("```") -> {
                flush()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    code.append(lines[i]).append('\n')
                    i++
                }
                out += MdBlock.Code(code.toString().trimEnd())
            }
            t.isEmpty() -> flush()
            HEADING.containsMatchIn(t) -> {
                flush()
                val level = HEADING.find(t)!!.groupValues[1].length
                out += MdBlock.Heading(level, t.replace(HEADING, ""))
            }
            RULE.matches(t) -> { flush(); out += MdBlock.Rule }
            BULLET.containsMatchIn(t) -> {
                flush()
                var text = t.replace(BULLET, "")
                var marker = "•"
                if (text.startsWith("[ ] ")) { marker = "☐"; text = text.drop(4) }
                else if (text.startsWith("[x] ", ignoreCase = true)) { marker = "☑"; text = text.drop(4) }
                out += MdBlock.Bullet(text, indent, marker)
            }
            NUMBERED.containsMatchIn(t) -> {
                flush()
                val num = NUMBERED.find(t)!!.groupValues[1]
                out += MdBlock.Bullet(t.replace(NUMBERED, ""), indent, "$num.")
            }
            t.startsWith(">") -> { flush(); out += MdBlock.Quote(t.removePrefix(">").trim()) }
            t.startsWith("|") -> {
                // Tables: shown as monospaced text.
                flush()
                val table = StringBuilder()
                while (i < lines.size && lines[i].trim().startsWith("|")) {
                    val row = lines[i].trim()
                    if (!Regex("""^\|[\s:|-]+\|?$""").matches(row)) table.append(row).append('\n')
                    i++
                }
                i--
                out += MdBlock.Code(table.toString().trimEnd())
            }
            else -> {
                if (para.isNotEmpty()) para.append('\n')
                para.append(t)
            }
        }
        i++
    }
    flush()
    return out
}

private val INLINE = Regex(
    """`([^`]+)`""" +                                   // 1 code
        """|\*\*(.+?)\*\*|__(.+?)__""" +                // 2,3 bold
        """|(?<![\w*])\*(?![\s*])(.+?)(?<!\s)\*(?![\w*])""" + // 4 italic
        """|(?<![\w_])_(?![\s_])(.+?)(?<!\s)_(?![\w_])""" +   // 5 italic
        """|\[([^\]]+)]\(([^)\s]+)\)""" +              // 6,7 link
        """|(https?://[^\s<>()]+[^\s<>().,;:!?'"])"""   // 8 bare URL
)

private fun AnnotatedString.Builder.appendInline(s: String, link: Color, codeBg: Color) {
    val linkStyle = TextLinkStyles(SpanStyle(color = link, textDecoration = TextDecoration.Underline))
    var pos = 0
    for (m in INLINE.findAll(s)) {
        if (m.range.first > pos) append(s.substring(pos, m.range.first))
        val g = m.groupValues
        when {
            g[1].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { append(g[1]) }
            g[2].isNotEmpty() || g[3].isNotEmpty() ->
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendInline(g[2].ifEmpty { g[3] }, link, codeBg) }
            g[4].isNotEmpty() || g[5].isNotEmpty() ->
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInline(g[4].ifEmpty { g[5] }, link, codeBg) }
            g[6].isNotEmpty() -> withLink(LinkAnnotation.Url(g[7], linkStyle)) { append(g[6]) }
            g[8].isNotEmpty() -> withLink(LinkAnnotation.Url(g[8], linkStyle)) { append(g[8]) }
            else -> append(m.value)
        }
        pos = m.range.last + 1
    }
    if (pos < s.length) append(s.substring(pos))
}

fun inlineMarkdown(s: String, link: Color, codeBg: Color): AnnotatedString =
    buildAnnotatedString { appendInline(s, link, codeBg) }

fun markdownToHtml(title: String, md: String, images: Map<Int, String> = emptyMap()): String {
    val blocks = parseBlocks(md)
    val bodyHtml = StringBuilder()
    for (b in blocks) {
        when (b) {
            is MdBlock.Heading -> bodyHtml.append("<h${b.level}>${embedImages(escapeHtml(b.text), images)}</h${b.level}>\n")
            is MdBlock.Para -> bodyHtml.append("<p>${embedImages(escapeHtml(b.text), images)}</p>\n")
            is MdBlock.Bullet -> bodyHtml.append("<ul><li>${embedImages(escapeHtml(b.text), images)}</li></ul>\n")
            is MdBlock.Code -> bodyHtml.append("<pre><code>${escapeHtml(b.text)}</code></pre>\n")
            is MdBlock.Quote -> bodyHtml.append("<blockquote>${embedImages(escapeHtml(b.text), images)}</blockquote>\n")
            MdBlock.Rule -> bodyHtml.append("<hr/>\n")
        }
    }
    return """
        <!DOCTYPE html>
        <html>
        <head>
        <meta charset="utf-8">
        <title>$title</title>
        <style>
          body { font-family: sans-serif; padding: 20px; line-height: 1.6; color: #222; }
          h1, h2, h3 { color: #333; margin-top: 24px; }
          p { margin: 8px 0; }
          ul { margin: 8px 0; padding-left: 24px; }
          pre { background: #f5f5f5; padding: 12px; border-radius: 6px; overflow-x: auto; font-family: monospace; font-size: 13px; }
          blockquote { border-left: 4px solid #ccc; margin: 0; padding-left: 12px; color: #555; }
          hr { border: none; border-top: 1px solid #ddd; margin: 20px 0; }
          img.shot { max-width: 100%; border: 1px solid #ddd; border-radius: 6px; margin: 10px 0; }
          figcaption { color: #777; font-size: 12px; margin-top: 2px; }
        </style>
        </head>
        <body>
        <h1>$title</h1>
        $bodyHtml
        </body>
        </html>
    """.trimIndent()
}

/** Replaces [Screenshot N] references with the actual captured slide, so the notes and the PDF show what the teacher drew. */
private fun embedImages(escaped: String, images: Map<Int, String>): String {
    if (images.isEmpty()) return escaped
    return Regex("""\[Screenshot (\d+)\]""").replace(escaped) { m ->
        val n = m.groupValues[1].toIntOrNull() ?: return@replace m.value
        val b64 = images[n] ?: return@replace m.value
        "<figure><img class=\"shot\" src=\"data:image/jpeg;base64,$b64\" alt=\"Screenshot $n\"/>" +
            "<figcaption>Screenshot $n</figcaption></figure>"
    }
}

private fun escapeHtml(s: String): String = s
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&#39;")
    .replace(Regex("""\*\*(.+?)\*\*"""), "<b>$1</b>")
    .replace(Regex("""\*(.+?)\*"""), "<i>$1</i>")
    .replace(Regex("""`([^`]+)`"""), "<code>$1</code>")

/** One line of text with inline Markdown (bold, `code`, links). */
@Composable
fun InlineMd(text: String, style: TextStyle = MaterialTheme.typography.bodyMedium, color: Color = Color.Unspecified) {
    val link = MaterialTheme.colorScheme.primary
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val annotated = remember(text, link, codeBg) { inlineMarkdown(text, link, codeBg) }
    Text(annotated, style = style, color = color)
}

@Composable
fun MarkdownText(md: String, modifier: Modifier = Modifier) {
    val blocks = remember(md) { parseBlocks(md) }
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (b in blocks) {
            when (b) {
                is MdBlock.Heading -> InlineMd(
                    b.text,
                    style = when (b.level) {
                        1 -> type.titleLarge
                        2 -> type.titleMedium
                        else -> type.titleSmall
                    }
                )
                is MdBlock.Para -> InlineMd(b.text)
                is MdBlock.Bullet -> Row(Modifier.padding(start = (b.indent * 16).dp)) {
                    Text(b.marker, style = type.bodyMedium, modifier = Modifier.width(if (b.marker.length > 2) 28.dp else 18.dp))
                    InlineMd(b.text)
                }
                is MdBlock.Code -> Box(
                    Modifier.fillMaxWidth().background(colors.surfaceVariant, RoundedCornerShape(6.dp))
                        .horizontalScroll(rememberScrollState()).padding(8.dp)
                ) {
                    Text(b.text, style = type.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp))
                }
                is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(colors.outlineVariant))
                    Box(Modifier.padding(start = 8.dp)) { InlineMd(b.text, color = colors.onSurfaceVariant) }
                }
                MdBlock.Rule -> HorizontalDivider()
            }
        }
    }
}
