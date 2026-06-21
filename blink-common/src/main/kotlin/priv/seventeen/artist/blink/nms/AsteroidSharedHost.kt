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
package priv.seventeen.artist.blink.nms

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
 * Asteroid 共享中间件（与 [priv.seventeen.artist.blink.aria.AriaSharedHost] 同模型）。
 *
 * <p>多个使用 Blink 框架且开启 Asteroid 的插件并存时，本对象保证整个 JVM 中仅有一份 Asteroid 实例：
 * <ul>
 *   <li>第一个加载且需要 Asteroid 的 Blink 插件作为 <b>部署者 (deployer)</b>：
 *     <ol>
 *       <li>从 maven 仓库的 maven-metadata.xml 解析 asteroid-nms 最新 release 版本</li>
 *       <li>对比 {@code plugins/.blink-shared/BlinkAsteroidHost.jar} 中嵌入的版本，落后则重新下载</li>
 *       <li>下载 asteroid-nms.jar 到临时位置，{@link #wrapAsteroidIntoHostJar wrap} 成可被 Bukkit 加载的
 *           {@code BlinkAsteroidHost.jar}（追加 {@code plugin.yml} 与 {@code BlinkAsteroidHostPlugin.class}）</li>
 *       <li>调用 {@link org.bukkit.plugin.PluginManager#loadPlugin(File)} +
 *           {@link org.bukkit.plugin.PluginManager#enablePlugin enablePlugin}
 *           将 host 插入到 Bukkit 插件体系。宿主 onEnable 执行 {@code NMSLoader.load(this)}，
 *           使全局数据包注入 + Bukkit 事件监听绑定到 <b>宿主</b>（长生命周期），而非任何消费插件。</li>
 *     </ol>
 *   </li>
 *   <li>后续 Blink 插件作为 <b>客户 (client)</b>：检测到 {@code BlinkAsteroidHost} 已存在则直接复用，
 *       跳过所有部署步骤。</li>
 * </ul>
 *
 * <p><b>共享语义</b>：BlinkAsteroidHost 作为正式 Bukkit 插件存活，其 PluginClassLoader 进入 Bukkit
 * 全局类查找链。任何插件都可以直接访问 {@code priv.seventeen.artist.asteroid.*} 类型，所有插件共享同一份
 * {@code AsteroidAPI} 全局单例与同一套 NMS 实现。
 *
 * <p><b>离线 fallback</b>：若仓库不可达但 {@code .blink-shared/BlinkAsteroidHost.jar} 已存在，
 * 跳过版本检查直接使用本地缓存。
 *
 * <p>本对象由 {@link AsteroidManager} 内部使用。
 */
internal object AsteroidSharedHost {

    private const val SHARED_DIR_NAME = ".blink-shared"
    private const val HOST_PLUGIN_NAME = "BlinkAsteroidHost"
    private const val HOST_JAR_NAME = "BlinkAsteroidHost.jar"

    /**
     * Host 类的 jar entry 路径与 main 类全限定名以 base64 编码内嵌，运行时解码。
     * 否则 Shadow 插件的 relocate 会把字面量 {@code priv.seventeen.artist.blink.nms.host} 改写成
     * 用户的 relocate 目标包，导致 jar 内类路径与 class 文件 this_class 不一致，Bukkit 加载失败。
     */
    private const val HOST_CLASS_PATH_B64 =
        "cHJpdi9zZXZlbnRlZW4vYXJ0aXN0L2JsaW5rL25tcy9ob3N0L0JsaW5rQXN0ZXJvaWRIb3N0UGx1Z2luLmNsYXNz"
    private const val HOST_MAIN_CLASS_B64 =
        "cHJpdi5zZXZlbnRlZW4uYXJ0aXN0LmJsaW5rLm5tcy5ob3N0LkJsaW5rQXN0ZXJvaWRIb3N0UGx1Z2lu"
    private val hostClassPath: String by lazy {
        String(Base64.getDecoder().decode(HOST_CLASS_PATH_B64), Charsets.UTF_8)
    }
    private val hostMainClass: String by lazy {
        String(Base64.getDecoder().decode(HOST_MAIN_CLASS_B64), Charsets.UTF_8)
    }

    /** maven-metadata.xml 解析正则 */
    private val RELEASE_PATTERN = Regex("<release>([^<]+)</release>")
    private val LATEST_PATTERN = Regex("<latest>([^<]+)</latest>")

    /**
     * 内嵌的 BlinkAsteroidHostPlugin 字节码（base64）。
     *
     * <p>对应 Java 源码（编译目标 JDK 17 / class major=61）：
     * <pre>
     * package priv.seventeen.artist.blink.nms.host;
     *
     * import org.bukkit.plugin.java.JavaPlugin;
     * import priv.seventeen.artist.asteroid.AsteroidAPI;
     * import priv.seventeen.artist.asteroid.internal.NMSLoader;
     *
     * public class BlinkAsteroidHostPlugin extends JavaPlugin {
     *     &#64;Override public void onEnable() {
     *         try {
     *             NMSLoader.load(this); // Plugin 重载：全局注入 + 事件监听绑定到本宿主
     *             getLogger().info("Asteroid host loaded (MC " + AsteroidAPI.getMcVersion() + ")");
     *         } catch (Throwable t) {
     *             getLogger().severe("Asteroid host failed to load NMS: " + t.getMessage());
     *         }
     *     }
     * }
     * </pre>
     *
     * <p>如需修改源码，重新编译并替换此 base64：
     * <pre>
     * javac --release 17 -XDstringConcat=inline \
     *   -cp "spigot-api.jar;asteroid-nms.jar" -d out BlinkAsteroidHostPlugin.java
     * base64 -w0 out/priv/seventeen/artist/blink/nms/host/BlinkAsteroidHostPlugin.class
     * </pre>
     * 使用 {@code -XDstringConcat=inline} 让字符串拼接走 StackBuilder（避免 invokedynamic
     * 对 StringConcatFactory 的引导依赖），使宿主类在全部受支持版本上字节码最简、最稳。
     */
    private const val HOST_PLUGIN_CLASS_BASE64 =
        "yv66vgAAAD0APQoAAgADBwAEDAAFAAYBACFvcmcvYnVra2l0L3BsdWdpbi9qYXZhL0phdmFQbHVnaW4BAAY8aW5pdD4BAAMoKVYKAAgACQcACgwACwAMAQAxcHJpdi9zZXZlbnRlZW4vYXJ0aXN0L2FzdGVyb2lkL2ludGVybmFsL05NU0xvYWRlcgEABGxvYWQBAB0oTG9yZy9idWtraXQvcGx1Z2luL1BsdWdpbjspVgoADgAPBwAQDAARABIBADxwcml2L3NldmVudGVlbi9hcnRpc3QvYmxpbmsvbm1zL2hvc3QvQmxpbmtBc3Rlcm9pZEhvc3RQbHVnaW4BAAlnZXRMb2dnZXIBABwoKUxqYXZhL3V0aWwvbG9nZ2luZy9Mb2dnZXI7BwAUAQAXamF2YS9sYW5nL1N0cmluZ0J1aWxkZXIKABMAAwgAFwEAGUFzdGVyb2lkIGhvc3QgbG9hZGVkIChNQyAKABMAGQwAGgAbAQAGYXBwZW5kAQAtKExqYXZhL2xhbmcvU3RyaW5nOylMamF2YS9sYW5nL1N0cmluZ0J1aWxkZXI7CgAdAB4HAB8MACAAIQEAKnByaXYvc2V2ZW50ZWVuL2FydGlzdC9hc3Rlcm9pZC9Bc3Rlcm9pZEFQSQEADGdldE1jVmVyc2lvbgEAFCgpTGphdmEvbGFuZy9TdHJpbmc7CAAjAQABKQoAEwAlDAAmACEBAAh0b1N0cmluZwoAKAApBwAqDAArACwBABhqYXZhL3V0aWwvbG9nZ2luZy9Mb2dnZXIBAARpbmZvAQAVKExqYXZhL2xhbmcvU3RyaW5nOylWBwAuAQATamF2YS9sYW5nL1Rocm93YWJsZQgAMAEAIkFzdGVyb2lkIGhvc3QgZmFpbGVkIHRvIGxvYWQgTk1TOiAKAC0AMgwAMwAhAQAKZ2V0TWVzc2FnZQoAKAA1DAA2ACwBAAZzZXZlcmUBAARDb2RlAQAPTGluZU51bWJlclRhYmxlAQAIb25FbmFibGUBAA1TdGFja01hcFRhYmxlAQAKU291cmNlRmlsZQEAHEJsaW5rQXN0ZXJvaWRIb3N0UGx1Z2luLmphdmEAIQAOAAIAAAAAAAIAAQAFAAYAAQA3AAAAHQABAAEAAAAFKrcAAbEAAAABADgAAAAGAAEAAAAMAAEAOQAGAAEANwAAAIgAAwACAAAARyq4AAcqtgANuwATWbcAFRIWtgAYuAActgAYEiK2ABi2ACS2ACenACFMKrYADbsAE1m3ABUSL7YAGCu2ADG2ABi2ACS2ADSxAAEAAAAlACgALQACADgAAAAaAAYAAAAQAAQAEQAlABQAKAASACkAEwBGABUAOgAAAAcAAmgHAC0dAAEAOwAAAAIAPA=="

    @Volatile
    private var hostClassLoader: ClassLoader? = null

    @Volatile
    private var asteroidVersion: String? = null

    /** 当前共享 Asteroid 版本，未初始化或失败时为 null */
    val currentVersion: String? get() = asteroidVersion

    /**
     * 获取或部署 BlinkAsteroidHost 插件，返回其 PluginClassLoader。
     * 调用线程安全，幂等。
     */
    @Synchronized
    fun acquire(plugin: JavaPlugin): ClassLoader? {
        hostClassLoader?.let { return it }

        // 1. 已经有别的 Blink 插件部署过 host
        val existing = try { Bukkit.getPluginManager().getPlugin(HOST_PLUGIN_NAME) } catch (_: Throwable) { null }
        if (existing != null && existing.isEnabled) {
            hostClassLoader = existing.javaClass.classLoader
            asteroidVersion = existing.description.version
            BlinkLog.detail("BlinkAsteroidHost 已存在 (v${asteroidVersion ?: "?"})，复用")
            return hostClassLoader
        }

        // 2. 当前插件作为部署者
        return ensureUpToDateAndLoad(plugin)
    }

    /** 检查/更新本地 BlinkAsteroidHost.jar，然后用 PluginManager.loadPlugin 加载它。 */
    private fun ensureUpToDateAndLoad(plugin: JavaPlugin): ClassLoader? {
        val sharedDir = File(plugin.dataFolder.parentFile, SHARED_DIR_NAME).apply { mkdirs() }
        val hostJar = File(sharedDir, HOST_JAR_NAME)

        val latest = resolveLatestAsteroidVersion()  // 仓库不可达返回 null
        val local = readEmbeddedVersion(hostJar)      // 文件不存在或无 plugin.yml 返回 null

        when {
            !hostJar.isFile && latest == null -> {
                BlinkLog.error("BlinkAsteroidHost 不可用：本地无缓存且 maven 仓库不可达")
                return null
            }
            !hostJar.isFile -> {
                BlinkLog.info("BlinkAsteroidHost 首次部署，下载 Asteroid v$latest")
                if (!downloadAndWrap(latest!!, sharedDir, hostJar, plugin)) return null
            }
            latest != null && latest != local -> {
                BlinkLog.info("BlinkAsteroidHost 升级 v${local ?: "?"} → v$latest")
                if (!downloadAndWrap(latest, sharedDir, hostJar, plugin)) {
                    BlinkLog.warn("升级失败，沿用本地 v${local ?: "?"}")
                }
            }
            latest == null -> {
                BlinkLog.detail("仓库不可达，使用本地 BlinkAsteroidHost v${local ?: "?"}")
            }
            else -> {
                BlinkLog.detail("BlinkAsteroidHost v${local ?: "?"} 已是最新")
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
            pm.enablePlugin(loaded)  // 宿主 onEnable 执行 NMSLoader.load(host)
            asteroidVersion = loaded.description.version
            hostClassLoader = loaded.javaClass.classLoader
            BlinkLog.success("BlinkAsteroidHost 已加载 (v$asteroidVersion, deployer=${plugin.name})")
            hostClassLoader
        } catch (e: Throwable) {
            BlinkLog.error("BlinkAsteroidHost 加载失败：${e.message}", e)
            null
        }
    }

    /** 下载 asteroid-nms-{version}.jar 到临时文件，wrap 成 host.jar 后清理临时文件。 */
    private fun downloadAndWrap(version: String, sharedDir: File, outJar: File, plugin: JavaPlugin): Boolean {
        val tempJar = File(sharedDir, "asteroid-nms-$version.tmp.jar")
        try {
            val ok = DependencyLoader.downloadDependencyInternal(
                DependencyLoader.Dependency("priv.seventeen.artist.asteroid", "asteroid-nms", version),
                tempJar, plugin
            )
            if (!ok) {
                BlinkLog.error("Asteroid v$version 下载失败")
                return false
            }
            wrapAsteroidIntoHostJar(tempJar, outJar, version)
            return true
        } catch (e: Throwable) {
            BlinkLog.error("Wrap BlinkAsteroidHost 失败", e)
            return false
        } finally {
            if (tempJar.exists()) tempJar.delete()
        }
    }

    /**
     * 把 asteroid-nms jar 重新打包成 BlinkAsteroidHost.jar：
     * 复制原 jar 所有 entry（跳过原 plugin.yml / META-INF/MANIFEST.MF），追加：
     * <ul>
     *   <li>{@code plugin.yml} —— 让 Bukkit 识别为插件</li>
     *   <li>{@code priv/seventeen/artist/blink/nms/host/BlinkAsteroidHostPlugin.class} —— 插件入口类</li>
     *   <li>{@code META-INF/MANIFEST.MF} —— 简洁版</li>
     * </ul>
     */
    private fun wrapAsteroidIntoHostJar(asteroidJar: File, outJar: File, version: String) {
        val tmpOut = File(outJar.parentFile, "${outJar.name}.tmp")
        ZipOutputStream(tmpOut.outputStream().buffered()).use { zos ->
            // 复制原 asteroid-nms.jar 所有 entry，跳过我们要替换的
            ZipInputStream(asteroidJar.inputStream().buffered()).use { zis ->
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
            zos.write("Manifest-Version: 1.0\r\nCreated-By: Blink AsteroidSharedHost\r\n\r\n".toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            // 追加 plugin.yml
            zos.putNextEntry(ZipEntry("plugin.yml"))
            zos.write(buildPluginYml(version).toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            // 追加 host plugin class
            zos.putNextEntry(ZipEntry(hostClassPath))
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
        append("main: ").append(hostMainClass).append('\n')
        // Asteroid 支持 1.18.2+，api-version 取下界 1.18 以便在全部受支持服务端加载
        append("api-version: '1.18'\n")
        append("description: 'Asteroid NMS host (auto-deployed by Blink)'\n")
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

    /** Asteroid metadata 固定从 hosted 仓库获取，避免 group 仓库缓存不同步 */
    private const val ASTEROID_METADATA_URL =
        "https://repo.arcartx.com/repository/maven-releases/priv/seventeen/artist/asteroid/asteroid-nms/maven-metadata.xml"

    /**
     * 从 maven 仓库 maven-metadata.xml 解析 asteroid-nms 最新 release 版本。
     * 固定从 hosted 仓库 (maven-releases) 获取，避免 Nexus group 缓存不同步。
     * 失败返回 null（由调用方决定是用本地缓存还是报错）。
     */
    private fun resolveLatestAsteroidVersion(): String? {
        try {
            val text = DependencyLoader.fetchTextInternal(ASTEROID_METADATA_URL) ?: return null
            val v = RELEASE_PATTERN.find(text)?.groupValues?.getOrNull(1)
                ?: LATEST_PATTERN.find(text)?.groupValues?.getOrNull(1)
            if (!v.isNullOrBlank()) {
                BlinkLog.detail("Asteroid 最新版本：${v.trim()} (from maven-releases)")
                return v.trim()
            }
        } catch (_: Throwable) { /* 仓库不可达 */ }
        return null
    }
}
