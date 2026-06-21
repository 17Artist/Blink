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

import org.bukkit.plugin.java.JavaPlugin
import priv.seventeen.artist.asteroid.AsteroidAPI
import priv.seventeen.artist.blink.BlinkLog

/**
 * Asteroid NMS 桥接管理器（共享中间件入口，与 AriaScriptManager 同模型）。
 *
 * <p>blink-common 通过 compileOnly 依赖 asteroid-nms；运行时 {@code init(plugin)} 委托
 * {@link AsteroidSharedHost#acquire} 部署/复用全 JVM 唯一的 {@code BlinkAsteroidHost} 插件。
 * 宿主 onEnable 内执行 {@code NMSLoader.load(host)}，将全局数据包注入 + Bukkit 事件监听绑定到
 * <b>长生命周期的宿主</b>，因此单个消费插件 disable 不会拆掉它。
 *
 * <p>消费插件直接使用 {@code priv.seventeen.artist.asteroid.*} 类型；如需自定义数据包监听，
 * 经 {@code AsteroidAPI.addPacketListener(thisPlugin, ...)} 注册，并在自身 onDisable 调用
 * {@code AsteroidAPI.removePacketListener(thisPlugin, ...)} 清理（框架不代管）。
 */
object AsteroidManager {

    @Volatile
    private var initialized = false

    @Volatile
    private var sharedCL: ClassLoader? = null

    /** Asteroid 是否可用 */
    val isAvailable: Boolean get() = initialized

    /** 当前共享 Asteroid 版本，未初始化或失败时为 null */
    val version: String? get() = AsteroidSharedHost.currentVersion

    /**
     * 暴露共享 ClassLoader，供需要动态加载 Asteroid 扩展类型的场景使用。
     */
    val sharedClassLoader: ClassLoader? get() = sharedCL

    /**
     * 初始化 Asteroid NMS 桥接。由 BlinkGeneratedMain.onLoad() 自动调用，不需要手动调用。
     *
     * @param plugin 当前插件，用于触发 [AsteroidSharedHost] 选举宿主或加入客户
     */
    fun init(plugin: JavaPlugin) {
        if (initialized) return
        try {
            val cl = AsteroidSharedHost.acquire(plugin)
            if (cl == null) {
                BlinkLog.error("Asteroid 共享 ClassLoader 获取失败，NMS 不可用")
                initialized = false
                return
            }
            sharedCL = cl
            initialized = true
            BlinkLog.success("Asteroid NMS 桥接已就绪 (MC ${AsteroidAPI.getMcVersion()}, v${AsteroidSharedHost.currentVersion ?: "?"})")
        } catch (e: Throwable) {
            BlinkLog.error("Asteroid 初始化失败", e)
            initialized = false
        }
    }

    /**
     * 关闭 Asteroid NMS 桥接。由 BlinkGeneratedMain.onDisable() 自动调用。
     *
     * <p>共享宿主与全局数据包注入由 BlinkAsteroidHost 持有、跨插件存活，绝不在此销毁；
     * 仅释放本插件本地引用。本插件注册的 packet listener 需由用户在 onDisable 自行 removePacketListener。
     */
    fun shutdown() {
        initialized = false
        BlinkLog.detail("Asteroid 本地引用已释放（共享 host 继续保留）")
    }

    /**
     * 获取 Asteroid API 入口。
     *
     * @return AsteroidAPI class（通过其静态方法访问各功能）
     * @throws IllegalStateException 如果 Asteroid 未初始化
     */
    fun api(): Class<AsteroidAPI> {
        checkAvailable()
        return AsteroidAPI::class.java
    }

    private fun checkAvailable() {
        if (!initialized) throw IllegalStateException("Asteroid NMS 桥接未初始化，请确认 enableAsteroid 已开启")
    }
}
