package com.utexo.build;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.io.File;
import java.util.stream.Collectors;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/** Rejects mismatching declared native inputs before any package or publication is produced. */
public abstract class VerifyNativeDistributionInputsTask extends DefaultTask {
    @Internal
    public abstract DirectoryProperty getRepositoryDirectory();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getChecksums();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getNativeInputs();

    @TaskAction
    public void verify() throws IOException, NoSuchAlgorithmException {
        NativeDistributionVerifier.verify(
            getRepositoryDirectory().get().getAsFile().toPath(),
            getChecksums().get().getAsFile().toPath(),
            getNativeInputs().getFiles().stream().map(File::toPath).collect(Collectors.toSet())
        );
    }
}
