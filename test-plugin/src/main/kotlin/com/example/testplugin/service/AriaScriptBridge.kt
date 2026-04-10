package com.example.testplugin.service

import priv.seventeen.artist.blink.bukkitPlugin
import priv.seventeen.artist.blink.script.AriaScriptManager

object AriaScriptBridge {

    val isAvailable: Boolean get() = AriaScriptManager.isAvailable

    fun init() {
        if (!AriaScriptManager.isAvailable) {
            bukkitPlugin.logger.warning("[Aria] Aria engine not available")
            return
        }

        bukkitPlugin.logger.info("[Aria] Aria engine ready")

        // 基本执行
        val result = AriaScriptManager.eval("1 + 2 + 3")
        bukkitPlugin.logger.info("[Aria] eval('1 + 2 + 3') = $result")

        // 带变量注入
        val greeting = AriaScriptManager.eval("msg + name", mapOf("msg" to "Hello ", "name" to "Blink"))
        bukkitPlugin.logger.info("[Aria] eval with bindings = $greeting")
    }

    fun eval(code: String): Any? {
        if (!isAvailable) return null
        return AriaScriptManager.eval(code)
    }

    fun evalWithBindings(code: String, bindings: Map<String, Any?>): Any? {
        if (!isAvailable) return null
        return AriaScriptManager.eval(code, bindings)
    }

    fun giveWelcomeItem(playerName: String) {
        if (!isAvailable) return

        try {
            val code = """
                var result = "Hello, " + playerName + "! Welcome to the server."
                result
            """.trimIndent()
            val result = AriaScriptManager.eval(code, mapOf("playerName" to playerName))
            bukkitPlugin.logger.info("[Aria] $result")
        } catch (e: Exception) {
            bukkitPlugin.logger.warning("[Aria] giveWelcomeItem failed: ${e.message}")
        }
    }

    fun shutdown() {
        // AriaScriptManager.shutdown() 由生成的主类自动调用
        // 这里可以做业务层的清理
    }
}
