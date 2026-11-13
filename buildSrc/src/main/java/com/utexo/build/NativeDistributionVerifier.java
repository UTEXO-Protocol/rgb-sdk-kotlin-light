package com.utexo.build;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;

/** Pure verifier, also exercised against isolated fixtures without a Gradle daemon. */
public final class NativeDistributionVerifier {
    private NativeDistributionVerifier() { }

    public static final Set<String> REQUIRED = Set.of(
        "NativeSource/THIRD_PARTY_RUST_NOTICES.md", "NativeSource/advisories.json",
        "NativeSource/artifact-manifest.json", "NativeSource/dependencies.json",
        "NativeSource/license-review.json", "NativeSource/native-build.json",
        "NativeSource/native-source.cdx.json", "NativeSource/utexo-local-signer-source.tar.gz",
        "sdk/src/main/jniLibs/arm64-v8a/libutexo_local_signer.so",
        "sdk/src/main/jniLibs/armeabi-v7a/libutexo_local_signer.so",
        "sdk/src/main/jniLibs/x86_64/libutexo_local_signer.so",
        "sdk/src/main/kotlin/com/utexo/rgb/sdk/internal/ffi/utexo_local_signer.kt",
        "NativeSource/utexo-rgb-verifier-source.tar.gz",
        "sdk/src/main/jniLibs/arm64-v8a/libutexo_rgb_verifier.so",
        "sdk/src/main/jniLibs/armeabi-v7a/libutexo_rgb_verifier.so",
        "sdk/src/main/jniLibs/x86_64/libutexo_rgb_verifier.so",
        "sdk/src/main/kotlin/com/utexo/rgb/sdk/internal/ffi/verifier/utexo_rgb_verifier.kt"
    );

    public static void verify(Path repository, Path checksumFile, Set<Path> actualInputs)
            throws IOException, NoSuchAlgorithmException {
        Path root = repository.toAbsolutePath().normalize();
        Path checksums = checksumFile.toAbsolutePath().normalize();
        requireRegular(root, checksums);
        Set<Path> expected = new HashSet<>();
        for (Path input : actualInputs) {
            Path target = input.toAbsolutePath().normalize();
            requireRegular(root, target);
            if (!target.equals(checksums)) expected.add(target);
        }
        for (String required : REQUIRED) {
            if (!expected.contains(root.resolve(required))) throw new IOException("Missing required native input: " + required);
        }
        Set<Path> declared = new HashSet<>();
        for (String line : Files.readAllLines(checksums)) {
            if (line.isBlank()) continue;
            String[] fields = line.trim().split("\\s+", 2);
            if (fields.length != 2 || !fields[0].matches("[0-9a-f]{64}")) throw new IOException("Malformed native checksum inventory");
            String relative = fields[1].startsWith("*") ? fields[1].substring(1) : fields[1];
            if (!relative.matches("[A-Za-z0-9_./-]+") || relative.startsWith("/")) throw new IOException("Invalid native inventory path");
            for (String part : relative.split("/", -1)) {
                if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new IOException("Noncanonical native inventory path");
            }
            Path target = root.resolve(relative);
            requireRegular(root, target);
            if (!expected.contains(target) || !declared.add(target)) throw new IOException("Unexpected or duplicate native inventory path");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(target)) {
                byte[] buffer = new byte[65536];
                for (int count; (count = input.read(buffer)) != -1;) digest.update(buffer, 0, count);
            }
            if (!HexFormat.of().formatHex(digest.digest()).equals(fields[0])) throw new IOException("Native distribution input checksum mismatch: " + relative);
        }
        if (!declared.equals(expected)) throw new IOException("Native checksum inventory does not cover the complete input set");
    }

    private static void requireRegular(Path root, Path file) throws IOException {
        if (!file.startsWith(root) || file.equals(root) || Files.isSymbolicLink(root)) throw new IOException("Invalid native input root/path");
        Path current = root;
        for (Path part : root.relativize(file)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IOException("Native input symlinks are forbidden");
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Native input must be a regular file");
    }
}
