package com.utexo.rgb.sdk

import com.utexo.rgb.sdk.internal.BoundedJson
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertFailsWith

internal class BoundedJsonTest {
    @Test
    fun `mixed containers accept depth boundary and reject deeper documents before recursive parsing`(): Unit {
        for (depth in listOf(1, 32, 63, 64, 65, 1_000, 100_000)) {
            val body = nested(depth)
            if (depth <= BoundedJson.MAX_DEPTH) {
                BoundedJson.check(body)
                Json.parseToJsonElement(body)
            } else assertFailsWith<IllegalArgumentException> { BoundedJson.check(body) }
        }
    }

    @Test
    fun `quotes backslashes and escaped brackets do not count as structure`(): Unit {
        val body = "{\"text\":" + Json.encodeToString(kotlinx.serialization.serializer<String>(),
            "[".repeat(100_000) + "\\\"}".repeat(10_000)) + "}"
        BoundedJson.check(body)
        Json.parseToJsonElement(body)
        for (invalid in listOf("{]", "[}", "{", "\"unterminated", "\"escape\\", "]", "{}]")) {
            assertFailsWith<IllegalArgumentException> { BoundedJson.check(invalid) }
        }
    }

    @Test
    fun `flat documents are constrained by token and character budgets`(): Unit {
        BoundedJson.check("[" + List(49_999) { "0" }.joinToString(",") + "]")
        assertFailsWith<IllegalArgumentException> {
            BoundedJson.check("[" + List(50_000) { "0" }.joinToString(",") + "]")
        }
        BoundedJson.check("\"" + "a".repeat(BoundedJson.MAX_CHARACTERS - 2) + "\"")
        assertFailsWith<IllegalArgumentException> {
            BoundedJson.check("\"" + "a".repeat(BoundedJson.MAX_CHARACTERS - 1) + "\"")
        }
    }

    private fun nested(depth: Int): String = buildString {
        repeat(depth) { append(if (it % 2 == 0) "[" else "{\"x\":") }
        append('0')
        for (index in depth - 1 downTo 0) append(if (index % 2 == 0) ']' else '}')
    }
}
