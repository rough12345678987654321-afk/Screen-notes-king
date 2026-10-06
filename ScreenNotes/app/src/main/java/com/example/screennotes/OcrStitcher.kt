package com.example.screennotes

import kotlin.math.max
import kotlin.math.min

/**
 * Pure helper merging consecutive OCR transcripts into one continuous transcript.
 * Per pair: finds overlap between previous tail and next head (sliding window <= 12 lines,
 * normalized fuzzy line match, similarity >= 0.8), drops the duplicated head, glues a cut-off
 * last line to its continuation, and joins with [capture boundary] markers.
 */
object OcrStitcher {

    fun stitchOcr(ocrs: List<String>): String {
        val nonBlank = ocrs.map { it.trim() }.filter { it.isNotEmpty() }
        if (nonBlank.isEmpty()) return ""
        var result = nonBlank[0]
        for (i in 1 until nonBlank.size) {
            result = stitchPair(result, nonBlank[i])
        }
        return result
    }

    private fun stitchPair(text1: String, text2: String): String {
        val lines1 = text1.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val lines2 = text2.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines1.isEmpty()) return text2
        if (lines2.isEmpty()) return text1

        val maxWindow = min(12, min(lines1.size, lines2.size))
        var bestK = 0

        for (k in maxWindow downTo 1) {
            var match = true
            for (i in 0 until k) {
                val s1 = lines1[lines1.size - k + i]
                val s2 = lines2[i]
                if (lineSimilarity(s1, s2) < 0.8) {
                    match = false
                    break
                }
            }
            if (match) {
                bestK = k
                break
            }
        }

        if (bestK > 0) {
            val outLines = lines1.toMutableList()
            val last1 = lines1.last()
            val match2 = lines2[bestK - 1]
            val prefixLen = min(last1.length, 10)
            if (match2.length > last1.length && match2.startsWith(last1.take(prefixLen), ignoreCase = true)) {
                outLines[outLines.size - 1] = match2
            }
            val remaining = lines2.drop(bestK)
            return if (remaining.isEmpty()) {
                outLines.joinToString("\n")
            } else {
                outLines.joinToString("\n") + "\n[capture boundary]\n" + remaining.joinToString("\n")
            }
        }

        val last1 = lines1.last()
        val first2 = lines2.first()
        val norm1 = last1.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        val norm2 = first2.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

        var glued = false
        var newLast: String? = null

        if (norm2.startsWith(norm1) && first2.length > last1.length) {
            newLast = first2
            glued = true
        } else {
            val maxCharOv = min(min(last1.length, first2.length), 30)
            var foundCharOv = 0
            for (ch in maxCharOv downTo 3) {
                if (last1.takeLast(ch).equals(first2.take(ch), ignoreCase = true)) {
                    foundCharOv = ch
                    break
                }
            }
            if (foundCharOv > 0) {
                newLast = last1 + first2.drop(foundCharOv)
                glued = true
            } else if (last1.endsWith("-")) {
                newLast = last1.dropLast(1) + first2
                glued = true
            } else {
                val endsWithPunct = listOf(".", "!", "?", ":", ";").any { last1.trimEnd().endsWith(it) }
                val startsContinuation = first2.firstOrNull()?.let { it.isLowerCase() || !it.isLetterOrDigit() } ?: false
                if (!endsWithPunct && startsContinuation) {
                    newLast = "$last1 $first2"
                    glued = true
                }
            }
        }

        if (glued && newLast != null) {
            val outLines = lines1.dropLast(1) + newLast
            val remaining = lines2.drop(1)
            return if (remaining.isEmpty()) {
                outLines.joinToString("\n")
            } else {
                outLines.joinToString("\n") + "\n[capture boundary]\n" + remaining.joinToString("\n")
            }
        }

        return lines1.joinToString("\n") + "\n[capture boundary]\n" + lines2.joinToString("\n")
    }

    private fun lineSimilarity(s1: String, s2: String): Double {
        val n1 = s1.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        val n2 = s2.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        if (n1 == n2) return 1.0
        val maxLen = max(n1.length, n2.length)
        if (maxLen == 0) return 1.0
        val dist = levenshtein(n1, n2)
        return 1.0 - (dist.toDouble() / maxLen.toDouble())
    }

    private fun levenshtein(s1: String, s2: String): Int {
        val m = s1.length
        val n = s2.length
        var prev = IntArray(n + 1) { it }
        var curr = IntArray(n + 1)
        for (i in 1..m) {
            curr[0] = i
            val c1 = s1[i - 1]
            for (j in 1..n) {
                val cost = if (c1 == s2[j - 1]) 0 else 1
                curr[j] = min(min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val temp = prev
            prev = curr
            curr = temp
        }
        return prev[n]
    }
}
