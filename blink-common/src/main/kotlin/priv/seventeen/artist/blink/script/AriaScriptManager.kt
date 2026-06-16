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

import org.bukkit.plugin.java.JavaPlugin
import priv.seventeen.artist.aria.Aria
import priv.seventeen.artist.blink.BlinkLog
import priv.seventeen.artist.blink.aria.AriaSharedHost

/**
 * Aria 脚本引擎管理器（共享中间件入口）。
 *
 * <p>自 Blink 1.4.0 起，Aria 作为编译期依赖直接引入，不再使用反射。
 * 本对象仅负责生命周期管理（确保 AriaSharedHost 部署和引擎初始化），
 * 调用方直接使用 {@code priv.seventeen.artist.aria.*} 类型即可。
 *
 * <h3>使用方式</h3>
 * <pre>
 * // 编译并执行脚本
 * val routine = Aria.compile("myScript", code)
 * val ctx = Aria.createContext()
 * val result = routine.execute(ctx)
 *
 * // 直接 eval
 * Aria.eval(code, ctx)
 * </pre>
 */
object AriaScriptManager {

    @Volatile
    private var initialized = false

    @Volatile
    private var sharedCL: ClassLoader? = null

    /** Aria 引擎是否可用 */
    val isAvailable: Boolean get() = initialized

    /** 当前共享 Aria 版本，未初始化或失败时为 null */
    val version: String? get() = AriaSharedHost.currentVersion

    /**
     * 暴露共享 ClassLoader，供需要动态加载 Aria 扩展类型的场景使用。
     */
    val sharedClassLoader: ClassLoader? get() = sharedCL

    /**
     * 初始化 Aria 引擎。由 BlinkGeneratedMain.onLoad() 自动调用。
     *
     * @param plugin 当前插件，用于触发 [AriaSharedHost] 选举宿主或加入客户
     */
    fun init(plugin: JavaPlugin) {
        if (initialized) return
        try {
            val cl = AriaSharedHost.acquire(plugin)
            if (cl == null) {
                BlinkLog.error("Aria 共享 ClassLoader 获取失败，引擎不可用")
                initialized = false
                return
            }
            sharedCL = cl
            // 确保引擎初始化完成
            Aria.getEngine()
            initialized = true
            BlinkLog.success("Aria 脚本引擎已就绪 (v${AriaSharedHost.currentVersion ?: "?"})")
        } catch (e: Throwable) {
            BlinkLog.error("Aria 初始化失败", e)
            initialized = false
        }
    }

    /** 关闭 Aria 引擎。由 BlinkGeneratedMain.onDisable() 自动调用。 */
    fun shutdown() {
        initialized = false
        BlinkLog.detail("Aria 引擎本地引用已释放（共享实例继续保留）")
    }
}
