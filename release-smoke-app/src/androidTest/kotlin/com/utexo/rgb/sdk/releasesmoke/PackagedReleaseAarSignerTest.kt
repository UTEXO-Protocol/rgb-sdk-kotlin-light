package com.utexo.rgb.sdk.releasesmoke

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
public class PackagedReleaseAarSignerTest {
    @Test
    public fun minifiedConsumerLoadsNativeSignerAndMatchesRnVector() {
        assertTrue(ReleaseSignerSmoke.matchesRnKeyDerivationVector())
    }
}
