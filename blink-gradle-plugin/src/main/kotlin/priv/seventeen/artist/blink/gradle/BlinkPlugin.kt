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

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar
import java.io.File

class BlinkPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create("blink", BlinkExtension::class.java)

        project.afterEvaluate {
            if (!project.plugins.hasPlugin("com.github.johnrengelman.shadow")) {
                project.plugins.apply("com.github.johnrengelman.shadow")
            }

            // Kotlin stdlib 由 KotlinBootstrap 运行时动态加载，不打包进 JAR
            project.configurations.findByName("runtimeClasspath")?.let { config ->
                config.exclude(mapOf("group" to "org.jetbrains.kotlin"))
                config.exclude(mapOf("group" to "org.jetbrains", "module" to "annotations"))
            }

            if (extension.enableAria.get()) {
                configureAria(project)
            }

            configureCodeGeneration(project, extension)
            configureShadow(project, extension)

            if (extension.obfuscate.get()) {
                configureProteus(project, extension)
            }
        }
    }

    private fun configureAria(project: Project) {
        val ariaVersion = project.findProperty("ariaVersion")?.toString() ?: "1.0.1"
        val depNotation = "priv.seventeen.artist.aria:aria:$ariaVersion"
        // compileOnly: 编译时可用，运行时由 DependencyLoader 自动下载注入
        val compileOnly = project.configurations.findByName("compileOnly")
        if (compileOnly != null) {
            project.dependencies.add("compileOnly", depNotation)
            project.logger.lifecycle("[Blink] Aria 脚本引擎已添加 (compileOnly): $depNotation")
        } else {
            project.logger.warn("[Blink] 未找到 compileOnly 配置，无法添加 Aria 依赖")
        }
    }

    private fun configureCodeGeneration(project: Project, extension: BlinkExtension) {
        val generateTask = project.tasks.register("blinkGenerate", BlinkGenerateTask::class.java) { task ->
            task.group = "blink"
            task.description = "Scan annotations and generate Blink entry classes"

            val kotlinCompile = project.tasks.findByName("compileKotlin")
            val javaCompile = project.tasks.findByName("compileJava")
            val classesDirectory = project.layout.buildDirectory.dir("classes/kotlin/main")

            if (kotlinCompile != null) {
                task.dependsOn(kotlinCompile)
                task.classesDir.set(classesDirectory)
            } else if (javaCompile != null) {
                task.dependsOn(javaCompile)
                task.classesDir.set((javaCompile as JavaCompile).destinationDirectory)
            }

            task.pluginName.set(extension.name)
            task.pluginVersion.set(extension.version)
            task.pluginDescription.set(extension.description)
            task.pluginAuthors.set(extension.authors)
            task.apiVersion.set(extension.apiVersion)
            task.depend.set(extension.depend)
            task.softDepend.set(extension.softDepend)
            task.libraries.set(extension.libraries)
            task.enableScript.set(extension.enableScript)
            task.enableAria.set(extension.enableAria)
            task.foliaSupported.set(extension.foliaSupported)
            task.packageName.set(extension.packageName)
        }

        project.tasks.withType(Jar::class.java).configureEach {
            it.dependsOn(generateTask)
            it.duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        }
    }

    private fun configureShadow(project: Project, extension: BlinkExtension) {
        try {
            @Suppress("UNCHECKED_CAST")
            val shadowJarClass = Class.forName("com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar") as Class<Task>
            project.tasks.withType(shadowJarClass).configureEach { task ->
                try {
                    val relocateMethod = shadowJarClass.getMethod("relocate", String::class.java, String::class.java)
                    val pkgName = extension.packageName.get().ifEmpty { project.group.toString() }
                    if (pkgName.isNotEmpty()) {
                        relocateMethod.invoke(task, "priv.seventeen.artist.blink", "$pkgName.blink")
                        // Aria 不再 relocate，运行时由 DependencyLoader 独立下载注入
                    }
                } catch (_: Exception) {
                    project.logger.warn("[Blink] 配置 Shadow relocate 失败，请手动配置")
                }
            }
        } catch (_: ClassNotFoundException) {
            project.logger.warn("[Blink] Shadow 插件未找到，跳过 relocate 配置")
        }
    }


    private fun configureProteus(project: Project, extension: BlinkExtension) {
        if (!project.plugins.hasPlugin("priv.seventeen.artist.proteus")) {
            project.logger.warn("[Blink] obfuscate=true 但未应用 Proteus 插件，跳过混淆配置")
            return
        }

        val pkgName = extension.packageName.get().ifEmpty { project.group.toString() }
        if (pkgName.isEmpty()) {
            project.logger.warn("[Blink] 无法确定包名，跳过混淆配置")
            return
        }

        val blinkPkg = "$pkgName.blink"

        try {
            val proteusExt = project.extensions.findByName("proteus") ?: run {
                project.logger.warn("[Blink] 未找到 proteus extension")
                return
            }
            val extClass = proteusExt.javaClass

            // 如果用户已手动设置 configFile，不覆盖
            try {
                val cfProp = extClass.getMethod("getConfigFile").invoke(proteusExt)
                val isPresent = cfProp.javaClass.getMethod("isPresent").invoke(cfProp) as Boolean
                if (isPresent) {
                    project.logger.lifecycle("[Blink] Proteus configFile 已手动设置，跳过自动配置")
                    return
                }
            } catch (_: Exception) { }

            // obfuscate task 依赖 shadowJar，用 shadowJar 输出作为输入
            val obfuscateTask = project.tasks.findByName("obfuscate")
            val shadowJarTask = project.tasks.findByName("shadowJar")
            if (obfuscateTask != null && shadowJarTask != null) {
                obfuscateTask.dependsOn(shadowJarTask)
                try {
                    val archiveFile = shadowJarTask.javaClass.getMethod("getArchiveFile")
                    val regProp = archiveFile.invoke(shadowJarTask)
                    val fileObj = regProp.javaClass.getMethod("get").invoke(regProp)
                    val file = fileObj.javaClass.getMethod("getAsFile").invoke(fileObj) as File
                    setProperty(extClass, proteusExt, "inputFile", file.absolutePath)
                } catch (_: Exception) { }
            }

            // 名称混淆 — 每个维度独立策略
            setProperty(extClass, proteusExt, "rename", true)
            setProperty(extClass, proteusExt, "packageStrategy", "underscore")
            setProperty(extClass, proteusExt, "packageLength", 30)
            setProperty(extClass, proteusExt, "forceDefaultPackage", true)
            setProperty(extClass, proteusExt, "defaultPackage", pkgName)
            setProperty(extClass, proteusExt, "classStrategy", "keyword")
            setProperty(extClass, proteusExt, "classLength", 25)
            setProperty(extClass, proteusExt, "methodStrategy", "il")
            setProperty(extClass, proteusExt, "methodLength", 20)
            setProperty(extClass, proteusExt, "fieldStrategy", "o0")
            setProperty(extClass, proteusExt, "fieldLength", 15)
            setProperty(extClass, proteusExt, "localVariables", "remove")
            setProperty(extClass, proteusExt, "updateResources", true)

            // AES 字符串加密
            setProperty(extClass, proteusExt, "stringEncryption", true)
            setProperty(extClass, proteusExt, "stringEncryptionAlgorithm", "aes")
            setProperty(extClass, proteusExt, "perClassKey", true)

            // 控制流混淆
            setProperty(extClass, proteusExt, "controlFlow", true)

            // 调试信息全部移除
            setProperty(extClass, proteusExt, "debugRemoval", true)
            setProperty(extClass, proteusExt, "lineNumbers", "remove")
            setProperty(extClass, proteusExt, "sourceFile", "remove")
            setProperty(extClass, proteusExt, "generics", "remove")
            setProperty(extClass, proteusExt, "innerClasses", "remove")

            // 类结构重组
            setProperty(extClass, proteusExt, "restructure", true)
            setProperty(extClass, proteusExt, "memberReorder", true)

            // Kotlin 感知
            setProperty(extClass, proteusExt, "kotlinMetadataRewrite", true)
            setProperty(extClass, proteusExt, "kotlinCoroutineAware", true)
            setProperty(extClass, proteusExt, "kotlinStructureAware", true)

            // 映射表
            setProperty(extClass, proteusExt, "mappingFile",
                File(project.layout.buildDirectory.get().asFile, "mapping.txt").absolutePath)

            // keep — 生成的入口类 + 用户自定义
            val keeps = mutableListOf(
                "$pkgName.BlinkGeneratedMain",
                "$pkgName.BlinkGeneratedLifeCycle",
                "$pkgName.BlinkGeneratedEvents"
            )
            keeps.addAll(extension.obfuscateKeep.get())
            addListProperty(extClass, proteusExt, "keepClasses", keeps)

            // exclude — Blink 运行时 + META-INF + 用户自定义
            val excludes = mutableListOf("META-INF/**", "$blinkPkg.**")
            excludes.addAll(extension.obfuscateExclude.get())
            addListProperty(extClass, proteusExt, "exclude", excludes)

            project.logger.lifecycle("[Blink] Proteus 混淆已配置: defaultPackage=$pkgName")

        } catch (e: Exception) {
            project.logger.warn("[Blink] 配置 Proteus 失败: ${e.message}")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun setProperty(extClass: Class<*>, ext: Any, name: String, value: Any) {
        try {
            val getter = extClass.getMethod("get${name.replaceFirstChar { it.uppercase() }}")
            val prop = getter.invoke(ext)
            val setMethod = prop.javaClass.getMethod("set", Any::class.java)
            setMethod.invoke(prop, value)
        } catch (_: Exception) {
            try {
                val getter = extClass.getMethod("get${name.replaceFirstChar { it.uppercase() }}")
                val prop = getter.invoke(ext)
                val convMethod = prop.javaClass.getMethod("convention", Any::class.java)
                convMethod.invoke(prop, value)
            } catch (_: Exception) { }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun addListProperty(extClass: Class<*>, ext: Any, name: String, values: List<String>) {
        try {
            val getter = extClass.getMethod("get${name.replaceFirstChar { it.uppercase() }}")
            val prop = getter.invoke(ext)
            val addAllMethod = prop.javaClass.getMethod("addAll", Iterable::class.java)
            addAllMethod.invoke(prop, values)
        } catch (_: Exception) {
            try {
                val getter = extClass.getMethod("get${name.replaceFirstChar { it.uppercase() }}")
                val prop = getter.invoke(ext)
                val setMethod = prop.javaClass.getMethod("set", Iterable::class.java)
                setMethod.invoke(prop, values)
            } catch (_: Exception) { }
        }
    }
}
