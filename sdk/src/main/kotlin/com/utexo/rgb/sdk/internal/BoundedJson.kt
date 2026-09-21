package com.utexo.rgb.sdk.internal

/**
 * Constant-space lexical budget before kotlinx.serialization's recursive tree reader.
 * This is not a replacement JSON grammar: the strict reader still validates syntax.
 * Quotes and escapes must be recognized so string contents cannot alter the budget.
 */
internal object BoundedJson {
    internal const val MAX_DEPTH: Int = 64
    internal const val MAX_TOKENS: Int = 100_000
    internal const val MAX_CHARACTERS: Int = 1_500_000

    internal fun check(body: String): Unit {
        require(body.length <= MAX_CHARACTERS) { "JSON exceeds character budget" }
        val stack = CharArray(MAX_DEPTH)
        var depth = 0
        var tokens = 0
        var quoted = false
        var escaped = false
        var primitive = false
        fun token() {
            tokens += 1
            require(tokens <= MAX_TOKENS) { "JSON exceeds token budget" }
        }
        for (character in body) {
            if (quoted) {
                if (escaped) escaped = false
                else when (character) {
                    '\\' -> escaped = true
                    '"' -> quoted = false
                }
                continue
            }
            when (character) {
                '"' -> { token(); quoted = true; primitive = false }
                '{', '[' -> {
                    token()
                    require(depth < MAX_DEPTH) { "JSON exceeds nesting budget" }
                    stack[depth++] = character
                    primitive = false
                }
                '}', ']' -> {
                    token()
                    require(depth > 0 && stack[--depth] == if (character == '}') '{' else '[') {
                        "JSON has mismatched containers"
                    }
                    primitive = false
                }
                ':', ',' -> { token(); primitive = false }
                ' ', '\t', '\n', '\r' -> primitive = false
                else -> if (!primitive) { token(); primitive = true }
            }
        }
        require(!quoted && depth == 0) { "JSON has unterminated contents" }
    }
}
