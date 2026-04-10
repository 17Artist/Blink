package com.example.testplugin

import com.example.testplugin.service.AriaScriptBridge
import com.example.testplugin.service.NMSBridge
import com.example.testplugin.service.ScriptBridge
import com.example.testplugin.service.WelcomeService
import priv.seventeen.artist.blink.bukkitPlugin
import priv.seventeen.artist.blink.lifecycle.Awake
import priv.seventeen.artist.blink.lifecycle.LifeCycle
import org.bukkit.Bukkit

object Bootstrap {

    @Awake(LifeCycle.LOAD)
    fun onLoad() {
        bukkitPlugin.logger.info("Loading BlinkTest...")
    }

    @Awake(LifeCycle.ENABLE)
    fun onEnable() {
        Settings.load()
        WelcomeService.init()
        bukkitPlugin.logger.info("BlinkTest enabled — ${Bukkit.getVersion()}")
    }

    @Awake(LifeCycle.ENABLE, priority = 10)
    fun initScript() {
        if (Settings.instance.enableScript) {
            ScriptBridge.init()
        }
    }

    @Awake(LifeCycle.ENABLE, priority = 10)
    fun initAria() {
        if (Settings.instance.enableAria) {
            AriaScriptBridge.init()
        }
    }

    @Awake(LifeCycle.ENABLE, priority = 10)
    fun initAsteroid() {
        if (Settings.instance.enableAsteroid) {
            NMSBridge.init()
        }
    }

    @Awake(LifeCycle.ACTIVE)
    fun onActive() {
        bukkitPlugin.logger.info("Server fully started, ${Bukkit.getOnlinePlayers().size} players online")
    }

    @Awake(LifeCycle.DISABLE)
    fun onDisable() {
        ScriptBridge.shutdown()
        AriaScriptBridge.shutdown()
        bukkitPlugin.logger.info("BlinkTest disabled")
    }
}
