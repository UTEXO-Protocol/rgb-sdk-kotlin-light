package com.utexo.build;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipFile;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.TypePath;

/** Deterministic JVM ABI and conservative Kotlin metadata guard, complemented by consumer compilation. */
@DisableCachingByDefault(because = "Checks a reviewed API baseline against the packaged release artifact")
public abstract class PublicApiBaselineTask extends DefaultTask {
    @Inject public PublicApiBaselineTask() {}
    @InputFile @PathSensitive(PathSensitivity.NONE) public abstract RegularFileProperty getAar();
    @Internal public abstract RegularFileProperty getBaseline();
    @OutputFile public abstract RegularFileProperty getActual();
    @Input public abstract Property<Boolean> getUpdate();

    @TaskAction public void verify() throws IOException {
        Set<String> declarations = new TreeSet<>();
        var temporary = getTemporaryDir().toPath().resolve("classes.jar");
        try (ZipFile aar = new ZipFile(getAar().get().getAsFile())) {
            try (var input = aar.getInputStream(aar.getEntry("classes.jar"))) {
                Files.copy(input, temporary, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
        try (ZipFile classes = new ZipFile(temporary.toFile())) {
            var entries = classes.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (!entry.getName().startsWith("com/utexo/rgb/sdk/") || !entry.getName().endsWith(".class")) continue;
                try (var input = classes.getInputStream(entry)) {
                    new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                        private String name;
                        private boolean exposed;
                        @Override public void visit(int version, int access, String className, String signature, String superName, String[] interfaces) {
                            name = className;
                            exposed = visible(access) && (access & Opcodes.ACC_SYNTHETIC) == 0;
                            if (exposed) declarations.add("class " + name + " " + flags(access) + " extends " + superName + " implements " + String.join(",", interfaces) + generic(signature));
                        }
                        @Override public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                            // Debug source mappings change with line numbers, not the consumer contract.
                            if (descriptor.equals("Lkotlin/jvm/internal/SourceDebugExtension;")) return null;
                            return exposed ? annotation(declarations, name + " annotation " + descriptor + " runtime=" + visible) : null;
                        }
                        @Override public FieldVisitor visitField(int access, String field, String descriptor, String signature, Object value) {
                            if (!exposed || !visible(access) || (access & Opcodes.ACC_SYNTHETIC) != 0) return null;
                            String member = name + "." + field + " " + descriptor;
                            declarations.add("  " + member + " " + flags(access) + generic(signature) + (value == null ? "" : " = " + value));
                            return new FieldVisitor(Opcodes.ASM9) {
                                @Override public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                                    return annotation(declarations, member + " annotation " + descriptor + " runtime=" + visible);
                                }
                                @Override public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath path, String descriptor, boolean visible) {
                                    return annotation(declarations, member + " type-annotation " + typeRef + ":" + path + " " + descriptor + " runtime=" + visible);
                                }
                            };
                        }
                        @Override public MethodVisitor visitMethod(int access, String method, String descriptor, String signature, String[] exceptions) {
                            // Default argument bridges are part of Kotlin's binary call contract.
                            // Internal mangled methods and compiler access bridges are intentionally hidden.
                            if (!exposed || !visible(access) || method.contains("$sdk") || method.startsWith("access$") || descriptor.contains("Lcom/utexo/rgb/sdk/internal/")) return null;
                            String member = name + "." + method + descriptor;
                            declarations.add("  " + member + " " + flags(access) + generic(signature) + (exceptions == null ? "" : " throws=" + String.join(",", exceptions)));
                            return new MethodVisitor(Opcodes.ASM9) {
                                @Override public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                                    return annotation(declarations, member + " annotation " + descriptor + " runtime=" + visible);
                                }
                                @Override public AnnotationVisitor visitParameterAnnotation(int parameter, String descriptor, boolean visible) {
                                    return annotation(declarations, member + " parameter=" + parameter + " annotation " + descriptor + " runtime=" + visible);
                                }
                                @Override public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath path, String descriptor, boolean visible) {
                                    return annotation(declarations, member + " type-annotation " + typeRef + ":" + path + " " + descriptor + " runtime=" + visible);
                                }
                            };
                        }
                    }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                }
            }
        }
        String actual = "# Reviewed packaged JVM ABI, annotations and Kotlin metadata (including named parameters/nullability).\n" +
                "# Metadata is intentionally conservative: private Kotlin declaration changes can require review.\n" +
                String.join("\n", declarations) + "\n";
        Files.createDirectories(getActual().get().getAsFile().toPath().getParent());
        Files.writeString(getActual().get().getAsFile().toPath(), actual, StandardCharsets.UTF_8);
        var baseline = getBaseline().get().getAsFile().toPath();
        if (getUpdate().get()) {
            Files.createDirectories(baseline.getParent());
            Files.writeString(baseline, actual, StandardCharsets.UTF_8);
        } else if (!Files.exists(baseline) || !Files.readString(baseline, StandardCharsets.UTF_8).equals(actual)) {
            throw new IllegalStateException("Public JVM ABI or Kotlin source metadata changed. Review build/api/release-api.txt against api/release-api.txt; update intentionally with :sdk:updateReleaseApiBaseline.");
        }
    }
    /** Values are encoded rather than escaped so metadata control characters cannot split baseline lines. */
    private static AnnotationVisitor annotation(Set<String> declarations, String prefix) {
        declarations.add("  " + prefix);
        return new AnnotationVisitor(Opcodes.ASM9) {
            private int index;
            private String element(String name) { return prefix + "/" + (name == null ? "[" + index++ + "]" : name); }
            @Override public void visit(String name, Object value) {
                declarations.add("  " + element(name) + "=" + annotationValue(value));
            }
            @Override public void visitEnum(String name, String descriptor, String value) {
                declarations.add("  " + element(name) + "=enum:" + descriptor + ":" + value);
            }
            @Override public AnnotationVisitor visitAnnotation(String name, String descriptor) {
                return annotation(declarations, element(name) + ":" + descriptor);
            }
            @Override public AnnotationVisitor visitArray(String name) {
                return annotation(declarations, element(name) + "[]");
            }
        };
    }
    private static String annotationValue(Object value) {
        if (value instanceof String text) return "utf8-base64:" + Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
        if (value != null && value.getClass().isArray()) {
            StringBuilder result = new StringBuilder(value.getClass().getComponentType().getName()).append('[');
            for (int index = 0; index < java.lang.reflect.Array.getLength(value); index++) {
                if (index > 0) result.append(',');
                result.append(annotationValue(java.lang.reflect.Array.get(value, index)));
            }
            return result.append(']').toString();
        }
        return value == null ? "null" : value.getClass().getName() + ":" + value;
    }
    private static boolean visible(int access) { return (access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0; }
    private static String generic(String signature) { return signature == null ? "" : " generic=" + signature; }
    private static String flags(int access) { return Integer.toHexString(access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE | Opcodes.ACC_ANNOTATION | Opcodes.ACC_ENUM | Opcodes.ACC_VARARGS)); }
}
