package com.example.testplugin

import priv.seventeen.artist.blink.bukkitPlugin
import priv.seventeen.artist.blink.config.BlinkConfig
import priv.seventeen.artist.blink.config.Comment
import priv.seventeen.artist.blink.config.ConfigKey

class Settings : BlinkConfig(bukkitPlugin, "config") {

    @Comment("欢迎消息，支持 & 颜色代码")
    @ConfigKey("welcome-message")
    var welcomeMessage: String = "&b欢迎来到服务器!"

    @Comment("玩家进入时是否发送标题")
    @ConfigKey("show-title")
    var showTitle: Boolean = true

    @Comment("标题持续时间（tick）")
    @ConfigKey("title-duration")
    var titleDuration: Int = 60

    @Comment("命令冷却时间（秒）")
    @ConfigKey("command-cooldown")
    var commandCooldown: Int = 3

    @Comment("是否启用 JS 脚本引擎")
    @ConfigKey("enable-script")
    var enableScript: Boolean = true

    @Comment("是否启用 Aria 脚本引擎")
    @ConfigKey("enable-aria")
    var enableAria: Boolean = true

    @Comment("是否启用 Asteroid NMS 桥接")
    @ConfigKey("enable-asteroid")
    var enableAsteroid: Boolean = true

    @Comment("调试模式")
    @ConfigKey("debug")
    var debug: Boolean = false

    companion object {
        lateinit var instance: Settings
            private set

        fun load() {
            instance = Settings()
            instance.load()
        }

        fun reload() = instance.reload()
    }
}
