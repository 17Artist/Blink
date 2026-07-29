package com.example.blinkobfsmoke

import org.bukkit.event.server.ServerLoadEvent
import org.bukkit.event.server.ServerCommandEvent
import org.bukkit.plugin.java.JavaPlugin
import priv.seventeen.artist.blink.bukkitPlugin
import priv.seventeen.artist.blink.BlinkLog
import priv.seventeen.artist.blink.command.BlinkCommand
import priv.seventeen.artist.blink.command.BlinkCommandGroup
import priv.seventeen.artist.blink.command.BlinkCommandRegistrar
import priv.seventeen.artist.blink.command.CommandContext
import priv.seventeen.artist.blink.command.SenderType
import priv.seventeen.artist.blink.command.SubCommand
import priv.seventeen.artist.blink.config.BlinkConfig
import priv.seventeen.artist.blink.config.BlinkConfigFolder
import priv.seventeen.artist.blink.config.BlinkSection
import priv.seventeen.artist.blink.config.Comment
import priv.seventeen.artist.blink.config.ConfigKey
import priv.seventeen.artist.blink.config.Ignore
import priv.seventeen.artist.blink.event.AutoListener
import priv.seventeen.artist.blink.event.EventManager
import priv.seventeen.artist.blink.lifecycle.Awake
import priv.seventeen.artist.blink.lifecycle.LifeCycle
import priv.seventeen.artist.blink.script.ScriptManager
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class SmokeNested : BlinkSection() {
    var message: String = "nested-default"
    var amount: Int = 1
}

class SmokeEntry : BlinkSection() {
    var value: String = "entry-default"
}

class SmokeSettings : BlinkConfig(bukkitPlugin, "smoke") {
    @Comment("implicit key regression probe")
    var implicitKeyProbe: String = "implicit-key-intact"

    @ConfigKey("explicit-key-probe")
    var explicitKeyProbe: Int = 1701

    var nested: SmokeNested = SmokeNested()
    var entries: MutableMap<String, SmokeEntry> = linkedMapOf()

    @Ignore
    var ignoredProbe: String = "ignored-default"
}

class FolderSettings(plugin: JavaPlugin, path: String) : BlinkConfig(plugin, path) {
    var value: String = "folder-default"
}

class SmokeConfigFolder(plugin: JavaPlugin) :
    BlinkConfigFolder<FolderSettings>(plugin, "entries") {

    override fun createConfig(plugin: JavaPlugin, filePath: String): FolderSettings =
        FolderSettings(plugin, filePath)
}

data class ProbePayload(val text: String, val numbers: List<Int>)

class ProbeRunnable(private val action: () -> Unit) : Runnable {
    override fun run() = action()
}

class SmokeCommandGroup : BlinkCommandGroup("admin", "reflection command probe") {

    @SubCommand(
        name = "reflect",
        description = "invoke an annotation-discovered command",
        args = ["value"],
        sender = SenderType.CONSOLE
    )
    fun reflect(context: CommandContext) {
        SmokeRuntime.groupCommandObserved = context.arg(0) == "reflected"
        context.reply("OBF_SMOKE_GROUP_OK")
    }
}

sealed interface ProbeState {
    object Ready : ProbeState
}

object SmokeCore {
    private const val SECRET_LITERAL = "OBF_LITERAL_SECRET_7A91"

    fun digest(): String {
        val payload = ProbePayload(
            text = SECRET_LITERAL.reversed().reversed(),
            numbers = (1..6).map { it * it }
        )
        val state: ProbeState = ProbeState.Ready
        val stateName = when (state) {
            ProbeState.Ready -> "Ready"
        }
        val material = "${payload.text}:${payload.numbers.sum()}:$stateName"
        return MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(StandardCharsets.UTF_8))
            .take(6)
            .joinToString("") { "%02x".format(it) }
    }

    fun objectContractsIntact(): Boolean {
        val first: Any = ProbePayload("same", listOf(1, 2, 3))
        val second: Any = ProbePayload("same", listOf(1, 2, 3))
        return first == second &&
            first.hashCode() == second.hashCode() &&
            first.toString().startsWith("ProbePayload(") &&
            hashSetOf(first).contains(second)
    }

    fun runnableContractIntact(): Boolean {
        var invoked = false
        val runnable: Runnable = ProbeRunnable { invoked = true }
        return runCatching { runnable.run() }.isSuccess && invoked
    }
}

object SmokeRuntime {
    lateinit var settings: SmokeSettings
        private set
    lateinit var configFolder: SmokeConfigFolder
        private set
    lateinit var command: BlinkCommand
        private set

    val enableOrder = mutableListOf<Int>()

    @Volatile
    var serverLoadObserved: Boolean = false
        private set
    @Volatile
    var staticCommandEventObserved: Boolean = false
        private set
    @Volatile
    var dynamicCommandEventObserved: Boolean = false
        private set
    @Volatile
    var scheduledRunnableObserved: Boolean = false
        private set

    var argsCommandObserved: Boolean = false
    var consoleCommandObserved: Boolean = false
    var groupCommandObserved: Boolean = false

    @Awake(LifeCycle.LOAD)
    fun onLoad() {
        bukkitPlugin.logger.info("OBF_SMOKE_LOAD")
    }

    @Awake(LifeCycle.ENABLE, priority = -10)
    fun onEnableEarly() {
        enableOrder += -10
    }

    @Awake(LifeCycle.ENABLE)
    fun onEnable() {
        enableOrder += 0
        settings = SmokeSettings().also { it.load() }
        configFolder = SmokeConfigFolder(bukkitPlugin).also { it.load() }

        EventManager.listen(
            ServerCommandEvent::class.java,
            "obfuscation-smoke-dynamic"
        ) { event ->
            if (event.command.startsWith("obfsmoke") || event.command.startsWith("os ")) {
                dynamicCommandEventObserved = true
            }
        }

        command = BlinkCommand("obfsmoke", "os")
            .command("ping", "basic command dispatch") { context ->
                context.reply("OBF_SMOKE_PONG")
            }
            .command(
                "args",
                "argument parsing",
                args = arrayOf("first", "?rest"),
                sender = SenderType.CONSOLE
            ) { context ->
                argsCommandObserved =
                    context.arg(0) == "alpha" &&
                        context.argJoined(1) == "beta gamma" &&
                        context.argInt(0, 77) == 77
                context.reply("OBF_SMOKE_ARGS_OK")
            }
            .command("console", "sender check", sender = SenderType.CONSOLE) { context ->
                consoleCommandObserved = true
                context.reply("OBF_SMOKE_CONSOLE_OK")
            }
            .command("tab", "tab completion", args = arrayOf("mode")) { context ->
                context.reply("OBF_SMOKE_TAB_EXECUTED ${context.arg(0)}")
            }
            .command("verify", "verify transformed Kotlin and reflected config") { context ->
                val configText = settings.configFile.readText(StandardCharsets.UTF_8)
                val implicitConfigOk = settings.implicitKeyProbe == "loaded-from-yaml"
                val explicitConfigOk = settings.explicitKeyProbe == 2402
                val nestedConfigOk =
                    settings.nested.message == "nested-loaded" &&
                        settings.nested.amount == 42
                val mapConfigOk = settings.entries["alpha"]?.value == "map-loaded"
                val ignoredConfigOk = settings.ignoredProbe == "ignored-default"
                val configKeysOk =
                    configText.contains("implicitKeyProbe:") &&
                        configText.contains("explicit-key-probe:")
                val configOk =
                    implicitConfigOk &&
                        explicitConfigOk &&
                        nestedConfigOk &&
                        mapConfigOk &&
                        ignoredConfigOk &&
                        configKeysOk
                val folderOk =
                    configFolder.configs["sub/alpha"]?.value == "folder-loaded"
                settings.save()
                val savedConfig = settings.configFile.readText(StandardCharsets.UTF_8)
                val saveOk =
                    savedConfig.contains("# implicit key regression probe") &&
                        savedConfig.contains("nested:") &&
                        savedConfig.contains("entries:") &&
                        !savedConfig.contains("ignoredProbe:")
                val digest = SmokeCore.digest()
                val objectContractsOk = SmokeCore.objectContractsIntact()
                val runnableContractOk = SmokeCore.runnableContractIntact()
                val lifecycleOk = enableOrder == listOf(-10, 0, 10)
                val commandsOk =
                    argsCommandObserved &&
                        consoleCommandObserved &&
                        groupCommandObserved
                val eventsOk =
                    staticCommandEventObserved &&
                        dynamicCommandEventObserved &&
                        EventManager.isListening("obfuscation-smoke-dynamic") &&
                        EventManager.keys().contains("obfuscation-smoke-dynamic")
                val tabOk = command.tabComplete(
                    bukkitPlugin.server.consoleSender,
                    "obfsmoke",
                    arrayOf("tab", "a")
                ) == listOf("alpha")
                val dynamicCleanupOk =
                    EventManager.unlisten("obfuscation-smoke-dynamic") &&
                        !EventManager.isListening("obfuscation-smoke-dynamic")
                val scriptOk =
                    ScriptManager.isAvailable &&
                        (ScriptManager.eval("1 + 2 + 3") as? Number)?.toInt() == 6
                if (
                    configOk &&
                    folderOk &&
                    saveOk &&
                    serverLoadObserved &&
                    digest == "d3b6842440ba" &&
                    objectContractsOk &&
                    runnableContractOk &&
                    scheduledRunnableObserved &&
                    lifecycleOk &&
                    commandsOk &&
                    eventsOk &&
                    tabOk &&
                    dynamicCleanupOk &&
                    scriptOk
                ) {
                    context.reply("OBF_SMOKE_VERIFY_OK digest=$digest")
                } else {
                    context.reply(
                        "OBF_SMOKE_VERIFY_FAIL config=$configOk " +
                            "configParts=$implicitConfigOk/$explicitConfigOk/" +
                            "$nestedConfigOk/$mapConfigOk/$ignoredConfigOk/$configKeysOk " +
                            "folder=$folderOk save=$saveOk event=$serverLoadObserved " +
                            "digest=$digest objectContracts=$objectContractsOk " +
                            "runnable=$runnableContractOk scheduled=$scheduledRunnableObserved " +
                            "lifecycle=$lifecycleOk commands=$commandsOk events=$eventsOk " +
                            "tab=$tabOk cleanup=$dynamicCleanupOk script=$scriptOk"
                    )
                }
            }
            .tabComplete("mode") { listOf("alpha", "beta") }
            .group(SmokeCommandGroup())

        BlinkCommandRegistrar.register(bukkitPlugin, command)
        BlinkLog.info("OBF_SMOKE_BLINK_LOG")
        bukkitPlugin.logger.info("OBF_SMOKE_ENABLE")
    }

    @Awake(LifeCycle.ENABLE, priority = 10)
    fun onEnableLate() {
        enableOrder += 10
        bukkitPlugin.server.scheduler.runTask(
            bukkitPlugin,
            ProbeRunnable { scheduledRunnableObserved = true }
        )
    }

    @AutoListener
    fun onServerLoad(event: ServerLoadEvent) {
        serverLoadObserved = event.type != null
        bukkitPlugin.logger.info("OBF_SMOKE_EVENT type=${event.type}")
    }

    @AutoListener
    fun onServerCommand(event: ServerCommandEvent) {
        if (event.command.startsWith("obfsmoke") || event.command.startsWith("os ")) {
            staticCommandEventObserved = true
        }
    }

    @Awake(LifeCycle.ACTIVE)
    fun onActive() {
        bukkitPlugin.logger.info("OBF_SMOKE_ACTIVE")
    }

    @Awake(LifeCycle.DISABLE)
    fun onDisable() {
        bukkitPlugin.logger.info("OBF_SMOKE_DISABLE")
    }
}
