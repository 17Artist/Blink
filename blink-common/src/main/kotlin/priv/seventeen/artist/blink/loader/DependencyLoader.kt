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
package priv.seventeen.artist.blink.loader

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import priv.seventeen.artist.blink.BlinkLog
import sun.misc.Unsafe
import java.io.File
import java.lang.reflect.Method
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.net.HttpURLConnection
import java.net.URL

object DependencyLoader {

    private const val CONNECT_TIMEOUT = 5000
    private const val READ_TIMEOUT = 30000

    private val DEFAULT_REPOSITORIES = listOf(
        "https://repo.arcartx.com/repository/maven-public/",
        "https://maven.aliyun.com/repository/central",
        "https://repo1.maven.org/maven2",
        "https://repo.huaweicloud.com/repository/maven"
    )

    data class Dependency(
        val group: String,
        val artifact: String,
        val version: String
    ) {
        val fileName: String get() = "$artifact-$version.jar"
        val path: String get() {
            val groupPath = group.replace('.', '/')
            return "$groupPath/$artifact/$version/$fileName"
        }
    }

    private val NASHORN_DEPENDENCIES = listOf(
        Dependency("org.openjdk.nashorn", "nashorn-core", "15.7"),
        Dependency("org.ow2.asm", "asm", "9.7.1"),
        Dependency("org.ow2.asm", "asm-commons", "9.7.1"),
        Dependency("org.ow2.asm", "asm-tree", "9.7.1"),
        Dependency("org.ow2.asm", "asm-util", "9.7.1")
    )

    private val ARIA_DEPENDENCIES = listOf(
        Dependency("priv.seventeen.artist.aria", "aria", "1.0.1")
    )

    fun loadAria(plugin: JavaPlugin) {
        val libsDir = File(plugin.dataFolder, "libs")
        libsDir.mkdirs()

        val repositories = loadRepositories(plugin)
        val classLoader = plugin.javaClass.classLoader

        for (dep in ARIA_DEPENDENCIES) {
            val file = File(libsDir, dep.fileName)
            if (file.exists()) {
                BlinkLog.detail("${dep.fileName} 已存在，直接加载")
                tryInject(classLoader, file, plugin)
                continue
            }
            downloadAndInject(dep, libsDir, repositories, classLoader, plugin)
        }
    }

    fun loadNashorn(plugin: JavaPlugin): List<File> {
        val libsDir = File(plugin.dataFolder, "libs")
        libsDir.mkdirs()

        val repositories = loadRepositories(plugin)

        return NASHORN_DEPENDENCIES.map { dep ->
            val file = File(libsDir, dep.fileName)
            if (!file.exists()) {
                BlinkLog.info("正在下载 ${dep.group}:${dep.artifact}:${dep.version}...")
                val ok = tryDownload(dep, repositories, file, plugin)
                if (!ok) {
                    BlinkLog.error("${dep.fileName} 所有仓库均下载失败!")
                }
                if (ok) BlinkLog.success("下载完成: ${dep.fileName}")
            }
            file
        }.filter { it.exists() }
    }

    fun loadAll(plugin: JavaPlugin) {
        val libsDir = File(plugin.dataFolder, "libs")
        libsDir.mkdirs()

        val repositories = loadRepositories(plugin)
        val classLoader = plugin.javaClass.classLoader

        val pluginDeps = parsePluginDependencies(plugin)
        for (dep in pluginDeps) {
            val file = File(libsDir, dep.fileName)
            if (file.exists()) {
                BlinkLog.detail("${dep.fileName} 已存在，直接加载")
                tryInject(classLoader, file, plugin)
                continue
            }
            downloadAndInject(dep, libsDir, repositories, classLoader, plugin)
        }
    }

    private fun downloadAndInject(
        dep: Dependency, libsDir: File, repos: List<String>,
        classLoader: ClassLoader, plugin: JavaPlugin
    ) {
        val file = File(libsDir, dep.fileName)
        if (!file.exists()) {
            BlinkLog.info("正在下载 ${dep.group}:${dep.artifact}:${dep.version}...")
            val ok = tryDownload(dep, repos, file, plugin)
            if (!ok) {
                BlinkLog.error("${dep.fileName} 所有仓库均下载失败!")
                return
            }
            BlinkLog.success("下载完成: ${dep.fileName}")
        }
        tryInject(classLoader, file, plugin)
    }

    private fun tryInject(classLoader: ClassLoader, file: File, @Suppress("UNUSED_PARAMETER") plugin: JavaPlugin) {
        try {
            injectClasspath(classLoader, file)
        } catch (e: Exception) {
            BlinkLog.error("注入 ${file.name} 失败", e)
        }
    }

    private fun parsePluginDependencies(plugin: JavaPlugin): List<Dependency> {
        val pluginYml = plugin.getResource("plugin.yml") ?: return emptyList()
        val yaml = YamlConfiguration()
        try {
            yaml.loadFromString(pluginYml.bufferedReader().use { it.readText() })
        } catch (_: Exception) {
            return emptyList()
        }

        val libs = yaml.getStringList("blink-libraries")
        if (libs.isEmpty()) return emptyList()

        val deps = mutableListOf<Dependency>()
        for (notation in libs) {
            val parts = notation.split(":")
            if (parts.size != 3) {
                BlinkLog.warn("无效的依赖声明: $notation (格式: group:artifact:version)")
                continue
            }
            deps.add(Dependency(parts[0].trim(), parts[1].trim(), parts[2].trim()))
        }

        if (deps.isNotEmpty()) {
            BlinkLog.info("从 plugin.yml 解析到 ${deps.size} 个依赖")
        }
        return deps
    }

    private fun loadRepositories(plugin: JavaPlugin): List<String> {
        val configFile = File(plugin.dataFolder, "blink.yml")
        if (!configFile.exists()) return DEFAULT_REPOSITORIES
        val yaml = YamlConfiguration.loadConfiguration(configFile)
        return yaml.getStringList("repositories").takeIf { it.isNotEmpty() } ?: DEFAULT_REPOSITORIES
    }

    private fun tryDownload(dep: Dependency, repos: List<String>, target: File, @Suppress("UNUSED_PARAMETER") plugin: JavaPlugin): Boolean {
        for (repo in repos) {
            val repoBase = repo.trimEnd('/')
            val url = "$repoBase/${dep.path}"
            try {
                BlinkLog.detail("  尝试: $repoBase")
                downloadFile(url, target)
                return true
            } catch (e: Exception) {
                BlinkLog.warn("  $repoBase 失败: ${e.message}")
            }
        }
        return false
    }

    private fun downloadFile(url: String, target: File) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            conn.instanceFollowRedirects = true

            if (conn.responseCode != 200) {
                throw RuntimeException("HTTP ${conn.responseCode}")
            }

            conn.inputStream.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }

            if (tmp.length() < 1024) {
                throw RuntimeException("文件过小 (${tmp.length()} bytes)，可能不是有效 JAR")
            }

            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        } finally {
            conn.disconnect()
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun injectClasspath(classLoader: ClassLoader, file: File) {
        val url = file.toURI().toURL()
        val addURL = findAddURL(classLoader)

        if (addURL != null) {
            try {
                addURL.isAccessible = true
                addURL.invoke(classLoader, url)
                return
            } catch (_: Exception) { }
        }

        val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe")
            .also { it.isAccessible = true }.get(null) as Unsafe
        val implLookupField = MethodHandles.Lookup::class.java.getDeclaredField("IMPL_LOOKUP")
        val offset = unsafe.staticFieldOffset(implLookupField)
        val trustedLookup = unsafe.getObject(MethodHandles.Lookup::class.java, offset) as MethodHandles.Lookup

        if (addURL != null) {
            trustedLookup.unreflect(addURL).invoke(classLoader, url)
            return
        }

        var clazz: Class<*>? = classLoader.javaClass
        while (clazz != null) {
            try {
                val handle = trustedLookup.findVirtual(clazz, "addURL",
                    MethodType.methodType(Void.TYPE, URL::class.java))
                handle.invoke(classLoader, url)
                return
            } catch (_: NoSuchMethodException) {
                clazz = clazz.superclass
            } catch (_: IllegalAccessException) {
                clazz = clazz.superclass
            }
        }

        throw IllegalStateException("无法向 ClassLoader ${classLoader.javaClass.name} 注入依赖")
    }

    private fun findAddURL(classLoader: ClassLoader): Method? {
        var clazz: Class<*>? = classLoader.javaClass
        while (clazz != null) {
            try {
                return clazz.getDeclaredMethod("addURL", URL::class.java)
            } catch (_: NoSuchMethodException) {
                clazz = clazz.superclass
            }
        }
        return null
    }
}
