# AndroidX Test's compile-only Error Prone annotations are not used at runtime.
# Keep this suppression restricted to the two references in the runner graph.
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.MustBeClosed
