package com.utexo.build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Release-only JVM surface hardening for Kotlin-internal implementation classes. */
public final class ReleaseApiHardener {
    private static final String SDK_PREFIX = "com/utexo/rgb/sdk/";
    private static final String INTERNAL_PREFIX = SDK_PREFIX + "internal/";
    private static final String GATEWAY_CONFIGURATION =
            SDK_PREFIX + "RgbLightningNodeGatewayConfiguration";
    private static final String GATEWAY_BACKEND =
            SDK_PREFIX + "RgbLightningNodeGatewayBackend";
    private static final String EXPECTED_GATEWAY_CONSTRUCTOR =
            "(Lcom/utexo/rgb/sdk/RgbLightningNodeGatewayConfiguration;)V";
    private static final Set<String> ROOT_INTERNAL_SIMPLE_NAMES = Set.of(
            "SeedStoreIdentity",
            "EncryptedSeed",
            "SeedEnvelopeCodec",
            "SeedEnvelopeCipher",
            "SeedEnvelopeRepository",
            "AtomicSeedEnvelopeFile",
            "AndroidKeystoreSeedCipher",
            "ProtectedStorageCipher",
            "AndroidProtectedStorageCipher",
            "ProtectedWalletFile",
            "EncryptedOperationJournal"
    );

    private ReleaseApiHardener() {}

    public static Set<String> rootInternalSimpleNames() {
        return ROOT_INTERNAL_SIMPLE_NAMES;
    }

    /** Rewrites a jar atomically. The transform is idempotent. */
    public static void hardenJar(File jar) throws IOException {
        File parent = jar.getAbsoluteFile().getParentFile();
        File temporary = File.createTempFile("classes-hardened-", ".jar", parent);
        boolean completed = false;
        try (ZipFile input = new ZipFile(jar);
             ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(temporary.toPath()))) {
            Enumeration<? extends ZipEntry> entries = input.entries();
            while (entries.hasMoreElements()) {
                ZipEntry original = entries.nextElement();
                ZipEntry rewritten = new ZipEntry(original.getName());
                rewritten.setTime(original.getTime());
                output.putNextEntry(rewritten);
                if (!original.isDirectory()) {
                    byte[] bytes;
                    try (InputStream stream = input.getInputStream(original)) {
                        bytes = readAll(stream);
                    }
                    if (original.getName().endsWith(".class")) {
                        bytes = hardenClass(bytes);
                    }
                    output.write(bytes);
                }
                output.closeEntry();
            }
            completed = true;
        } finally {
            if (!completed) {
                Files.deleteIfExists(temporary.toPath());
            }
        }
        try {
            Files.move(
                    temporary.toPath(),
                    jar.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (IOException unsupportedAtomicMove) {
            Files.move(
                    temporary.toPath(),
                    jar.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
            );
        }
    }

    /** Returns release API violations; an empty list is the required result. */
    public static List<String> auditJar(File jar) throws IOException {
        List<String> violations = new ArrayList<>();
        try (ZipFile input = new ZipFile(jar)) {
            Enumeration<? extends ZipEntry> entries = input.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                    continue;
                }
                byte[] bytes;
                try (InputStream stream = input.getInputStream(entry)) {
                    bytes = readAll(stream);
                }
                auditClass(bytes, violations);
            }
        }
        return violations;
    }

    private static byte[] hardenClass(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, 0);
        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
            private String className;
            private boolean implementationClass;

            @Override
            public void visit(
                    int version,
                    int access,
                    String name,
                    String signature,
                    String superName,
                    String[] interfaces
            ) {
                className = name;
                implementationClass = isImplementationClass(name);
                super.visit(
                        version,
                        implementationClass ? access | Opcodes.ACC_SYNTHETIC : access,
                        name,
                        signature,
                        superName,
                        interfaces
                );
            }

            @Override
            public FieldVisitor visitField(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    Object value
            ) {
                int hardened = shouldHideMember(implementationClass, name, descriptor)
                        ? access | Opcodes.ACC_SYNTHETIC
                        : access;
                return super.visitField(hardened, name, descriptor, signature, value);
            }

            @Override
            public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    String[] exceptions
            ) {
                int hardened = shouldHideMember(implementationClass, name, descriptor)
                        ? access | Opcodes.ACC_SYNTHETIC
                        : access;
                if (name.startsWith("access$") ||
                        (className.equals(GATEWAY_CONFIGURATION) && name.contains("$sdk"))) {
                    hardened &= ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE);
                }
                return super.visitMethod(hardened, name, descriptor, signature, exceptions);
            }

            @Override
            public void visitInnerClass(
                    String name,
                    String outerName,
                    String innerName,
                    int access
            ) {
                super.visitInnerClass(
                        name,
                        outerName,
                        innerName,
                        isImplementationClass(name) ? access | Opcodes.ACC_SYNTHETIC : access
                );
            }
        };
        reader.accept(visitor, 0);
        return writer.toByteArray();
    }

    private static void auditClass(byte[] bytes, List<String> violations) {
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            private String className;
            private boolean implementationClass;
            private int publicGatewayConstructors;

            @Override
            public void visit(
                    int version,
                    int access,
                    String name,
                    String signature,
                    String superName,
                    String[] interfaces
            ) {
                className = name;
                implementationClass = isImplementationClass(name);
                if (implementationClass && !isSynthetic(access)) {
                    violations.add(name + " is an externally discoverable implementation class");
                }
            }

            @Override
            public FieldVisitor visitField(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    Object value
            ) {
                if (implementationClass && !isSynthetic(access)) {
                    violations.add(className + "." + name + " is a non-synthetic implementation field");
                }
                if (className.equals(GATEWAY_CONFIGURATION) && name.equals("token") &&
                        (access & Opcodes.ACC_PRIVATE) == 0) {
                    violations.add("gateway bearer token is not stored in a private field");
                }
                return null;
            }

            @Override
            public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    String[] exceptions
            ) {
                if (implementationClass && !isSynthetic(access)) {
                    violations.add(className + "." + name + descriptor +
                            " is a non-synthetic implementation method");
                }
                if ((name.contains("$sdk") || descriptor.contains(INTERNAL_PREFIX)) &&
                        !isSynthetic(access)) {
                    violations.add(className + "." + name + descriptor +
                            " exposes a Kotlin-internal JVM member");
                }
                if (name.startsWith("access$") && (access & Opcodes.ACC_PUBLIC) != 0 &&
                        !implementationClass) {
                    violations.add(className + "." + name +
                            " exposes a compiler access bridge");
                }
                if (className.equals(GATEWAY_BACKEND) && name.equals("<init>") &&
                        (access & Opcodes.ACC_PUBLIC) != 0) {
                    publicGatewayConstructors++;
                    if (!descriptor.equals(EXPECTED_GATEWAY_CONSTRUCTOR)) {
                        violations.add("gateway exposes a transport-policy bypass constructor " + descriptor);
                    }
                }
                if (className.equals(GATEWAY_CONFIGURATION) &&
                        (access & Opcodes.ACC_PUBLIC) != 0 &&
                        (name.toLowerCase().contains("token") ||
                         name.toLowerCase().contains("authorizationvalue") ||
                         name.toLowerCase().contains("resolvedbaseurl"))) {
                    violations.add("gateway configuration exposes secret transport state through " + name);
                }
                return null;
            }

            @Override
            public void visitEnd() {
                if (className.equals(GATEWAY_BACKEND) && publicGatewayConstructors != 1) {
                    violations.add("gateway must expose exactly one public constructor, found " +
                            publicGatewayConstructors);
                }
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }

    private static boolean shouldHideMember(
            boolean implementationClass,
            String name,
            String descriptor
    ) {
        return implementationClass || name.contains("$sdk") || descriptor.contains(INTERNAL_PREFIX);
    }

    private static boolean isImplementationClass(String name) {
        if (name.startsWith(INTERNAL_PREFIX)) {
            return true;
        }
        if (!name.startsWith(SDK_PREFIX)) {
            return false;
        }
        String outerName = name.substring(0, name.indexOf('$') >= 0 ? name.indexOf('$') : name.length());
        if (ROOT_INTERNAL_SIMPLE_NAMES.contains(outerName.substring(SDK_PREFIX.length()))) {
            return true;
        }
        // Explicit API mode guarantees that every intended public top-level
        // function is declared `public`. This SDK deliberately collects those
        // functions in the @JvmName("RGBSDK") facade; ordinary *Kt facades
        // therefore contain implementation helpers only.
        return outerName.endsWith("Kt") && !outerName.equals(SDK_PREFIX + "RGBSDK");
    }

    private static boolean isSynthetic(int access) {
        return (access & Opcodes.ACC_SYNTHETIC) != 0;
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }
}
