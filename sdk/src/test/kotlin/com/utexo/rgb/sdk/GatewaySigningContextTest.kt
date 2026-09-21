package com.utexo.rgb.sdk

import com.utexo.rgb.sdk.internal.EpochSecondsSource
import com.utexo.rgb.sdk.internal.GatewaySigningContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

internal class GatewaySigningContextTest {
    @Test
    fun `height context is explicit bounded and never accepts timestamp locktime domain`(): Unit {
        assertEquals(0, GatewaySigningContext(0).maximumBlockHeight)
        assertEquals(499_999_999, GatewaySigningContext(499_999_999).maximumBlockHeight)
        for (invalid in listOf(-1, 500_000_000, Int.MAX_VALUE)) {
            assertFailsWith<SdkException.InvalidArgument> { GatewaySigningContext(invalid) }
        }
        assertEquals("utexo.psbt.rgb-lib.v1", GatewaySigningContext.PROFILE)
    }

    @Test
    fun `local validation time is read afresh and invalid clock never wraps to unsigned`(): Unit {
        var now = 100L
        val context = GatewaySigningContext(100, EpochSecondsSource { now })
        assertEquals(100, context.maximumBlockHeight)
        assertEquals(100L, context.validationTime())
        now = 101L
        assertEquals(101L, context.validationTime())
        now = -1L
        assertFailsWith<SdkException.InternalInvariantViolation> { context.validationTime() }
        now = 0L
        assertFailsWith<SdkException.InternalInvariantViolation> { context.validationTime() }
    }
}
