# UniFFI uses JNA reflection internally. These implementation classes are
# Kotlin-internal, but their names, native methods, Structure layouts, and
# runtime annotations must survive app R8.
-keep class com.utexo.rgb.sdk.internal.ffi.** { *; }
-keep class * extends com.sun.jna.Structure { *; }
-keepattributes RuntimeVisibleAnnotations
-keep class * extends com.sun.jna.ptr.ByReference { *; }
-keep class * implements com.sun.jna.Callback { *; }
-keep class * extends com.sun.jna.Callback { *; }

# JNA discovers parts of its own JNI bridge reflectively and by fixed native
# names. The upstream Android AAR carries no consumer ProGuard metadata.
-keep class com.sun.jna.** { *; }
-dontwarn java.awt.**
