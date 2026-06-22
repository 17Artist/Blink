package com.example.testplugin.service

import priv.seventeen.artist.aria.Aria
import priv.seventeen.artist.blink.bukkitPlugin
import priv.seventeen.artist.blink.script.AriaScriptManager

/**
 * Aria 脚本用法示例。
 *
 * 自 Blink 1.4.0 起 Aria 改为编译期直接依赖、去掉反射包装层：
 * `AriaScriptManager` 只管生命周期（无 eval/compile），执行脚本直接用
 * `priv.seventeen.artist.aria.Aria` 的静态方法，先用 `isAvailable` 守门。
 */
object AriaScriptBridge {

    val isAvailable: Boolean get() = AriaScriptManager.isAvailable

    fun init() {
        if (!isAvailable) {
            bukkitPlugin.logger.warning("[Aria] Aria engine not available")
            return
        }
        bukkitPlugin.logger.info("[Aria] Aria engine ready")

        // 一次性 eval：结果是 IValue<?>，用 jvmValue()/stringValue()/numberValue() 取 JVM 值
        val sum = Aria.eval("1 + 2 + 3", Aria.createContext()).jvmValue()
        bukkitPlugin.logger.info("[Aria] eval('1 + 2 + 3') = $sum")

        // compile 一次、execute 多次；每次用独立 Context 做上下文隔离
        val routine = Aria.compile("greeting", "'Hello, ' + 'Blink' + '!'")
        val greeting = routine.execute(Aria.createContext()).stringValue()
        bukkitPlugin.logger.info("[Aria] compiled greeting = $greeting")
    }

    /** 一次性执行脚本，返回 JVM 值；不可用或出错返回 null。 */
    fun eval(code: String): Any? {
        if (!isAvailable) return null
        return try {
            Aria.eval(code, Aria.createContext()).jvmValue()
        } catch (e: Exception) {
            bukkitPlugin.logger.warning("[Aria] eval failed: ${e.message}")
            null
        }
    }

    /** 需要动态值时，把值拼进脚本源码（注意转义，避免脚本注入）。 */
    fun welcomeMessage(playerName: String): String? {
        if (!isAvailable) return null
        val safe = playerName.replace("'", "")
        return try {
            Aria.eval("'Hello, ' + '$safe' + '! Welcome to the server.'", Aria.createContext()).stringValue()
        } catch (e: Exception) {
            bukkitPlugin.logger.warning("[Aria] welcomeMessage failed: ${e.message}")
            null
        }
    }

    fun shutdown() {
        // AriaScriptManager.shutdown() 由生成的主类自动调用；这里做业务层清理
    }
}
