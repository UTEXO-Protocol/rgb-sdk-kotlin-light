package com.utexo.build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;
import javax.inject.Inject;
import javax.tools.ToolProvider;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** Audits the packaged AAR and proves raw implementation calls fail from Java. */
@DisableCachingByDefault(because = "Runs Java compiler negative API probes")
public abstract class VerifyReleaseJvmApiTask extends DefaultTask {
    @Inject
    public VerifyReleaseJvmApiTask() {}

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getAar();

    @Classpath
    public abstract ConfigurableFileCollection getCompilerClasspath();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getRootKotlinSources();

    @TaskAction
    public final void verify() throws IOException {
        verifyRootInternalInventory();
        File extractedClasses = new File(getTemporaryDir(), "classes.jar");
        try (ZipFile zip = new ZipFile(getAar().get().getAsFile())) {
            var entry = zip.getEntry("classes.jar");
            if (entry == null) {
                throw new IllegalStateException("Release AAR has no classes.jar");
            }
            try (var input = zip.getInputStream(entry);
                 var output = Files.newOutputStream(extractedClasses.toPath())) {
                input.transferTo(output);
            }
        }

        List<String> violations = ReleaseApiHardener.auditJar(extractedClasses);
        if (!violations.isEmpty()) {
            throw new IllegalStateException(
                    "Packaged release JVM API is unsafe:\n" + String.join("\n", violations)
            );
        }

        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException(
                    "A JDK compiler is required to verify the release Java surface"
            );
        }
        String classpath = extractedClasses.getAbsolutePath() + File.pathSeparator +
                getCompilerClasspath().getAsPath();

        CompileResult positive = compile(
                compiler,
                classpath,
                "PublicApiProbe",
                "import com.utexo.rgb.sdk.UTEXOWallet;\n" +
                        "import com.utexo.rgb.sdk.RgbLightningNodeGatewayBackend;\n" +
                        "final class PublicApiProbe {\n" +
                        "  UTEXOWallet wallet;\n" +
                        "  RgbLightningNodeGatewayBackend backend;\n" +
                        "}\n"
        );
        requireResult(
                positive.exitCode == 0,
                "The supported Java surface did not compile:\n" + positive.output
        );

        CompileResult rawFfi = compile(
                compiler,
                classpath,
                "RawFfiProbe",
                "import com.utexo.rgb.sdk.internal.ffi.LocalSigner;\n" +
                        "import com.utexo.rgb.sdk.internal.ffi.SigningIntent;\n" +
                        "final class RawFfiProbe {\n" +
                        "  Object sign(LocalSigner signer, SigningIntent intent) {\n" +
                        "    return signer.signPsbt(\"AA==\", intent);\n" +
                        "  }\n" +
                        "}\n"
        );
        requireResult(rawFfi.exitCode != 0, "Raw UniFFI signing calls are callable from Java");

        CompileResult internalSigner = compile(
                compiler,
                classpath,
                "InternalSignerProbe",
                "import com.utexo.rgb.sdk.internal.LocalSigningEngine;\n" +
                        "final class InternalSignerProbe {\n" +
                        "  Object capabilities(LocalSigningEngine signer) {\n" +
                        "    return signer.getCapabilities();\n" +
                        "  }\n" +
                        "}\n"
        );
        requireResult(
                internalSigner.exitCode != 0,
                "Internal signing engine calls are callable from Java"
        );

        CompileResult rootInternal = compile(
                compiler,
                classpath,
                "RootInternalProbe",
                "import com.utexo.rgb.sdk.SeedStoreIdentity;\n" +
                        "import com.utexo.rgb.sdk.IdentifiersKt;\n" +
                        "final class RootInternalProbe {\n" +
                        "  Object identity() {\n" +
                        "    return new SeedStoreIdentity(\"app\", \"wallet\", \"signet\", \"policy\");\n" +
                        "  }\n" +
                        "  String unchecked(String value) {\n" +
                        "    return IdentifiersKt.requireProtocolIdentifier(\"field\", value);\n" +
                        "  }\n" +
                        "}\n"
        );
        requireResult(
                rootInternal.exitCode != 0,
                "Root-package Kotlin internals are callable from Java"
        );

        CompileResult bypass = compile(
                compiler,
                classpath,
                "GatewayBypassProbe",
                "import com.utexo.rgb.sdk.RgbLightningNodeGatewayBackend;\n" +
                        "import com.utexo.rgb.sdk.RgbLightningNodeGatewayConfiguration;\n" +
                        "import okhttp3.OkHttpClient;\n" +
                        "import kotlin.jvm.functions.Function0;\n" +
                        "final class GatewayBypassProbe {\n" +
                        "  Object open(RgbLightningNodeGatewayConfiguration config,\n" +
                        "              OkHttpClient client, Function0<Long> clock) {\n" +
                        "    return new RgbLightningNodeGatewayBackend(config, client, clock);\n" +
                        "  }\n" +
                        "}\n"
        );
        requireResult(
                bypass.exitCode != 0,
                "Gateway transport-policy bypass constructor is callable"
        );

        CompileResult secretAccessor = compile(
                compiler,
                classpath,
                "GatewaySecretProbe",
                "import com.utexo.rgb.sdk.RgbLightningNodeGatewayConfiguration;\n" +
                        "final class GatewaySecretProbe {\n" +
                        "  String read(RgbLightningNodeGatewayConfiguration config) {\n" +
                        "    return config.authorizationValue$sdk();\n" +
                        "  }\n" +
                        "}\n"
        );
        requireResult(
                secretAccessor.exitCode != 0,
                "Gateway bearer-token accessor is callable"
        );

        CompileResult recoveryBypass = compile(
                compiler,
                classpath,
                "GatewayRecoveryProbe",
                "import com.utexo.rgb.sdk.*;\n" +
                        "final class GatewayRecoveryProbe {\n" +
                        "  Object recover(RgbLightningNodeGatewayBackend backend, BackendContext context,\n" +
                        "                 OperationCheckpoint checkpoint, SendBtcRequest request) {\n" +
                        "    return backend.getOperationForRecovery$sdk(context, checkpoint, request, null);\n" +
                        "  }\n" +
                        "}\n"
        );
        requireResult(
                recoveryBypass.exitCode != 0,
                "Internal checkpoint-aware recovery is callable from Java"
        );
    }

    private void verifyRootInternalInventory() throws IOException {
        Pattern internalType = Pattern.compile(
                "(?m)^internal\\s+(?:(?:data|sealed|enum|open|abstract|value|fun)\\s+)*" +
                        "(?:class|interface|object)\\s+([A-Za-z_][A-Za-z0-9_]*)"
        );
        Pattern publicTopLevelFunction = Pattern.compile(
                "(?m)^public\\s+(?:suspend\\s+)?fun\\s+(?!interface\\b)"
        );
        Set<String> discovered = new HashSet<>();
        for (File source : getRootKotlinSources().getFiles()) {
            String text = Files.readString(source.toPath(), StandardCharsets.UTF_8);
            var matcher = internalType.matcher(text);
            while (matcher.find()) {
                discovered.add(matcher.group(1));
            }
            if (!source.getName().equals("KeyManagement.kt") &&
                    publicTopLevelFunction.matcher(text).find()) {
                throw new IllegalStateException(
                        "Public top-level functions must be routed through the RGBSDK facade: " +
                                source.getName()
                );
            }
        }
        Set<String> expected = ReleaseApiHardener.rootInternalSimpleNames();
        if (!discovered.equals(expected)) {
            Set<String> missingFromHardener = new HashSet<>(discovered);
            missingFromHardener.removeAll(expected);
            Set<String> staleInventory = new HashSet<>(expected);
            staleInventory.removeAll(discovered);
            throw new IllegalStateException(
                    "Root Kotlin internal inventory drifted; unprotected=" + missingFromHardener +
                            " stale=" + staleInventory
            );
        }
    }

    private CompileResult compile(
            javax.tools.JavaCompiler compiler,
            String classpath,
            String className,
            String source
    ) throws IOException {
        File sourceFile = new File(getTemporaryDir(), className + ".java");
        Files.writeString(sourceFile.toPath(), source, StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int exitCode = compiler.run(
                null,
                output,
                output,
                "-proc:none",
                "-classpath",
                classpath,
                "-d",
                getTemporaryDir().getAbsolutePath(),
                sourceFile.getAbsolutePath()
        );
        return new CompileResult(exitCode, output.toString(StandardCharsets.UTF_8));
    }

    private static void requireResult(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static final class CompileResult {
        private final int exitCode;
        private final String output;

        private CompileResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }
}
