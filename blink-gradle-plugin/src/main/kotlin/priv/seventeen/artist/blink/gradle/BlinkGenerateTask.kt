/*
 * Copyright 2026 17Artist
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package priv.seventeen.artist.blink.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File

abstract class BlinkGenerateTask : DefaultTask() {

    @get:InputDirectory
    abstract val classesDir: DirectoryProperty

    @get:Input abstract val pluginName: Property<String>
    @get:Input abstract val pluginVersion: Property<String>
    @get:Input abstract val pluginDescription: Property<String>
    @get:Input abstract val pluginAuthors: ListProperty<String>
    @get:Input abstract val apiVersion: Property<String>
    @get:Input abstract val depend: ListProperty<String>
    @get:Input abstract val softDepend: ListProperty<String>
    @get:Input abstract val libraries: ListProperty<String>
    @get:Input abstract val enableScript: Property<Boolean>
    @get:Input abstract val enableAria: Property<Boolean>
    @get:Input abstract val foliaSupported: Property<Boolean>
    @get:Input abstract val packageName: Property<String>

    @TaskAction
    fun generate() {
        val classesRoot = classesDir.get().asFile
        if (!classesRoot.exists()) {
            logger.warn("[Blink] 编译产物目录不存在: $classesRoot")
            return
        }

        val pkg = packageName.get().ifEmpty { inferPackageName(classesRoot) }
        if (pkg.isEmpty()) {
            logger.error("[Blink] 无法推断包名，请在 blink { packageName = \"...\" } 中指定")
            return
        }
        logger.lifecycle("[Blink] 包名: $pkg")

        val scanner = AnnotationScanner()
        scanner.scan(classesRoot)
        logger.lifecycle("[Blink] 扫描到 ${scanner.awakeEntries.size} 个 @Awake, ${scanner.listenerEntries.size} 个 @AutoListener")

        val internalPkg = pkg.replace('.', '/')
        val generator = BytecodeGenerator(internalPkg)

        writeClass(classesRoot, "$internalPkg/BlinkGeneratedLifeCycle.class",
            generator.generateLifeCycleClass(scanner.awakeEntries))
        writeClass(classesRoot, "$internalPkg/BlinkGeneratedEvents.class",
            generator.generateEventsClass(scanner.listenerEntries))
        writeClass(classesRoot, "$internalPkg/BlinkGeneratedMain.class",
            generator.generateMainClass(enableScript.get(), enableAria.get()))

        generatePluginYml(classesRoot, pkg)
        writeScanResult(classesRoot, scanner)

        logger.lifecycle("[Blink] 代码生成完成")
    }

    private fun writeScanResult(classesRoot: File, scanner: AnnotationScanner) {
        val buildDir = classesRoot.parentFile.parentFile.parentFile
        val resultFile = File(buildDir, "blink-scan-result.txt")
        val sb = StringBuilder()
        for (entry in scanner.awakeEntries) {
            sb.appendLine("${entry.ownerInternal.replace('/', '.')}#${entry.methodName}")
        }
        for (entry in scanner.listenerEntries) {
            sb.appendLine("${entry.ownerInternal.replace('/', '.')}#${entry.methodName}")
        }
        resultFile.writeText(sb.toString(), Charsets.UTF_8)
    }

    private fun inferPackageName(classesRoot: File): String {
        val firstClass = classesRoot.walk()
            .filter { it.isFile && it.extension == "class" }
            .firstOrNull() ?: return ""
        val relative = classesRoot.toPath().relativize(firstClass.toPath()).toString()
        val parts = relative.replace('\\', '/').split('/')
        return if (parts.size > 1) parts.dropLast(1).joinToString(".") else ""
    }

    private fun writeClass(classesRoot: File, relativePath: String, bytes: ByteArray) {
        val file = File(classesRoot, relativePath)
        file.parentFile.mkdirs()
        file.writeBytes(bytes)
    }

    private fun generatePluginYml(classesRoot: File, pkg: String) {
        val buildDir = classesRoot.parentFile.parentFile.parentFile
        val resourcesDir = File(buildDir, "resources/main")
        if (!resourcesDir.exists()) resourcesDir.mkdirs()

        val yml = buildPluginYml(pkg)
        File(resourcesDir, "plugin.yml").writeText(yml, Charsets.UTF_8)
        File(classesRoot, "plugin.yml").writeText(yml, Charsets.UTF_8)
    }

    private fun buildPluginYml(pkg: String): String {
        val sb = StringBuilder()
        val name = pluginName.get().ifEmpty { project.name }
        val version = pluginVersion.get()

        sb.appendLine("name: $name")
        sb.appendLine("version: $version")
        sb.appendLine("main: $pkg.BlinkGeneratedMain")
        sb.appendLine("api-version: '${apiVersion.get()}'")

        val desc = pluginDescription.get()
        if (desc.isNotEmpty()) sb.appendLine("description: '$desc'")

        val authors = pluginAuthors.get()
        if (authors.isNotEmpty()) {
            sb.appendLine("authors: [${authors.joinToString(", ") { "'$it'" }}]")
        }

        val deps = depend.get()
        if (deps.isNotEmpty()) {
            sb.appendLine("depend: [${deps.joinToString(", ") { "'$it'" }}]")
        }

        val softDeps = softDepend.get()
        if (softDeps.isNotEmpty()) {
            sb.appendLine("softdepend: [${softDeps.joinToString(", ") { "'$it'" }}]")
        }

        if (foliaSupported.get()) {
            sb.appendLine("folia-supported: true")
        }

        val libs = libraries.get()
        if (libs.isNotEmpty()) {
            sb.appendLine("blink-libraries:")
            libs.forEach { sb.appendLine("  - '$it'") }
        }

        return sb.toString()
    }
}
