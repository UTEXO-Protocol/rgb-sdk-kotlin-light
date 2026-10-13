# Keep only the fixture's externally-invoked instrumentation bridge. SDK
# implementation retention must come from the packaged AAR's consumer rules.
-keep class com.utexo.rgb.sdk.releasesmoke.ReleaseSignerSmoke { public *; }

# The separately-minified AndroidX instrumentation APK resolves Kotlin runtime
# classes from the target APK. R8 cannot see cross-APK runner references while
# shrinking the target. This fixture-only keep does not retain any SDK classes.
-keep class kotlin.** { *; }
-keep class com.utexo.rgb.sdk.releasesmoke.ReleaseTransactionSmoke { public *; }
-keep class com.utexo.rgb.sdk.releasesmoke.ReleaseGatewaySmoke { public *; }
-keep class com.utexo.rgb.sdk.releasesmoke.ReleaseGatewaySpendingSmoke {
    public *;
}
