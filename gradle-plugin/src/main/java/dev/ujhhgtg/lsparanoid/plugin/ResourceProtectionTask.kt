package dev.ujhhgtg.lsparanoid.plugin

import dev.ujhhgtg.lsparanoid.processor.nativebackend.*
import dev.ujhhgtg.lsparanoid.processor.resources.ResourceProtector
import dev.ujhhgtg.lsparanoid.processor.resources.ResourceSourceDirectory
import org.gradle.api.DefaultTask
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.*

@CacheableTask
abstract class ResourceProtectionTask : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val sourceDirectories: ListProperty<Directory>
    @get:Input abstract val priorities: ListProperty<Int>
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val manifests: ListProperty<RegularFile>
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val entropy: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val aapt2: RegularFileProperty
    @get:Input abstract val moduleIdentity: Property<String>
    @get:Input abstract val includes: SetProperty<String>
    @get:Input abstract val excludes: SetProperty<String>
    @get:OutputDirectory abstract val overlay: DirectoryProperty
    @get:OutputFile abstract val records: RegularFileProperty
    @get:OutputFile abstract val report: RegularFileProperty
    @get:LocalState abstract val workspace: DirectoryProperty

    @TaskAction fun protect() {
        overlay.get().asFile.apply { deleteRecursively(); mkdirs() }
        workspace.get().asFile.apply { deleteRecursively(); mkdirs() }
        val spec = NativeBuildSpec.create(entropy.get().asFile.readBytes(), moduleIdentity.get())
        val registry = NativeStringRegistry(spec, RecordDomain.RESOURCE)
        ResourceProtector.protect(
            sourceDirectories = sourceDirectories.get().zip(priorities.get()).map { (directory, priority) ->
                ResourceSourceDirectory(directory.asFile.toPath(), priority)
            },
            manifests = manifests.get().map { it.asFile.toPath() },
            includes = includes.get(), excludes = excludes.get(), namespace = spec.namespace,
            overlayDirectory = overlay.get().asFile.toPath(), reportFile = report.get().asFile.toPath(),
            aapt2 = aapt2.get().asFile.toPath(), workspaceDirectory = workspace.get().asFile.toPath(),
            register = registry::registerString,
        )
        NativeRecordIO.write(records.get().asFile, spec, registry.records())
    }
}
