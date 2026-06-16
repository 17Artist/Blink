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
import priv.seventeen.artist.blink.BlinkLog
import priv.seventeen.artist.blink.aria.AriaSharedHost
import java.io.File
import java.lang.reflect.Method

/**
 * Aria 脚本引擎管理器（共享中间件入口）。
 *
 * <p>自 Blink 1.3.0 起，Aria 由 {@link AriaSharedHost} 在第一个加载且需要 Aria 的 Blink 插件
 * onLoad 时部署为独立的 Bukkit 插件 {@code BlinkAriaHost}，所有插件（包括非 Blink 插件）
 * 共享同一份 Aria 实例。
 *
 * <h3>共享语义</h3>
 * <ul>
 *   <li>{@code Aria.DEFAULT_ENGINE} 全局唯一</li>
 *   <li>{@code CallableManager.INSTANCE} 全局唯一。任一插件注册到 CallableManager 的
 *       namespace、constructor、object function 对所有插件可见。</li>
 *   <li>{@code AnnotationRegistry} 同理共享。</li>
 * </ul>
 *
 * <h3>API 类型</h3>
 * 因为本对象不再编译期依赖 Aria（避免 PluginClassLoader 类型耦合），公开方法签名只使用 JDK
 * 中性类型：输入 {@link String}/{@link Map}/{@link File}，输出 {@link Any}（已 unwrap 为
 * Java 原生类型：String/Number/Boolean/Map/List/null/对象）。
 *
 * <h3>非 Blink 插件接入</h3>
 * 任意 Bukkit 插件可在 plugin.yml 中加 {@code depend: [BlinkAriaHost]}，然后正常 import
 * {@code priv.seventeen.artist.aria.*} 即可。前提是服务器中至少有一个启用 Aria 的 Blink 插件，
 * 否则 BlinkAriaHost 不会被部署。
 */
object AriaScriptManager {

    @Volatile
    private var initialized = false

    @Volatile
    private var sharedCL: ClassLoader? = null

    // 反射缓存
    @Volatile private var ariaClass: Class<*>? = null
    @Volatile private var contextClass: Class<*>? = null
    @Volatile private var iValueClass: Class<*>? = null
    @Volatile private var nativeCallableClass: Class<*>? = null
    @Volatile private var variableKeyClass: Class<*>? = null
    @Volatile private var globalStorageClass: Class<*>? = null

    @Volatile private var mGetEngine: Method? = null
    @Volatile private var mCreateContext: Method? = null
    @Volatile private var mEvalCodeContext: Method? = null
    @Volatile private var mCompileNameCode: Method? = null
    @Volatile private var mWrapObject: Method? = null
    @Volatile private var mVariableKeyOf: Method? = null
    @Volatile private var mGetGlobalStorage: Method? = null
    @Volatile private var mGetGlobalVariable: Method? = null
    @Volatile private var mDeclareGlobalVariable: Method? = null
    @Volatile private var mVariableSetValue: Method? = null
    @Volatile private var mIValueJvmValue: Method? = null
    @Volatile private var mEngineGetContext: Method? = null

    /** Aria 引擎是否可用 */
    val isAvailable: Boolean get() = initialized

    /** 当前共享 Aria 版本，未初始化或失败时为 null */
    val version: String? get() = AriaSharedHost.currentVersion

    /**
     * 暴露共享 ClassLoader，业务层在需要直接反射 Aria 类型（如自定义 NativeCallable 注册）时使用。
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
            resolveReflection(cl)
            // 触发 Aria.getEngine()，确保 DEFAULT_ENGINE.initialize() 完成
            mGetEngine?.invoke(null)
            initialized = true
            BlinkLog.success("Aria 脚本引擎已就绪 (v${AriaSharedHost.currentVersion ?: "?"})")
        } catch (e: Throwable) {
            BlinkLog.error("Aria 初始化失败", unwrap(e))
            initialized = false
        }
    }

    /** 关闭 Aria 引擎。由 BlinkGeneratedMain.onDisable() 自动调用。 */
    fun shutdown() {
        // 共享 host 跨插件存活，单插件 disable 不应卸载共享 ClassLoader。
        // 只重置本插件视角的初始化标记。
        initialized = false
        BlinkLog.detail("Aria 引擎本地引用已释放（共享实例继续保留）")
    }

    /**
     * 创建一个新的 Aria 执行上下文。
     *
     * @return 不透明的 Context 句柄；只能传给本类的其它 eval 重载使用
     */
    fun createContext(): Any {
        checkAvailable()
        return mCreateContext!!.invoke(null)
            ?: throw IllegalStateException("Aria.createContext() 返回 null")
    }

    /**
     * 执行 Aria 脚本代码。
     *
     * @param code 脚本源码
     * @param contextHandle 由 [createContext] 返回的 context 句柄；为 null 时自动创建
     * @return 脚本结果（已 unwrap 为 JDK 类型：String/Number/Boolean/Map/List/null/对象）
     */
    fun eval(code: String, contextHandle: Any? = null): Any? {
        checkAvailable()
        val ctx = contextHandle ?: createContext()
        val result = try {
            mEvalCodeContext!!.invoke(null, code, ctx)
        } catch (e: Throwable) {
            throw unwrap(e)
        }
        return unwrapValue(result)
    }

    /**
     * 执行 Aria 脚本代码，将 bindings 作为 global 变量注入。
     * 脚本中通过 {@code global.key} 访问注入的值。
     */
    fun eval(code: String, bindings: Map<String, Any?>): Any? {
        checkAvailable()
        val ctx = createContext()
        injectBindings(ctx, bindings)
        return eval(code, ctx)
    }

    /**
     * 在引擎全局上下文中执行代码。全局上下文继承 CallableManager 注册的所有命名空间
     * （如 {@code symphony.*}），适合 @derive/@formula 等需要访问桥接函数的场景。
     *
     * <p>注意：全局上下文是共享的，并发场景应使用 [evalWithArgs] 代替。
     *
     * @param code 脚本源码
     * @param bindings 注入到全局上下文的临时变量
     * @return 脚本结果（已 unwrap 为 JDK 类型）
     */
    fun evalInGlobal(code: String, bindings: Map<String, Any?> = emptyMap()): Any? {
        checkAvailable()
        val ctx = getGlobalContext()
        if (bindings.isNotEmpty()) {
            injectBindings(ctx, bindings)
        }
        val result = try {
            mEvalCodeContext!!.invoke(null, code, ctx)
        } catch (e: Throwable) {
            throw unwrap(e)
        }
        return unwrapValue(result)
    }

    /**
     * 执行带参数的脚本代码。将代码包装为立即调用的闭包，参数通过闭包形参传入，
     * 彻底绕过上下文变量系统的限制。
     *
     * <p>等效于在 Aria 中执行：{@code ((arg0, arg1, ...) -> { <code> })(val0, val1, ...)}
     *
     * <p>脚本内可直接使用参数名访问值，同时全局命名空间（{@code symphony.*} 等）可用。
     *
     * @param code 脚本函数体（不含外层 fun/lambda 声明）
     * @param args 有序参数列表，key=参数名，value=参数值
     * @return 脚本结果（已 unwrap 为 JDK 类型）
     */
    fun evalWithArgs(code: String, args: Map<String, Any?>): Any? {
        checkAvailable()
        if (args.isEmpty()) return evalInGlobal(code)

        // 构建闭包包装：((p1, p2) -> { <code> })(v1, v2)
        val paramNames = args.keys.joinToString(", ")
        val placeholders = args.keys.mapIndexed { i, _ -> "__blink_arg_$i" }.joinToString(", ")
        val wrappedCode = "(($paramNames) -> { $code })($placeholders)"

        // 将实际参数值通过 bindings 注入为临时变量 __blink_arg_N
        val tempBindings = mutableMapOf<String, Any?>()
        args.values.forEachIndexed { i, value -> tempBindings["__blink_arg_$i"] = value }

        val ctx = getGlobalContext()
        injectBindings(ctx, tempBindings)
        val result = try {
            mEvalCodeContext!!.invoke(null, wrappedCode, ctx)
        } catch (e: Throwable) {
            throw unwrap(e)
        }
        return unwrapValue(result)
    }

    /** 执行脚本文件。 */
    fun evalFile(file: File, bindings: Map<String, Any?> = emptyMap()): Any? {
        return eval(file.readText(Charsets.UTF_8), bindings)
    }

    /**
     * 预编译脚本。返回的 [CompiledScript] 可对不同 bindings 多次执行。
     */
    fun compile(name: String, code: String): CompiledScript {
        checkAvailable()
        val routine = try {
            mCompileNameCode!!.invoke(null, name, code)
        } catch (e: Throwable) {
            throw unwrap(e)
        } ?: throw IllegalStateException("Aria.compile 返回 null")
        return CompiledScript(routine, this)
    }

    // -------- 内部 API：供 CompiledScript 复用 --------

    /**
     * 获取引擎全局上下文。优先使用 Engine.getContext()，若不可用则回退到 Aria.createContext()。
     */
    private fun getGlobalContext(): Any {
        val engine = mGetEngine?.invoke(null)
        if (engine != null && mEngineGetContext != null) {
            val ctx = mEngineGetContext!!.invoke(engine)
            if (ctx != null) return ctx
        }
        // 回退：创建新上下文（功能受限，但至少不会 NPE）
        return createContext()
    }

    internal fun executeRoutine(routine: Any, bindings: Map<String, Any?>): Any? {
        val ctx = createContext()
        injectBindings(ctx, bindings)
        val executeMethod = routine.javaClass.getMethod("execute", contextClass)
        val raw = try {
            executeMethod.invoke(routine, ctx)
        } catch (e: Throwable) {
            throw unwrap(e)
        }
        return unwrapValue(raw)
    }

    internal fun injectBindings(ctx: Any, bindings: Map<String, Any?>) {
        if (bindings.isEmpty()) return
        val gs = mGetGlobalStorage!!.invoke(ctx)
        for ((key, value) in bindings) {
            val varKey = mVariableKeyOf!!.invoke(null, key)
            // 尝试获取已有变量；不存在时尝试声明新变量
            var variable = mGetGlobalVariable!!.invoke(gs, varKey)
            if (variable == null && mDeclareGlobalVariable != null) {
                mDeclareGlobalVariable!!.invoke(gs, varKey)
                variable = mGetGlobalVariable!!.invoke(gs, varKey)
            }
            if (variable == null) continue
            val wrapped = mWrapObject!!.invoke(null, value)
            // 使用缓存的 setValue 方法或按需查找
            val setValueM = mVariableSetValue
                ?: variable.javaClass.methods.firstOrNull {
                    it.name == "setValue" && it.parameterCount == 1
                }?.also { mVariableSetValue = it }
                ?: continue
            try {
                setValueM.invoke(variable, wrapped)
            } catch (_: Throwable) {
                // 类型不匹配时 fallback：按需查找当前实例的 setValue
                val fallbackM = variable.javaClass.methods.firstOrNull {
                    it.name == "setValue" && it.parameterCount == 1
                } ?: continue
                fallbackM.invoke(variable, wrapped)
            }
        }
    }

    // -------- 反射工具 --------

    private fun resolveReflection(cl: ClassLoader) {
        val aria = Class.forName("priv.seventeen.artist.aria.Aria", true, cl)
        val context = Class.forName("priv.seventeen.artist.aria.context.Context", true, cl)
        val iValue = Class.forName("priv.seventeen.artist.aria.value.IValue", true, cl)
        val nativeCallable = Class.forName("priv.seventeen.artist.aria.callable.NativeCallable", true, cl)
        val variableKey = Class.forName("priv.seventeen.artist.aria.context.VariableKey", true, cl)
        val globalStorage = Class.forName("priv.seventeen.artist.aria.context.GlobalStorage", true, cl)

        ariaClass = aria
        contextClass = context
        iValueClass = iValue
        nativeCallableClass = nativeCallable
        variableKeyClass = variableKey
        globalStorageClass = globalStorage

        mGetEngine = aria.getMethod("getEngine")
        mCreateContext = aria.getMethod("createContext")
        mEvalCodeContext = aria.getMethod("eval", String::class.java, context)
        mCompileNameCode = aria.getMethod("compile", String::class.java, String::class.java)
        mWrapObject = nativeCallable.getMethod("wrapObject", Any::class.java)
        mVariableKeyOf = variableKey.getMethod("of", String::class.java)
        mGetGlobalStorage = context.getMethod("getGlobalStorage")
        mGetGlobalVariable = globalStorage.getMethod("getGlobalVariable", variableKey)
        mIValueJvmValue = iValue.getMethod("jvmValue")

        // Aria 1.1.1+ 可选 API：声明新全局变量
        mDeclareGlobalVariable = try {
            globalStorage.getMethod("declareGlobalVariable", variableKey)
        } catch (_: NoSuchMethodException) {
            // 尝试备选方法名
            try {
                globalStorage.getMethod("declare", variableKey)
            } catch (_: NoSuchMethodException) {
                null
            }
        }

        // 引擎全局上下文：Engine.getContext() 或 Engine.getGlobalContext()
        val engineInstance = mGetEngine!!.invoke(null)
        if (engineInstance != null) {
            mEngineGetContext = engineInstance.javaClass.methods.firstOrNull {
                it.name in setOf("getContext", "getGlobalContext", "getRootContext")
                        && it.parameterCount == 0
                        && context.isAssignableFrom(it.returnType)
            }
        }
    }

    /** 把 Aria IValue 解包为 JDK 类型；非 IValue 原样返回。 */
    private fun unwrapValue(raw: Any?): Any? {
        if (raw == null) return null
        val ivCls = iValueClass ?: return raw
        if (!ivCls.isInstance(raw)) return raw
        return try {
            mIValueJvmValue!!.invoke(raw)
        } catch (_: Throwable) {
            raw.toString()
        }
    }

    /** 解包 InvocationTargetException 等反射包装异常 */
    private fun unwrap(t: Throwable): Throwable {
        var cur: Throwable = t
        while (cur is java.lang.reflect.InvocationTargetException || cur is java.lang.reflect.UndeclaredThrowableException) {
            val cause = cur.cause ?: break
            cur = cause
        }
        return cur
    }

    private fun checkAvailable() {
        if (!initialized) throw IllegalStateException("Aria 脚本引擎未初始化，请确认 enableAria 已开启")
    }
}

/**
 * 预编译脚本句柄。可对不同 bindings 多次执行。
 *
 * <p>每次 execute 内部会创建新 context 并注入 bindings，互不干扰；适合无状态热路径。
 */
class CompiledScript internal constructor(
    private val routine: Any,
    private val mgr: AriaScriptManager
) {
    /** 用空 bindings 执行 */
    fun execute(): Any? = execute(emptyMap())

    /** 用指定 bindings 执行，返回已 unwrap 的 JDK 类型结果 */
    fun execute(bindings: Map<String, Any?>): Any? = mgr.executeRoutine(routine, bindings)
}
