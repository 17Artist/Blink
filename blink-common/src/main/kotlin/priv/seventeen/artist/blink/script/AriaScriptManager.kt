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

import priv.seventeen.artist.aria.Aria
import priv.seventeen.artist.aria.api.AriaCompiledRoutine
import priv.seventeen.artist.aria.callable.NativeCallable
import priv.seventeen.artist.aria.context.Context
import priv.seventeen.artist.aria.context.VariableKey
import priv.seventeen.artist.aria.value.IValue
import priv.seventeen.artist.blink.BlinkLog
import java.io.File

/**
 * Aria 脚本引擎管理器。
 *
 * <p>blink-common 通过 compileOnly 依赖 Aria，运行时由 DependencyLoader.loadAria() 自动下载并注入 classpath。
 * 当 {@code enableAria = true} 时，生成的主类在 onLoad 先调用 loadAria() 下载注入，
 * 再调用 {@link #init()}，onDisable 调用 {@link #shutdown()}。
 */
object AriaScriptManager {

    @Volatile
    private var initialized = false

    /** Aria 引擎是否可用 */
    val isAvailable: Boolean get() = initialized

    /**
     * 初始化 Aria 引擎。
     * 由 BlinkGeneratedMain.onLoad() 自动调用，不需要手动调用。
     */
    fun init() {
        try {
            Aria.getEngine()
            initialized = true
            BlinkLog.success("Aria 脚本引擎已初始化")
        } catch (e: Exception) {
            BlinkLog.error("Aria 初始化失败", e)
            initialized = false
        }
    }

    /**
     * 关闭 Aria 引擎。
     * 由 BlinkGeneratedMain.onDisable() 自动调用，不需要手动调用。
     */
    fun shutdown() {
        initialized = false
        BlinkLog.info("Aria 脚本引擎已关闭")
    }

    /**
     * 创建一个新的 Aria 执行上下文。
     *
     * @return 新的 Context 实例
     */
    fun createContext(): Context {
        checkAvailable()
        return Aria.createContext()
    }

    /**
     * 执行 Aria 脚本代码。
     *
     * @param code    Aria 脚本代码
     * @param context 执行上下文，为 null 时自动创建
     * @return 脚本执行结果
     */
    fun eval(code: String, context: Context? = null): IValue<*> {
        checkAvailable()
        return Aria.eval(code, context ?: createContext())
    }

    /**
     * 执行 Aria 脚本代码，将 bindings 作为 global 变量注入。
     * 脚本中通过 {@code global.key} 访问注入的值。
     *
     * @param code     Aria 脚本代码
     * @param bindings 要注入的变量，key 为变量名，value 会自动转换为 Aria 值类型
     * @return 脚本执行结果
     */
    fun eval(code: String, bindings: Map<String, Any?>): IValue<*> {
        checkAvailable()
        val ctx = createContext()
        val gs = ctx.globalStorage
        for ((key, value) in bindings) {
            gs.getGlobalVariable(VariableKey.of(key)).setValue(NativeCallable.wrapObject(value))
        }
        return Aria.eval(code, ctx)
    }

    /**
     * 执行脚本文件。
     *
     * @param file     脚本文件（.aria 或 .js）
     * @param bindings 要注入的 global 变量
     * @return 脚本执行结果
     */
    fun evalFile(file: File, bindings: Map<String, Any?> = emptyMap()): IValue<*> {
        return eval(file.readText(Charsets.UTF_8), bindings)
    }

    /**
     * 预编译脚本为可复用的 routine。
     * 编译结果可对不同 Context 多次执行，适合热路径。
     *
     * @param name 脚本名称（.js 后缀自动启用 JavaScript 兼容模式）
     * @param code 脚本代码
     * @return 编译后的 routine
     */
    fun compile(name: String, code: String): AriaCompiledRoutine {
        checkAvailable()
        return Aria.compile(name, code)
    }

    private fun checkAvailable() {
        if (!initialized) throw IllegalStateException("Aria 脚本引擎未初始化，请确认 enableAria 已开启")
    }
}
