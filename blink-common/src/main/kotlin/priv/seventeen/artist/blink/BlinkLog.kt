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
package priv.seventeen.artist.blink

import org.bukkit.Bukkit
import org.bukkit.configuration.file.YamlConfiguration
import java.util.logging.Level

/**
 * Blink 统一控制台日志输出。
 *
 * <p>前缀可通过 blink DSL 的 {@code logPrefix} 自定义，运行时从 plugin.yml 的
 * {@code blink-log-prefix} 字段读取。未设置时使用默认的 {@code ◆ Blink} 前缀。
 */
object BlinkLog {

    private const val DEFAULT_PREFIX = "\u00A79\u25C6 \u00A7bBlink \u00A78| "
    private val COLOR_PATTERN = Regex("\u00A7[0-9a-fk-or]")

    @Volatile
    private var prefix: String = DEFAULT_PREFIX

    /**
     * 初始化日志前缀。由框架在 onLoad 阶段自动调用。
     */
    fun initPrefix() {
        try {
            val yml = bukkitPlugin.getResource("plugin.yml") ?: return
            val yaml = YamlConfiguration()
            yaml.loadFromString(yml.bufferedReader().use { it.readText() })
            val custom = yaml.getString("blink-log-prefix")
            if (!custom.isNullOrEmpty()) {
                // 用户自定义前缀，追加分隔符
                prefix = "$custom \u00A78| "
            }
        } catch (_: Exception) { }
    }

    fun info(message: String) {
        send("$prefix\u00A7f$message")
    }

    fun success(message: String) {
        send("$prefix\u00A7a$message")
    }

    fun warn(message: String) {
        send("$prefix\u00A7e$message")
    }

    fun error(message: String) {
        send("$prefix\u00A7c$message")
    }

    fun error(message: String, throwable: Throwable) {
        send("$prefix\u00A7c$message")
        bukkitPlugin.logger.log(Level.WARNING, "", throwable)
    }

    fun detail(message: String) {
        send("$prefix\u00A77$message")
    }

    private fun send(message: String) {
        try {
            Bukkit.getConsoleSender().sendMessage(message)
        } catch (_: Exception) {
            println(message.replace(COLOR_PATTERN, ""))
        }
    }
}
