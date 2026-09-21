import com.sun.jna.NativeLibrary;
import com.utexo.rgb.sdk.GeneratedKeys;
import com.utexo.rgb.sdk.Network;
import com.utexo.rgb.sdk.RGBSDK;
import com.utexo.rgb.sdk.SdkException;
import com.utexo.rgb.sdk.SecretSeed;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import kotlin.ResultKt;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.coroutines.intrinsics.IntrinsicsKt;

/** Separate JVM per variant: never replace/reset the process's initialized native runtime. */
public final class NativeCompatibilityProbe {
    public static void main(String[] args) throws Exception {
        String library = args[0];
        System.setProperty("uniffi.component.utexo_local_signer.libraryOverride", library);
        NativeLibrary fixture = NativeLibrary.getInstance(library);
        for (String symbol : Files.readAllLines(Path.of(args[1]))) {
            fixture.getFunction(symbol); // All ordinary symbols resolve, not merely the checks.
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            String entry = attempt == 0 ? args[2] : (args[2].equals("key") ? "signer" : "key");
            Throwable failure = call(entry);
            if (!(failure instanceof SdkException.Unsupported) || failure.getCause() != null) {
                throw new AssertionError("Expected sanitized incompatible-library rejection", failure);
            }
            SdkException sdk = (SdkException) failure;
            if (!"compatible native utexo-local-signer library".equals(sdk.getDetails().get("feature"))) {
                throw new AssertionError("Wrong native failure classification");
            }
        }
        int checks = fixture.getFunction("utexo_test_check_count").invokeInt(new Object[0]);
        if (checks != Integer.parseInt(args[3])) throw new AssertionError("Checks were bypassed/retried: " + checks);
        System.out.println("PASS " + args[2] + " first, checks=" + checks + "; no operational FFI entered");
    }

    private static Throwable call(String entry) throws Exception {
        CompletableFuture<Object> outcome = new CompletableFuture<>();
        Continuation<Object> continuation = new Continuation<>() {
            public CoroutineContext getContext() { return EmptyCoroutineContext.INSTANCE; }
            public void resumeWith(Object value) { outcome.complete(value); }
        };
        try (SecretSeed seed = SecretSeed.Companion.fromBytes(new byte[64])) {
            Object result;
            try {
                if (entry.equals("key")) {
                    result = RGBSDK.generateKeys(Network.SIGNET, continuation);
                } else {
                    Class<?> factory = Class.forName("com.utexo.rgb.sdk.internal.UniFfiLocalSigningEngineFactory");
                    var instanceField = factory.getDeclaredField("INSTANCE");
                    instanceField.setAccessible(true);
                    var open = factory.getDeclaredMethod("open", Network.class, SecretSeed.class, Continuation.class);
                    open.setAccessible(true);
                    result = open.invoke(instanceField.get(null), Network.SIGNET, seed, continuation);
                }
                if (result == IntrinsicsKt.getCOROUTINE_SUSPENDED()) result = outcome.get(10, TimeUnit.SECONDS);
                ResultKt.throwOnFailure(result);
                if (result instanceof AutoCloseable) ((AutoCloseable) result).close();
                throw new AssertionError("Native operation unexpectedly succeeded");
            } catch (InvocationTargetException failure) {
                return failure.getCause();
            } catch (SdkException.Unsupported failure) {
                return failure;
            }
        }
    }
}
