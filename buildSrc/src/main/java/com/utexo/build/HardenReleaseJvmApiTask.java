package com.utexo.build;

import java.io.File;
import java.io.IOException;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** Mutates AGP's release classes.jar immediately before AAR packaging. */
@DisableCachingByDefault(because = "Transforms an AGP intermediate in place")
public abstract class HardenReleaseJvmApiTask extends DefaultTask {
    @Inject
    public HardenReleaseJvmApiTask() {}

    @Internal
    public abstract RegularFileProperty getClassesJar();

    @TaskAction
    public final void harden() throws IOException {
        File classesJar = getClassesJar().get().getAsFile();
        if (!classesJar.isFile()) {
            throw new IllegalStateException("Release classes.jar was not produced");
        }
        ReleaseApiHardener.hardenJar(classesJar);
        var violations = ReleaseApiHardener.auditJar(classesJar);
        if (!violations.isEmpty()) {
            throw new IllegalStateException(
                    "Release JVM API hardening failed:\n" + String.join("\n", violations)
            );
        }
    }
}
