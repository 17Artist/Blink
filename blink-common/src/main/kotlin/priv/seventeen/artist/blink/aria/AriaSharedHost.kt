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
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin
import priv.seventeen.artist.blink.BlinkLog
import priv.seventeen.artist.blink.loader.DependencyLoader
import java.io.File
import java.net.URL
import java.net.URLClassLoader

/**
 * Aria 共享中间件。
 *
 * <p>多个使用 Blink 框架的插件并存时，本对象保证整个 JVM 中 {@code aria.jar} 只被一个
 * {@link URLClassLoader} 加载一次：
 * <ul>
 *   <li>第一个加载 Aria 的插件作为 <b>宿主 (host)</b>：从 maven 仓库下载 aria.jar 到
 *       {@code plugins/.blink-shared/}，创建共享 ClassLoader，并通过 Bukkit
 *       {@link org.bukkit.plugin.ServicesManager} 注册一个 marker map 供其它插件查找。</li>
 *   <li>后续加载 Aria 的插件作为 <b>客户 (client)</b>：从 ServicesManager 查到已注册的 marker，
 *       复用同一个 ClassLoader，不再下载也不再注入到自身的 PluginClassLoader。</li>
 * </ul>
 *
 * <p>由于 Bukkit 中每个插件有独立的 PluginClassLoader，跨插件传递对象需要使用 JDK 中性类型。
 * 因此 ServicesManager 注册项的 key 类型为 {@link Map}，service 实例为
 * {@code Map<String, Any?>}，包含两个字段：
 * <ul>
 *   <li>{@code __blink_aria_marker__}: 固定字符串 {@code "true"}，用于在多个 Map service 中筛选 Aria 共享 host。</li>
 *   <li>{@code classloader}: 共享的 {@link ClassLoader} 实例。</li>
 *   <li>{@code version}: 实际加载的 Aria 版本字符串。</li>
 * </ul>
 *
 * <p>本对象供 {@link priv.seventeen.artist.blink.script.AriaScriptManager} 内部使用，
 * 不暴露给业务代码。
 */
internal object AriaSharedHost {

    private const val SHARED_DIR_NAME = ".blink-shared"
    private const val MARKER_KEY = "__blink_aria_marker__"
    private const val MARKER_VALUE = "true"
    private const val CL_KEY = "classloader"
    private const val VERSION_KEY = "version"

    /** maven-metadata.xml 中查找 <release> 标签的简单正则 */
    private val RELEASE_PATTERN = Regex("<release>([^<]+)</release>")
    private val LATEST_PATTERN = Regex("<latest>([^<]+)</latest>")

    /** Aria 版本兜底（仓库不可达且 gradle.properties 也不可读时使用） */
    private const val FALLBACK_VERSION = "1.1.1"

    @Volatile
    private var sharedClassLoader: ClassLoader? = null

    @Volatile
    private var ariaVersion: String? = null

    /** 暴露给 AriaScriptManager 查询当前共享版本 */
    val currentVersion: String? get() = ariaVersion

    /**
     * 获取或创建共享 ClassLoader。
     * 调用线程安全，幂等。
     *
     * @param plugin 当前插件，用于注册 ServicesManager service、获取数据目录等
     * @return 共享 ClassLoader；下载/选举失败时返回 null
     */
    @Synchronized
    fun acquire(plugin: JavaPlugin): ClassLoader? {
        sharedClassLoader?.let { return it }

        val existing = lookupExistingHost()
        if (existing != null) {
            sharedClassLoader = existing.classLoader
            ariaVersion = existing.version
            BlinkLog.detail("Aria 共享 ClassLoader 已存在 (v${existing.version})，复用")
            return existing.classLoader
        }

        return becomeHost(plugin)
    }

    /**
     * 在 ServicesManager 中查找已注册的 Aria 共享 host。
     */
    private fun lookupExistingHost(): SharedAriaInfo? {
        val sm = try { Bukkit.getServicesManager() } catch (_: Throwable) { return null }
        val regs = try { sm.getRegistrations(Map::class.java) } catch (_: Throwable) { return null }
        for (reg in regs) {
            @Suppress("UNCHECKED_CAST")
            val provider = (try { reg.provider } catch (_: Throwable) { null }) as? Map<*, *> ?: continue
            if (provider[MARKER_KEY] != MARKER_VALUE) continue
            val cl = provider[CL_KEY] as? ClassLoader ?: continue
            val ver = provider[VERSION_KEY] as? String ?: continue
            return SharedAriaInfo(cl, ver)
        }
        return null
    }

    /**
     * 当前插件作为宿主：下载 aria.jar，创建 URLClassLoader，注册到 ServicesManager。
     */
    private fun becomeHost(plugin: JavaPlugin): ClassLoader? {
        val sharedDir = File(plugin.dataFolder.parentFile, SHARED_DIR_NAME).apply { mkdirs() }
        val version = resolveLatestAriaVersion(plugin)

        val ariaJar = downloadAriaJar(version, sharedDir, plugin)
        if (ariaJar == null || !ariaJar.isFile) {
            BlinkLog.error("Aria 共享宿主初始化失败：aria-$version.jar 下载失败")
            return null
        }

        // 父 ClassLoader 用 JavaPlugin 所在的 CL（一般是 Spigot/Paper 主类加载器或 PluginClassLoader 的父）。
        // 这样共享 CL 中的 Aria 在反射 ScriptEngine / Bukkit 等 API 时能命中宿主 JVM。
        val parentCL = JavaPlugin::class.java.classLoader
        val sharedCL = URLClassLoader(arrayOf<URL>(ariaJar.toURI().toURL()), parentCL)

        // 注册到 ServicesManager（用 JDK Map 作为类型 key 绕过 PluginClassLoader 隔离）
        val service: Map<String, Any?> = mapOf(
            MARKER_KEY to MARKER_VALUE,
            CL_KEY to sharedCL,
            VERSION_KEY to version
        )
        try {
            Bukkit.getServicesManager().register(
                Map::class.java,
                service,
                plugin,
                ServicePriority.Normal
            )
        } catch (e: Throwable) {
            BlinkLog.warn("Aria 共享 host 注册到 ServicesManager 失败：${e.message}")
            // 即使注册失败，本插件自己仍可使用，只是后续插件无法发现
        }

        sharedClassLoader = sharedCL
        ariaVersion = version
        BlinkLog.success("Aria 共享 ClassLoader 已就绪 (v$version, host=${plugin.name})")
        return sharedCL
    }

    /**
     * 从 maven 仓库的 maven-metadata.xml 解析最新版本。
     * 失败时回退到 plugin.yml/gradle 中静态写入的版本，再不济回退到硬编码。
     */
    private fun resolveLatestAriaVersion(plugin: JavaPlugin): String {
        val repos = DependencyLoader.loadRepositoriesInternal(plugin)
        for (repo in repos) {
            val base = repo.trimEnd('/')
            val url = "$base/priv/seventeen/artist/aria/aria/maven-metadata.xml"
            try {
                val text = DependencyLoader.fetchTextInternal(url) ?: continue
                val release = RELEASE_PATTERN.find(text)?.groupValues?.getOrNull(1)
                    ?: LATEST_PATTERN.find(text)?.groupValues?.getOrNull(1)
                if (!release.isNullOrBlank()) {
                    BlinkLog.info("Aria 最新版本解析成功：$release (from $base)")
                    return release.trim()
                }
            } catch (_: Throwable) {
                // try next repo
            }
        }
        BlinkLog.warn("Aria 仓库 metadata 解析失败，回退到内置版本 $FALLBACK_VERSION")
        return FALLBACK_VERSION
    }

    /**
     * 下载 aria-{version}.jar 到共享目录。
     */
    private fun downloadAriaJar(version: String, sharedDir: File, plugin: JavaPlugin): File? {
        val target = File(sharedDir, "aria-$version.jar")
        if (target.isFile && target.length() > 1024) {
            BlinkLog.detail("aria-$version.jar 已存在于共享目录，直接使用")
            return target
        }
        val dep = DependencyLoader.Dependency(
            "priv.seventeen.artist.aria", "aria", version
        )
        val ok = DependencyLoader.downloadDependencyInternal(dep, target, plugin)
        return if (ok) target else null
    }

    private data class SharedAriaInfo(
        val classLoader: ClassLoader,
        val version: String
    )
}
