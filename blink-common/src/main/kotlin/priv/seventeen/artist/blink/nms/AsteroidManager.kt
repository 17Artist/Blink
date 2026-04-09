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
import priv.seventeen.artist.asteroid.internal.NMSLoader
import priv.seventeen.artist.blink.BlinkLog

/**
 * Asteroid NMS 桥接管理器。
 *
 * <p>blink-common 通过 compileOnly 依赖 asteroid-api，运行时由 DependencyLoader.loadAsteroid() 自动下载
 * asteroid-nms (shadow jar) 并注入 classpath。
 * 当 {@code enableAsteroid = true} 时，生成的主类在 onLoad 先调用 loadAsteroid() 下载注入，
 * 再调用 {@link #init}，onDisable 调用 {@link #shutdown}。
 */
object AsteroidManager {

    @Volatile
    private var initialized = false

    /** Asteroid 是否可用 */
    val isAvailable: Boolean get() = initialized

    /**
     * 初始化 Asteroid NMS 桥接。
     * 由 BlinkGeneratedMain.onLoad() 自动调用，不需要手动调用。
     *
     * @param plugin 插件实例，用于注册数据包监听等
     */
    fun init(plugin: JavaPlugin) {
        try {
            NMSLoader.load(plugin)
            initialized = true
            BlinkLog.success("Asteroid NMS 桥接已初始化 (MC ${AsteroidAPI.getMcVersion()})")
        } catch (e: Exception) {
            BlinkLog.error("Asteroid 初始化失败", e)
            initialized = false
        }
    }

    /**
     * 关闭 Asteroid NMS 桥接。
     * 由 BlinkGeneratedMain.onDisable() 自动调用，不需要手动调用。
     */
    fun shutdown() {
        initialized = false
        BlinkLog.info("Asteroid NMS 桥接已关闭")
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
