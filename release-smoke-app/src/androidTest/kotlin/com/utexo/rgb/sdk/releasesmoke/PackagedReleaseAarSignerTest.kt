package com.utexo.rgb.sdk.releasesmoke

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
public class PackagedReleaseAarSignerTest {
    @Test
    public fun minifiedOrdinaryFactoryValidatesReceiveWithRealNativeParser() {
        assertTrue(ReleaseGatewaySmoke.validatesReceiveWithNativeParserThroughOrdinaryFactory(InstrumentationRegistry.getInstrumentation().targetContext))
    }
    @Test
    public fun minifiedSeedCustodyRoundTripsWithRealKeystore() {
        assertTrue(ReleaseSignerSmoke.seedCustodyRoundTripsAndDoesNotSilentlyChangePolicy(InstrumentationRegistry.getInstrumentation().targetContext))
    }
    @Test
    public fun minifiedOrdinaryFactoryRejectsDeepGatewayJson() {
        assertTrue(ReleaseGatewaySmoke.rejectsDeepJsonWithoutCrashing(InstrumentationRegistry.getInstrumentation().targetContext))
    }
    @Test
    public fun minifiedConsumerSignsAndFinalizesRealTaprootPsbt() {
        assertTrue(ReleaseTransactionSmoke.signsAndFinalizesThroughPackagedFacade())
    }
    @Test
    public fun minifiedConsumerCreatesWalletWithDeviceProtectedStorage() {
        assertTrue(ReleaseSignerSmoke.ordinaryFactoryOpensAndReopens(InstrumentationRegistry.getInstrumentation().targetContext))
    }

    @Test
    public fun minifiedConsumerLoadsNativeSignerAndMatchesRnVector() {
        assertTrue(ReleaseSignerSmoke.matchesRnKeyDerivationVector())
    }
}
