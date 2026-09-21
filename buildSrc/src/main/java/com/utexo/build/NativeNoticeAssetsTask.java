package com.utexo.build;

import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/** Builds the generated asset directory without mutating source or native evidence files. */
public abstract class NativeNoticeAssetsTask extends DefaultTask {
    @InputFiles
    @PathSensitive(PathSensitivity.NAME_ONLY)
    public abstract ConfigurableFileCollection getNoticeFiles();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDirectory();

    @Inject
    protected abstract FileSystemOperations getFileSystemOperations();

    @TaskAction
    public void stage() {
        getFileSystemOperations().sync(spec -> {
            spec.into(getOutputDirectory());
            spec.from(getNoticeFiles(), nested -> nested.into("utexo-native"));
        });
    }
}
