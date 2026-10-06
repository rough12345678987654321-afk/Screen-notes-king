package com.example.screennotes

/**
 * Gemini sometimes writes LaTeX even when the prompt forbids it (flash-lite especially).
 * This rewrites the common LaTeX fragments into the plain Unicode the app displays,
 * so notes never reach the owner with dollar signs or backslashes in them.
 */
object NotesClean {

    private val SUP = mapOf(
        '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴',
        '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
        '-' to '⁻', '+' to '⁺', '=' to '⁼', '(' to '⁽', ')' to '⁾', 'n' to 'ⁿ'
    )
    private val SUB = mapOf(
        '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄',
        '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉',
        '-' to '₋', '+' to '₊', '=' to '₌', '(' to '₍', ')' to '₎',
        'a' to 'ₐ', 'e' to 'ₑ', 'i' to 'ᵢ', 'o' to 'ₒ', 'r' to 'ᵣ',
        'u' to 'ᵤ', 'v' to 'ᵥ', 'x' to 'ₓ', 'k' to 'ₖ', 'm' to 'ₘ',
        'n' to 'ₙ', 'p' to 'ₚ', 's' to 'ₛ', 't' to 'ₜ'
    )
    private val GREEK = mapOf(
        "varepsilon" to "ε", "varphi" to "φ",
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε",
        "zeta" to "ζ", "eta" to "η", "theta" to "θ", "iota" to "ι", "kappa" to "κ",
        "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ", "pi" to "π", "rho" to "ρ",
        "sigma" to "σ", "tau" to "τ", "upsilon" to "υ", "phi" to "φ", "chi" to "χ",
        "psi" to "ψ", "omega" to "ω",
        "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ", "Xi" to "Ξ",
        "Pi" to "Π", "Sigma" to "Σ", "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω"
    )
    private val SYMBOLS = mapOf(
        "rightleftharpoons" to "⇌", "leftrightarrows" to "⇄",
        "rightarrow" to "→", "leftarrow" to "←", "Rightarrow" to "⇒", "Leftarrow" to "⇐",
        "leftrightarrow" to "↔", "uparrow" to "↑", "downarrow" to "↓",
        "cdotp" to "·", "cdot" to "·", "times" to "×", "div" to "÷", "pm" to "±", "mp" to "∓",
        "approx" to "≈", "neq" to "≠", "leq" to "≤", "geq" to "≥", "ll" to "≪", "gg" to "≫",
        "propto" to "∝", "equiv" to "≡", "sim" to "~",
        "infty" to "∞", "partial" to "∂", "nabla" to "∇", "circ" to "°", "angle" to "∠",
        "int" to "∫", "iint" to "∬", "sum" to "Σ", "prod" to "Π",
        "in" to "∈", "notin" to "∉", "subset" to "⊂", "cup" to "∪", "cap" to "∩",
        "forall" to "∀", "exists" to "∃", "emptyset" to "∅"
    )
    private val FUNCTIONS = listOf(
        "ln", "log", "sin", "cos", "tan", "cot", "sec", "csc", "exp", "lim",
        "max", "min", "arg", "deg", "mod", "det", "dim", "ker"
    )

    /** Rewrites leftover LaTeX into plain Unicode. Leaves clean text untouched. */
    fun cleanNotes(md: String): String {
        if (!md.contains('\\') && !md.contains('$')) return md
        var s = md

        // 1. Drop math-mode wrappers: $$ ... $$, \( ... \), \[ ... \]
        s = s.replace("$$", "")
        s = s.replace("\\[", "").replace("\\]", "")
        s = s.replace("\\(", "").replace("\\)", "")

        // 2. \frac{a}{b} -> a/b (repeat: fractions inside fractions)
        val frac = Regex("""\\[dt]?frac\s*\{([^{}]*)\}\s*\{([^{}]*)\}""")
        repeat(3) {
            s = frac.replace(s) { m ->
                val (a, b) = m.groupValues[1] to m.groupValues[2]
                val wrap = { x: String -> if (Regex("""[+\-]""").containsMatchIn(x)) "($x)" else x }
                "${wrap(a)}/${wrap(b)}"
            }
        }

        // 3. \sqrt{x} -> sqrt(x) (the root sign alone is ambiguous without overlines)
        s = Regex("""\\sqrt\s*\{([^{}]*)\}""").replace(s) { "√(${it.groupValues[1]})" }

        // 4. \text{..}, \mathrm{..}, \mathbf{..}, \operatorname{..} -> their content
        s = Regex("""\\(?:text|mathrm|mathbf|mathit|operatorname|bm)\s*\{([^{}]*)\}""")
            .replace(s) { it.groupValues[1] }

        // 5. Greek letters and symbols (longest names first so \vartheta beats \theta etc.)
        for ((k, v) in GREEK.toList().sortedByDescending { it.first.length }) {
            s = Regex("""\\$k(?![A-Za-z])""").replace(s, Regex.escapeReplacement(v))
        }
        for ((k, v) in SYMBOLS.toList().sortedByDescending { it.first.length }) {
            s = Regex("""\\$k(?![A-Za-z])""").replace(s, Regex.escapeReplacement(v))
        }
        for (f in FUNCTIONS) {
            s = Regex("""\\$f(?![A-Za-z])""").replace(s, f)
        }

        // 6. Sizing and spacing commands: gone
        s = s.replace(Regex("""\\(?:left|right|big|Big|bigg|Bigg|quad|qquad|,|;|:|!|displaystyle|limits)"""), " ")

        // 7. Superscripts and subscripts: ^{..} / _{..} and single characters
        val mapChars = { raw: String, table: Map<Char, Char> ->
            if (raw.isNotEmpty() && raw.all { table.containsKey(it) })
                raw.map { table[it]!! }.joinToString("")
            else null
        }
        s = Regex("""\^\{([^{}]*)\}""").replace(s) { m -> mapChars(m.groupValues[1], SUP) ?: "^${m.groupValues[1]}" }
        s = Regex("""\^(\S)""").replace(s) { m -> mapChars(m.groupValues[1], SUP) ?: m.value }
        s = Regex("""_\{([^{}]*)\}""").replace(s) { m -> mapChars(m.groupValues[1], SUB) ?: "_${m.groupValues[1]}" }
        s = Regex("""_(\S)""").replace(s) { m -> mapChars(m.groupValues[1], SUB) ?: m.value }

        // 8. Anything still escaped loses its backslash; stray dollar signs go too
        s = s.replace(Regex("""\\+([A-Za-z])"""), "$1")
        s = s.replace("$", "")
        return s
    }
}
