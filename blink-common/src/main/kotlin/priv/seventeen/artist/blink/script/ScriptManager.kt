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
package priv.seventeen.artist.blink.script

import priv.seventeen.artist.blink.BlinkLog
import priv.seventeen.artist.blink.bukkitPlugin
import java.io.File
import java.net.URLClassLoader
import java.util.*
import javax.script.ScriptEngine
import javax.script.ScriptEngineFactory

object ScriptManager {

    @Volatile
    private var engineClassLoader: URLClassLoader? = null
    @Volatile
    private var defaultEngine: ScriptEngine? = null

    fun init(nashornJars: List<File>) {
        if (nashornJars.isEmpty()) {
            BlinkLog.warn("Nashorn JAR 文件为空，JS 引擎不可用")
            return
        }

        val urls = nashornJars.map { it.toURI().toURL() }.toTypedArray()
        val cl = URLClassLoader(urls, bukkitPlugin.javaClass.classLoader)

        val factory = resolveNashornFactory(cl)
        if (factory == null) {
            BlinkLog.warn("未找到 Nashorn 引擎，JS 功能不可用（该服务端可能不支持，如 Forge/Mohist 混合端）")
            try { cl.close() } catch (_: Exception) {}
            return
        }

        try {
            defaultEngine = factory.scriptEngine   // 引擎实例化在这里；失败会抛，捕获后打印真实原因
            engineClassLoader = cl
            BlinkLog.success("JS 引擎已初始化: ${factory.engineName} ${factory.engineVersion}")
        } catch (e: Throwable) {
            BlinkLog.error("Nashorn 引擎实例化失败（可能是服务端/JDK 兼容问题）", e)
            try { cl.close() } catch (_: Exception) {}
        }
    }

    /** 候选 Nashorn 工厂类名：独立 nashorn-core（org.openjdk）优先，其次旧 JDK 内置（jdk.nashorn）。 */
    private val NASHORN_FACTORY_CLASSES = listOf(
        "org.openjdk.nashorn.api.scripting.NashornScriptEngineFactory",
        "jdk.nashorn.api.scripting.NashornScriptEngineFactory"
    )

    /**
     * 解析 Nashorn 工厂。
     *
     * 1. 先走标准 [ServiceLoader]（Paper / Spigot 等普通端有效）。
     * 2. 失败则回退为按已知类名直接实例化 —— **绕过 ServiceLoader 的 `META-INF/services` 发现机制**。
     *    这是 Forge / Mohist 等混合端的关键：ModLauncher 的类加载器下 ServiceLoader 常常
     *    发现不到 provider，但类本身在 nashorn-core jar 里、可直接 `Class.forName` 加载。
     */
    private fun resolveNashornFactory(cl: ClassLoader): ScriptEngineFactory? {
        try {
            ServiceLoader.load(ScriptEngineFactory::class.java, cl)
                .firstOrNull { it.engineName.contains("nashorn", ignoreCase = true) }
                ?.let { return it }
        } catch (e: Throwable) {
            BlinkLog.detail("ServiceLoader 未能定位 Nashorn（${e.javaClass.simpleName}），改用直接实例化")
        }
        for (name in NASHORN_FACTORY_CLASSES) {
            try {
                val c = Class.forName(name, true, cl)
                return c.getDeclaredConstructor().newInstance() as ScriptEngineFactory
            } catch (_: ClassNotFoundException) {
                // 试下一个候选类名
            } catch (e: Throwable) {
                BlinkLog.warn("Nashorn 工厂 $name 实例化失败：${e.message}")
            }
        }
        return null
    }

    val isAvailable: Boolean get() = defaultEngine != null

    fun getEngine(): ScriptEngine {
        return defaultEngine ?: throw IllegalStateException("JS 引擎未初始化，请确认 enableScript 已开启")
    }

    /** 创建独立引擎实例，用于隔离执行环境。JS 不可用时抛出清晰的 IllegalStateException（而非难懂的 NoSuchElementException）。 */
    fun createEngine(): ScriptEngine {
        val cl = engineClassLoader
            ?: throw IllegalStateException("JS 引擎不可用：未初始化或当前服务端无 Nashorn（如 Forge/Mohist 混合端）。调用前请先判断 ScriptManager.isAvailable")
        return (resolveNashornFactory(cl)
            ?: throw IllegalStateException("未找到 Nashorn 引擎，JS 功能不可用（该服务端可能不支持）")).scriptEngine
    }

    /** 共享 defaultEngine，线程安全通过 synchronized 保证；如需并发请用 [createEngine] */
    fun eval(script: String, bindings: Map<String, Any?> = emptyMap()): Any? {
        val eng = getEngine()
        val ctx = eng.createBindings()
        ctx.putAll(bindings)
        return synchronized(eng) { eng.eval(script, ctx) }
    }

    fun evalFile(file: File, bindings: Map<String, Any?> = emptyMap()): Any? {
        return eval(file.readText(Charsets.UTF_8), bindings)
    }

    fun shutdown() {
        defaultEngine = null
        try {
            engineClassLoader?.close()
        } catch (e: Exception) {
            BlinkLog.error("关闭 JS 引擎 ClassLoader 时出错", e)
        }
        engineClassLoader = null
    }
}
