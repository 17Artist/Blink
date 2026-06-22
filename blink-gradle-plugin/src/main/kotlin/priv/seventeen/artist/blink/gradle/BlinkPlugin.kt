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
import org.gradle.api.provider.Provider
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

            if (extension.enableAsteroid.get()) {
                configureAsteroid(project)
            }

            configureCodeGeneration(project, extension)
            configureShadow(project, extension)

            if (extension.obfuscate.get()) {
                configureProteus(project, extension)
            }
        }
    }

    private fun configureAria(project: Project) {
        val ariaVersion = resolveLatestVersion(
            project,
            groupPath = "priv/seventeen/artist/aria",
            artifactId = "aria",
            propertyName = "ariaVersion",
            hardcodedFallback = "1.1.1"
        )
        val depNotation = "priv.seventeen.artist.aria:aria:$ariaVersion"
        // compileOnly: 编译时可用，运行时由 AriaSharedHost 加载到全局共享 ClassLoader
        val compileOnly = project.configurations.findByName("compileOnly")
        if (compileOnly != null) {
            project.dependencies.add("compileOnly", depNotation)
            project.logger.lifecycle("[Blink] Aria 脚本引擎已添加 (compileOnly): $depNotation")
        } else {
            project.logger.warn("[Blink] 未找到 compileOnly 配置，无法添加 Aria 依赖")
        }
    }

    /**
     * 解析仓库中指定构件的最新 release 版本。
     * 优先级：仓库 maven-metadata 的 <release> > <latest> > gradle.properties[propertyName] > 硬编码兜底。
     *
     * @param groupPath         构件 group 的斜杠路径，例如 "priv/seventeen/artist/aria"
     * @param artifactId        构件 artifactId，例如 "aria" / "asteroid-nms"
     * @param propertyName      仓库全部不可达时的兜底 gradle 属性名，例如 "ariaVersion"
     * @param hardcodedFallback 属性也缺失时的最终兜底版本
     */
    private fun resolveLatestVersion(
        project: Project,
        groupPath: String,
        artifactId: String,
        propertyName: String,
        hardcodedFallback: String
    ): String {
        val repos = listOf(
            "https://repo.arcartx.com/repository/maven-public/",
            "https://maven.aliyun.com/repository/central",
            "https://repo1.maven.org/maven2",
            "https://repo.huaweicloud.com/repository/maven"
        )
        val releaseRegex = Regex("<release>([^<]+)</release>")
        val latestRegex = Regex("<latest>([^<]+)</latest>")
        for (repo in repos) {
            val base = repo.trimEnd('/')
            val url = "$base/$groupPath/$artifactId/maven-metadata.xml"
            try {
                val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 10000
                    instanceFollowRedirects = true
                }
                if (conn.responseCode != 200) {
                    conn.disconnect()
                    continue
                }
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val v = releaseRegex.find(text)?.groupValues?.getOrNull(1)
                    ?: latestRegex.find(text)?.groupValues?.getOrNull(1)
                if (!v.isNullOrBlank()) {
                    project.logger.lifecycle("[Blink] $artifactId 版本解析: ${v.trim()} (latest from $base)")
                    return v.trim()
                }
            } catch (_: Exception) {
                // try next repo
            }
        }
        val fallback = project.findProperty(propertyName)?.toString() ?: hardcodedFallback
        project.logger.lifecycle("[Blink] $artifactId 版本解析: $fallback (fallback, 仓库不可达)")
        return fallback
    }

    private fun configureAsteroid(project: Project) {
        val asteroidVersion = resolveLatestVersion(
            project,
            groupPath = "priv/seventeen/artist/asteroid",
            artifactId = "asteroid-nms",
            propertyName = "asteroidVersion",
            hardcodedFallback = "1.1.0"
        )
        val depNotation = "priv.seventeen.artist.asteroid:asteroid-nms:$asteroidVersion"
        // compileOnly: 编译时可用，运行时由 AsteroidSharedHost 部署 BlinkAsteroidHost 到全局共享 ClassLoader
        // （不打包、不 relocate，全 JVM 仅一份，与 Aria 同模型）
        val compileOnly = project.configurations.findByName("compileOnly")
        if (compileOnly != null) {
            project.dependencies.add("compileOnly", depNotation)
            project.logger.lifecycle("[Blink] Asteroid NMS 桥接已添加 (compileOnly): $depNotation")
        } else {
            project.logger.warn("[Blink] 未找到 compileOnly 配置，无法添加 Asteroid 依赖")
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
            task.enableAsteroid.set(extension.enableAsteroid)
            task.foliaSupported.set(extension.foliaSupported)
            task.packageName.set(extension.packageName)
            task.logPrefix.set(extension.logPrefix)
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
                        // Aria / Asteroid 运行时由各自 SharedHost 部署为全局共享插件，不打包、不 relocate
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
            // （Asteroid 已不打包进消费者 jar，无需 exclude）
            val excludes = mutableListOf("META-INF/**", "$blinkPkg.**")
            excludes.addAll(extension.obfuscateExclude.get())
            addListProperty(extClass, proteusExt, "exclude", excludes)

            // 自动排除配置类：BlinkConfig / BlinkSection 子类按字段名反射映射 YAML key，
            // 混淆会把字段名改成乱码导致配置失效（甚至每次构建 key 都变）。
            // 类清单由 blinkGenerate 编译期扫描写到 build/blink-obfuscate-exclude.txt，
            // 此处以惰性 Provider 追加：obfuscate 任务依赖 shadowJar→blinkGenerate，
            // 执行 obfuscate 时文件已生成。
            val buildDirFile = project.layout.buildDirectory.get().asFile
            val configExcludeProvider = project.provider {
                val f = File(buildDirFile, "blink-obfuscate-exclude.txt")
                if (f.isFile) f.readLines().map { it.trim() }.filter { it.isNotEmpty() } else emptyList()
            }
            if (!addListPropertyProvider(extClass, proteusExt, "exclude", configExcludeProvider)) {
                project.logger.warn("[Blink] 无法以 Provider 方式追加配置类排除，开启混淆时 BlinkConfig 字段名可能被改写，请手动 obfuscateExclude 配置类")
            }

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

    /**
     * 以惰性 [org.gradle.api.provider.Provider] 向某个 ListProperty 追加元素（反射调用
     * {@code addAll(Provider)}）。Provider 会在 Proteus 读取该属性（任务执行期）时才求值，
     * 因此能引用此刻尚未生成、但在 obfuscate 执行前会由 blinkGenerate 写出的文件内容。
     *
     * @return 成功追加返回 true；目标属性不支持 addAll(Provider) 时返回 false。
     */
    @Suppress("UNCHECKED_CAST")
    private fun addListPropertyProvider(
        extClass: Class<*>,
        ext: Any,
        name: String,
        provider: Provider<out Iterable<String>>
    ): Boolean {
        return try {
            val getter = extClass.getMethod("get${name.replaceFirstChar { it.uppercase() }}")
            val prop = getter.invoke(ext)
            val addAllMethod = prop.javaClass.getMethod("addAll", Provider::class.java)
            addAllMethod.invoke(prop, provider)
            true
        } catch (_: Exception) {
            false
        }
    }
}
