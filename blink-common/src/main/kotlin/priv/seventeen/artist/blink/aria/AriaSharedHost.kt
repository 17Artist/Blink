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
package priv.seventeen.artist.blink.aria

import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import priv.seventeen.artist.blink.BlinkLog
import priv.seventeen.artist.blink.loader.DependencyLoader
import java.io.File
import java.util.Base64
import java.util.jar.JarFile
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Aria 共享中间件。
 *
 * <p>多个使用 Blink 框架的插件并存时，本对象保证整个 JVM 中仅有一份 Aria 实例：
 * <ul>
 *   <li>第一个加载且需要 Aria 的 Blink 插件作为 <b>部署者 (deployer)</b>：
 *     <ol>
 *       <li>从 maven 仓库的 maven-metadata.xml 解析 Aria 最新 release 版本</li>
 *       <li>对比 {@code plugins/.blink-shared/BlinkAriaHost.jar} 中嵌入的版本，落后则重新下载</li>
 *       <li>下载 aria.jar 到临时位置，{@link #wrapAriaIntoHostJar wrap} 成可被 Bukkit 加载的
 *           {@code BlinkAriaHost.jar}（追加 {@code plugin.yml} 与 {@code BlinkAriaHostPlugin.class}）</li>
 *       <li>调用 {@link org.bukkit.plugin.PluginManager#loadPlugin(File)} +
 *           {@link org.bukkit.plugin.PluginManager#enablePlugin enablePlugin}
 *           将 host 插入到 Bukkit 插件体系</li>
 *     </ol>
 *   </li>
 *   <li>后续 Blink 插件作为 <b>客户 (client)</b>：检测到 {@code BlinkAriaHost} 已存在则直接复用，
 *       跳过所有部署步骤。</li>
 * </ul>
 *
 * <p><b>共享语义</b>：BlinkAriaHost 作为正式 Bukkit 插件存活，其 PluginClassLoader 进入 Bukkit
 * 全局类查找链。任何插件（包括非 Blink 插件）都可以通过 {@code Class.forName} 或
 * {@code depend: [BlinkAriaHost]} 直接访问 Aria 类型，所有插件共享同一个 {@code Aria.DEFAULT_ENGINE}
 * 与 {@code CallableManager.INSTANCE}。
 *
 * <p><b>离线 fallback</b>：若仓库不可达但 {@code .blink-shared/BlinkAriaHost.jar} 已存在，
 * 跳过版本检查直接使用本地缓存。
 *
 * <p>本对象由 {@link priv.seventeen.artist.blink.script.AriaScriptManager} 内部使用。
 */
internal object AriaSharedHost {

    private const val SHARED_DIR_NAME = ".blink-shared"
    private const val HOST_PLUGIN_NAME = "BlinkAriaHost"
    private const val HOST_JAR_NAME = "BlinkAriaHost.jar"

    /** maven-metadata.xml 解析正则 */
    private val RELEASE_PATTERN = Regex("<release>([^<]+)</release>")
    private val LATEST_PATTERN = Regex("<latest>([^<]+)</latest>")

    /**
     * 内嵌的 BlinkAriaHostPlugin 字节码（base64）。源代码见同包文档。
     *
     * <p>对应 Kotlin/Java 等价代码：
     * <pre>
     * package priv.seventeen.artist.blink.aria.host;
     * public class BlinkAriaHostPlugin extends org.bukkit.plugin.java.JavaPlugin {
     *     &#64;Override public void onEnable() {
     *         try { Class.forName("priv.seventeen.artist.aria.Aria"); } catch (Throwable ignored) {}
     *         getLogger().info("Aria host loaded");
     *     }
     * }
     * </pre>
     *
     * <p>编译目标 JDK 17（class major=61）。如需修改源码，重新 javac 编译并替换此 base64。
     */
    private const val HOST_PLUGIN_CLASS_BASE64 =
        "yv66vgAAAD0AJQoAAgADBwAEDAAFAAYBACFvcmcvYnVra2l0L3BsdWdpbi9qYXZhL0phdmFQbHVnaW4BAAY8aW5pdD4BAAMoKVYIAAgBAB9wcml2LnNldmVudGVlbi5hcnRpc3QuYXJpYS5BcmlhCgAKAAsHAAwMAA0ADgEAD2phdmEvbGFuZy9DbGFzcwEAB2Zvck5hbWUBACUoTGphdmEvbGFuZy9TdHJpbmc7KUxqYXZhL2xhbmcvQ2xhc3M7BwAQAQATamF2YS9sYW5nL1Rocm93YWJsZQoAEgATBwAUDAAVABYBADlwcml2L3NldmVudGVlbi9hcnRpc3QvYmxpbmsvYXJpYS9ob3N0L0JsaW5rQXJpYUhvc3RQbHVnaW4BAAlnZXRMb2dnZXIBABwoKUxqYXZhL3V0aWwvbG9nZ2luZy9Mb2dnZXI7CAAYAQAQQXJpYSBob3N0IGxvYWRlZAoAGgAbBwAcDAAdAB4BABhqYXZhL3V0aWwvbG9nZ2luZy9Mb2dnZXIBAARpbmZvAQAVKExqYXZhL2xhbmcvU3RyaW5nOylWAQAEQ29kZQEAD0xpbmVOdW1iZXJUYWJsZQEACG9uRW5hYmxlAQANU3RhY2tNYXBUYWJsZQEAClNvdXJjZUZpbGUBABhCbGlua0FyaWFIb3N0UGx1Z2luLmphdmEAIQASAAIAAAAAAAIAAQAFAAYAAQAfAAAAHQABAAEAAAAFKrcAAbEAAAABACAAAAAGAAEAAAAFAAEAIQAGAAEAHwAAAFEAAgACAAAAFBIHuAAJV6cABEwqtgAREhe2ABmxAAEAAAAGAAkADwACACAAAAAWAAUAAAAJAAYACwAJAAoACgAMABMADQAiAAAABwACSQcADwAAAQAjAAAAAgAk"

    @Volatile
    private var hostClassLoader: ClassLoader? = null

    @Volatile
    private var ariaVersion: String? = null

    /** 当前共享 Aria 版本，未初始化或失败时为 null */
    val currentVersion: String? get() = ariaVersion

    /**
     * 获取或部署 BlinkAriaHost 插件，返回其 PluginClassLoader。
     * 调用线程安全，幂等。
     */
    @Synchronized
    fun acquire(plugin: JavaPlugin): ClassLoader? {
        hostClassLoader?.let { return it }

        // 1. 已经有别的 Blink 插件部署过 host
        val existing = try { Bukkit.getPluginManager().getPlugin(HOST_PLUGIN_NAME) } catch (_: Throwable) { null }
        if (existing != null && existing.isEnabled) {
            hostClassLoader = existing.javaClass.classLoader
            ariaVersion = existing.description.version
            BlinkLog.detail("BlinkAriaHost 已存在 (v${ariaVersion ?: "?"})，复用")
            return hostClassLoader
        }

        // 2. 当前插件作为部署者
        return ensureUpToDateAndLoad(plugin)
    }

    /** 检查/更新本地 BlinkAriaHost.jar，然后用 PluginManager.loadPlugin 加载它。 */
    private fun ensureUpToDateAndLoad(plugin: JavaPlugin): ClassLoader? {
        val sharedDir = File(plugin.dataFolder.parentFile, SHARED_DIR_NAME).apply { mkdirs() }
        val hostJar = File(sharedDir, HOST_JAR_NAME)

        val latest = resolveLatestAriaVersion(plugin)  // 仓库不可达返回 null
        val local = readEmbeddedVersion(hostJar)        // 文件不存在或无 plugin.yml 返回 null

        when {
            !hostJar.isFile && latest == null -> {
                BlinkLog.error("BlinkAriaHost 不可用：本地无缓存且 maven 仓库不可达")
                return null
            }
            !hostJar.isFile -> {
                BlinkLog.info("BlinkAriaHost 首次部署，下载 Aria v$latest")
                if (!downloadAndWrap(latest!!, sharedDir, hostJar, plugin)) return null
            }
            latest != null && latest != local -> {
                BlinkLog.info("BlinkAriaHost 升级 v${local ?: "?"} → v$latest")
                if (!downloadAndWrap(latest, sharedDir, hostJar, plugin)) {
                    BlinkLog.warn("升级失败，沿用本地 v${local ?: "?"}")
                }
            }
            latest == null -> {
                BlinkLog.detail("仓库不可达，使用本地 BlinkAriaHost v${local ?: "?"}")
            }
            else -> {
                BlinkLog.detail("BlinkAriaHost v${local ?: "?"} 已是最新")
            }
        }

        return loadHostJar(hostJar, plugin)
    }

    /** 调用 Bukkit PluginManager 加载并启用 host jar。 */
    private fun loadHostJar(jar: File, plugin: JavaPlugin): ClassLoader? {
        return try {
            val pm = Bukkit.getPluginManager()
            val loaded = pm.loadPlugin(jar)
                ?: throw IllegalStateException("PluginManager.loadPlugin 返回 null")
            pm.enablePlugin(loaded)
            ariaVersion = loaded.description.version
            hostClassLoader = loaded.javaClass.classLoader
            BlinkLog.success("BlinkAriaHost 已加载 (v$ariaVersion, deployer=${plugin.name})")
            hostClassLoader
        } catch (e: Throwable) {
            BlinkLog.error("BlinkAriaHost 加载失败：${e.message}", e)
            null
        }
    }

    /** 下载 aria-{version}.jar 到临时文件，wrap 成 host.jar 后清理临时文件。 */
    private fun downloadAndWrap(version: String, sharedDir: File, outJar: File, plugin: JavaPlugin): Boolean {
        val tempAria = File(sharedDir, "aria-$version.tmp.jar")
        try {
            val ok = DependencyLoader.downloadDependencyInternal(
                DependencyLoader.Dependency("priv.seventeen.artist.aria", "aria", version),
                tempAria, plugin
            )
            if (!ok) {
                BlinkLog.error("Aria v$version 下载失败")
                return false
            }
            wrapAriaIntoHostJar(tempAria, outJar, version)
            return true
        } catch (e: Throwable) {
            BlinkLog.error("Wrap BlinkAriaHost 失败", e)
            return false
        } finally {
            if (tempAria.exists()) tempAria.delete()
        }
    }

    /**
     * 把 Aria jar 重新打包成 BlinkAriaHost.jar：
     * 复制原 jar 所有 entry（跳过原 plugin.yml / META-INF/MANIFEST.MF），追加：
     * <ul>
     *   <li>{@code plugin.yml} —— 让 Bukkit 识别为插件</li>
     *   <li>{@code priv/seventeen/artist/blink/aria/host/BlinkAriaHostPlugin.class} —— 插件入口类</li>
     *   <li>{@code META-INF/MANIFEST.MF} —— 简洁版</li>
     * </ul>
     */
    private fun wrapAriaIntoHostJar(ariaJar: File, outJar: File, version: String) {
        val tmpOut = File(outJar.parentFile, "${outJar.name}.tmp")
        ZipOutputStream(tmpOut.outputStream().buffered()).use { zos ->
            // 复制原 aria.jar 所有 entry，跳过我们要替换的
            ZipInputStream(ariaJar.inputStream().buffered()).use { zis ->
                val seen = HashSet<String>()
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val name = entry.name
                    if (name == "plugin.yml" || name == "META-INF/MANIFEST.MF") continue
                    if (!seen.add(name)) continue  // 防御重复 entry
                    val newEntry = ZipEntry(name).apply {
                        if (entry.time != -1L) time = entry.time
                    }
                    zos.putNextEntry(newEntry)
                    if (!entry.isDirectory) zis.copyTo(zos)
                    zos.closeEntry()
                }
            }
            // 追加 MANIFEST.MF
            zos.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
            zos.write("Manifest-Version: 1.0\r\nCreated-By: Blink AriaSharedHost\r\n\r\n".toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            // 追加 plugin.yml
            zos.putNextEntry(ZipEntry("plugin.yml"))
            zos.write(buildPluginYml(version).toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            // 追加 host plugin class
            zos.putNextEntry(ZipEntry("priv/seventeen/artist/blink/aria/host/BlinkAriaHostPlugin.class"))
            zos.write(Base64.getDecoder().decode(HOST_PLUGIN_CLASS_BASE64))
            zos.closeEntry()
        }
        if (outJar.exists()) outJar.delete()
        if (!tmpOut.renameTo(outJar)) {
            tmpOut.copyTo(outJar, overwrite = true)
            tmpOut.delete()
        }
    }

    private fun buildPluginYml(version: String): String = buildString {
        append("name: ").append(HOST_PLUGIN_NAME).append('\n')
        append("version: '").append(version).append("'\n")
        append("main: priv.seventeen.artist.blink.aria.host.BlinkAriaHostPlugin\n")
        append("api-version: '1.20'\n")
        append("description: 'Aria script engine host (auto-deployed by Blink)'\n")
        append("authors: ['17Artist']\n")
    }

    /** 读取已存在 host jar 中 plugin.yml 的 version 字段，找不到返回 null。 */
    private fun readEmbeddedVersion(jar: File): String? {
        if (!jar.isFile) return null
        return try {
            JarFile(jar).use { jf ->
                val entry = jf.getJarEntry("plugin.yml") ?: return@use null
                jf.getInputStream(entry).bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.firstOrNull { it.trim().startsWith("version:") }
                        ?.substringAfter(":")
                        ?.trim()
                        ?.trim('\'', '"')
                }
            }
        } catch (_: Throwable) { null }
    }

    /**
     * 从 maven 仓库 maven-metadata.xml 解析 Aria 最新 release 版本。
     * 任一仓库可达且解析成功即返回；全部失败返回 null（不再 fallback 到硬编码版本，
     * 由调用方决定是用本地缓存还是报错）。
     */
    private fun resolveLatestAriaVersion(plugin: JavaPlugin): String? {
        val repos = DependencyLoader.loadRepositoriesInternal(plugin)
        for (repo in repos) {
            val base = repo.trimEnd('/')
            val url = "$base/priv/seventeen/artist/aria/aria/maven-metadata.xml"
            try {
                val text = DependencyLoader.fetchTextInternal(url) ?: continue
                val v = RELEASE_PATTERN.find(text)?.groupValues?.getOrNull(1)
                    ?: LATEST_PATTERN.find(text)?.groupValues?.getOrNull(1)
                if (!v.isNullOrBlank()) {
                    BlinkLog.detail("Aria 最新版本：${v.trim()} (from $base)")
                    return v.trim()
                }
            } catch (_: Throwable) { /* try next */ }
        }
        return null
    }
}
