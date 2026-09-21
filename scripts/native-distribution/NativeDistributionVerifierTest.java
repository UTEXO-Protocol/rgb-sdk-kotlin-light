package com.utexo.build;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** Isolated byte fixtures; never edits the repository or its evidence files. */
public final class NativeDistributionVerifierTest {
    public static void main(String[] args) throws Exception {
        List<String> scenarios = List.of("valid", "missing-entry", "tampered-input", "duplicate-entry",
            "empty-inventory", "absolute-path", "traversal", "file-symlink", "directory-symlink",
            "missing-required-file", "extra-unlisted-input", "unrelated-entry");
        for (String scenario : scenarios) {
            Path root = Files.createTempDirectory("utexo-native-checksums-");
            try {
                Set<Path> actual = new HashSet<>();
                List<String> lines = new ArrayList<>();
                for (String name : NativeDistributionVerifier.REQUIRED.stream().sorted().toList()) {
                    Path path = root.resolve(name);
                    Files.createDirectories(path.getParent());
                    Files.writeString(path, "public synthetic fixture " + name);
                    actual.add(path);
                    String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
                    lines.add(digest + "  " + name);
                }
                Path selected = root.resolve("NativeSource/license-review.json");
                switch (scenario) {
                    case "missing-entry" -> lines.remove(0);
                    case "tampered-input" -> Files.writeString(selected, "changed");
                    case "duplicate-entry" -> lines.add(lines.get(0));
                    case "empty-inventory" -> lines.clear();
                    case "absolute-path" -> lines.set(0, lines.get(0).substring(0, 66) + root.resolve(lines.get(0).substring(66)));
                    case "traversal" -> lines.set(0, lines.get(0).replace("NativeSource/", "NativeSource/../NativeSource/"));
                    case "file-symlink" -> {
                        Path backing = root.resolve("backing");
                        Files.move(selected, backing);
                        Files.createSymbolicLink(selected, backing);
                    }
                    case "directory-symlink" -> {
                        Path backing = root.resolve("backing");
                        Files.move(root.resolve("NativeSource"), backing);
                        Files.createSymbolicLink(root.resolve("NativeSource"), backing);
                    }
                    case "missing-required-file" -> {
                        actual.remove(selected);
                        Files.delete(selected);
                        lines.removeIf(line -> line.endsWith("NativeSource/license-review.json"));
                    }
                    case "extra-unlisted-input" -> {
                        Path extra = root.resolve("NativeSource/extra.json");
                        Files.writeString(extra, "extra"); actual.add(extra);
                    }
                    case "unrelated-entry" -> {
                        Path extra = root.resolve("unrelated.txt");
                        Files.writeString(extra, "extra");
                        lines.add(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(extra))) + "  unrelated.txt");
                    }
                    default -> { }
                }
                Path checksums = root.resolve("NativeSource/SHA256SUMS");
                Files.write(checksums, lines);
                actual.add(checksums);
                boolean rejected = false;
                try { NativeDistributionVerifier.verify(root, checksums, actual); }
                catch (IOException expected) { rejected = true; }
                if (rejected == scenario.equals("valid")) throw new AssertionError("Incorrect result: " + scenario);
                System.out.println("PASS native-distribution: " + scenario);
            } finally {
                // Only this freshly generated fixture directory; walk never follows symlinks.
                try (var paths = Files.walk(root)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
                }
            }
        }
    }
}
