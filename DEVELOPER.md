# Blink 开发者手册

## 运行时架构

```
BlinkGeneratedMain.onLoad()
    KotlinBootstrap.bootstrap(this)
    bukkitPlugin = this
    DependencyLoader.loadAll()
    [enableScript]  DependencyLoader.loadNashorn() → ScriptManager.init()
    [enableAria]    AriaScriptManager.init(plugin) → AriaSharedHost.acquire (部署/复用 BlinkAriaHost)
    [enableAsteroid] AsteroidManager.init(plugin) → AsteroidSharedHost.acquire (部署/复用 BlinkAsteroidHost)
    BlinkGeneratedLifeCycle.registerAll()
    LifeCycleManager.trigger(LOAD)

BlinkGeneratedMain.onEnable()
    BlinkGeneratedEvents.registerAll()
    LifeCycleManager.trigger(ENABLE)
    scheduler.runTask → LifeCycleManager.trigger(ACTIVE)

BlinkGeneratedMain.onDisable()
    LifeCycleManager.trigger(DISABLE)
    [enableScript]   ScriptManager.shutdown()
    [enableAria]     AriaScriptManager.shutdown()
    [enableAsteroid] AsteroidManager.shutdown()
```

## 快速开始

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        maven("https://repo.arcartx.com/repository/maven-public/")
        gradlePluginPortal()
        mavenCentral()
    }
}
rootProject.name = "MyPlugin"
```

```kotlin
// build.gradle.kts
plugins {
    kotlin("jvm") version "1.8.22"
    id("priv.seventeen.artist.blink") version "1.3.11"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

repositories {
    maven("https://repo.arcartx.com/repository/maven-public/")
    mavenCentral()
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
}

blink {
    name.set("MyPlugin")
    version.set("1.0.0")
    authors.set(listOf("YourName"))
    apiVersion.set("1.21")
    packageName.set("com.example.myplugin")
}

dependencies {
    implementation("priv.seventeen.artist.blink:blink-common:1.3.11")
    compileOnly("org.spigotmc:spigot-api:1.21-R0.1-SNAPSHOT")
}

kotlin {
    jvmToolchain(17)
}

tasks.named("build") {
    dependsOn("shadowJar")
}
```

```kotlin
package com.example.myplugin

import priv.seventeen.artist.blink.bukkitPlugin
import priv.seventeen.artist.blink.lifecycle.Awake
import priv.seventeen.artist.blink.lifecycle.LifeCycle

object MyPlugin {
    @Awake(LifeCycle.ENABLE)
    fun onEnable() {
        bukkitPlugin.logger.info("Hello from Blink!")
    }
}
```

## blink { } DSL

```kotlin
blink {
    name.set("MyPlugin")
    version.set("1.0.0")
    description.set("A cool plugin")
    authors.set(listOf("Author1", "Author2"))
    apiVersion.set("1.21")
    depend.set(listOf("Vault"))
    softDepend.set(listOf("PlaceholderAPI"))
    foliaSupported.set(false)
    libraries.set(listOf("com.google.code.gson:gson:2.10.1"))
    kotlinVersion.set("1.8.22")
    enableScript.set(false)
    enableAria.set(false)
    enableAsteroid.set(false)
    packageName.set("com.example.myplugin")
    logPrefix.set("")                                   // 自定义日志前缀，如 "§1♦ §bMyPlugin"，空则使用默认
    obfuscate.set(false)
    obfuscateKeep.set(listOf())
    obfuscateExclude.set(listOf())
}
```

构建脚本末尾建议加上，让 `./gradlew build` 直接产出 fat jar：

```kotlin
tasks.named("build") {
    dependsOn("shadowJar")
}
```

## 生命周期

```kotlin
@Awake(LifeCycle.LOAD, priority = 0)
fun init() { }

@Awake(LifeCycle.ENABLE, priority = 10)
fun setup() { }

@Awake(LifeCycle.ACTIVE)
fun ready() { }

@Awake(LifeCycle.DISABLE)
fun cleanup() { }
```

| 阶段      | 时机       | 等价于                     |
|---------|----------|-------------------------|
| LOAD    | 插件加载     | JavaPlugin.onLoad()     |
| ENABLE  | 插件启用     | JavaPlugin.onEnable()   |
| ACTIVE  | 第一个 tick | onEnable() 后 runTask 回调 |
| DISABLE | 插件禁用     | JavaPlugin.onDisable()  |

方法要求：public、无参、无返回值，位于 `object` 单例或顶级函数中。普通 class 的实例方法不支持。

`priority` 值越小越先执行，默认 0。

运行时手动注册：

```kotlin
LifeCycleManager.register(LifeCycle.ENABLE, priority = 0, Runnable { ... }, "描述")
LifeCycleManager.clear()
```

## 事件监听

### 编译期注册

```kotlin
@AutoListener
fun onJoin(event: PlayerJoinEvent) {
    event.joinMessage = "Welcome!"
}

@AutoListener(priority = EventPriority.HIGH, ignoreCancelled = true)
fun onBreak(event: BlockBreakEvent) { }
```

方法参数必须是单个 Event 子类。

### 运行时注册

```kotlin
EventManager.listen<PlayerJoinEvent>("join-handler") { event ->
    event.player.sendMessage("Hello!")
}

EventManager.listen<PlayerMoveEvent>(
    key = "move-handler",
    priority = EventPriority.HIGH,
    ignoreCancelled = true
) { event -> }

// Java 互操作
EventManager.listen(PlayerQuitEvent::class.java, "quit-handler") { event -> }
```

同 key 重复注册自动替换旧监听器。

```kotlin
EventManager.unlisten("join-handler")   // 注销指定
EventManager.unlistenAll()               // 注销所有动态监听器
EventManager.unregisterAll()             // 注销全部（编译期 + 动态）
EventManager.isListening("join-handler") // 查询
EventManager.keys()                      // 所有已注册 key
```

## 命令

### Lambda 注册

```kotlin
@Awake(LifeCycle.ENABLE)
fun registerCommands() {
    BlinkCommandRegistrar.register(bukkitPlugin,
        BlinkCommand("myplugin", "mp")
            .command("reload", "重载配置", permission = "myplugin.reload") { ctx ->
                ctx.reply("§a已重载")
            }
            .command("give", "给物品",
                args = arrayOf("player", "item", "?amount"),
                sender = SenderType.OP
            ) { ctx ->
                val player = ctx.argPlayer(0) ?: return@command ctx.reply("§c玩家不存在")
                ctx.reply("§a给了 ${player.name} ${ctx.argInt(2, 1)}x ${ctx.arg(1)}")
            }
            .tabComplete("item") { listOf("diamond", "gold", "iron") }
    )
}
```

### 注解驱动

```kotlin
class AdminCommands : BlinkCommandGroup("admin", "管理命令") {
    @SubCommand(name = "reload", description = "重载配置", permission = "myplugin.admin")
    fun reload(ctx: CommandContext) { ctx.reply("Config reloaded!") }

    @SubCommand(name = "ban", description = "封禁玩家",
        args = ["player", "?reason"], sender = SenderType.OP)
    fun ban(ctx: CommandContext) {
        val target = ctx.argPlayer(0)
        ctx.reply("Banned ${target?.name}: ${ctx.argJoined(1).ifEmpty { "No reason" }}")
    }
}
```

两种方式可以混用：`.group(AdminCommands()).command("info", "查看信息") { ctx -> ... }`

`args` 中 `?` 开头的为可选参数，生成用法提示时显示为 `[xxx]`。

### CommandContext

| 方法 | 返回值 | 说明 |
|------|--------|------|
| sender | CommandSender | 命令发送者 |
| player | Player? | 发送者转 Player |
| label | String | 实际使用的命令名 |
| size | Int | 参数数量 |
| arg(index) | String | 获取参数，越界返回空字符串 |
| argInt(index, default) | Int | 参数转 Int |
| argLong(index, default) | Long | 参数转 Long |
| argDouble(index, default) | Double | 参数转 Double |
| argBoolean(index, default) | Boolean | 参数转 Boolean |
| argPlayer(index) | Player? | 在线玩家 |
| argUUID(index) | UUID? | UUID |
| argJoined(fromIndex) | String | 拼接剩余参数 |
| reply(message) | Unit | 回复消息 |

SenderType: `ALL`（默认）/ `PLAYER` / `OP` / `CONSOLE`

## 配置

```kotlin
class Settings : BlinkConfig(bukkitPlugin, "config") {
    @Comment("是否开启调试模式")
    var debug: Boolean = false

    @Comment("欢迎消息")
    @ConfigKey("welcome-message")
    var welcomeMessage: String = "Welcome, %player%!"

    var maxPlayers: Int = 100
    var whitelist: List<String> = listOf("Player1", "Player2")
    var database: DatabaseSection = DatabaseSection()

    @Ignore
    var tempCache: Any? = null
}

class DatabaseSection : BlinkSection() {
    var host: String = "localhost"
    var port: Int = 3306
    var name: String = "mydb"
}
```

```kotlin
val config = Settings()
config.load()       // 文件不存在时自动生成默认值
config.reload()
config.save()
```

`@ConfigKey` 自定义 YAML key，不写则使用字段名。`@Comment` 生成注释行。`@Ignore` 跳过该字段。

只有 `var` 字段参与配置绑定，`val` 不参与。

支持类型：String / Int / Long / Double / Float / Boolean / List / Map / BlinkSection 子类。

BlinkConfigFolder 可以批量管理一个目录下的配置文件。

## JS 脚本引擎

通过 JSR-223 动态链接 Nashorn（GPL-2.0 WITH Classpath Exception），`enableScript.set(true)` 启用后运行时自动下载。

```kotlin
@Awake(LifeCycle.ENABLE, priority = 10)
fun initJS() {
    Bukkit.getScheduler().runTaskAsynchronously(bukkitPlugin, Runnable {
        val jars = DependencyLoader.loadNashorn(bukkitPlugin)
        ScriptManager.init(jars)
        Bukkit.getScheduler().runTask(bukkitPlugin, Runnable {
            if (ScriptManager.isAvailable) bukkitPlugin.logger.info("JS engine ready!")
        })
    })
}
```

```kotlin
ScriptManager.eval("1 + 2")
ScriptManager.eval("msg + name", mapOf("msg" to "Hello ", "name" to "World"))
ScriptManager.evalFile(File(bukkitPlugin.dataFolder, "scripts/init.js"))
ScriptManager.createEngine()  // 创建独立引擎实例，线程安全
```

disable 时由生成的主类自动调用 `ScriptManager.shutdown()`。

`enableScript = false` 时不会下载任何依赖。

## Aria 脚本引擎

集成 [Aria](https://repo.arcartx.com) 脚本引擎，`enableAria.set(true)` 启用后运行时自动部署为全 JVM 唯一的共享插件 `BlinkAriaHost`（与 Asteroid 同模型，不打包进消费者 jar）。Aria 支持预编译、上下文隔离，适合需要高频执行脚本的场景。

```kotlin
@Awake(LifeCycle.ENABLE, priority = 10)
fun initAria() {
    if (AriaScriptManager.isAvailable) {
        bukkitPlugin.logger.info("Aria engine ready!")
    }
}
```

> 自 1.4.0 起 Aria 改为编译期直接依赖、去掉反射包装层：`AriaScriptManager` 只管生命周期（`isAvailable` / `shutdown`），**不再有 `eval` / `compile` / `createContext`**。执行脚本直接用 `priv.seventeen.artist.aria.Aria` 的静态方法，先用 `AriaScriptManager.isAvailable` 守门。

```kotlin
import priv.seventeen.artist.aria.Aria

// 一次性 eval：结果是 IValue<?>，用 jvmValue() / stringValue() / numberValue() / booleanValue() 取值
val sum = Aria.eval("1 + 2 + 3", Aria.createContext()).jvmValue()

// 预编译（热路径：编译一次、execute 多次），每次用独立 Context 做上下文隔离
val routine = Aria.compile("greeting", "'Hello, ' + 'World' + '!'")
val msg = routine.execute(Aria.createContext()).stringValue()

// 仅当需要反射加载 Aria 扩展类型时，才从共享 ClassLoader 取类
val cl = AriaScriptManager.sharedClassLoader
```

disable 时由生成的主类自动调用 `AriaScriptManager.shutdown()`（只释放本地引用，共享宿主跨插件存活）。

`enableAria = false` 时不会下载任何依赖。

## Asteroid NMS 桥接

集成 [Asteroid](https://repo.arcartx.com) 跨版本 NMS 操作库，`enableAsteroid.set(true)` 启用后运行时自动部署为全 JVM 唯一的共享插件 `BlinkAsteroidHost`（与 Aria 同模型，不打包进消费者 jar）。一套代码适配 MC 1.18.2 ~ 26.1，兼容 Paper / Spigot / Folia。

```kotlin
@Awake(LifeCycle.ENABLE, priority = 10)
fun initNMS() {
    if (AsteroidManager.isAvailable) {
        bukkitPlugin.logger.info("Asteroid ready — MC ${AsteroidAPI.getMcVersion()}")
    }
}
```

```kotlin
// 数据包监听
AsteroidAPI.addPacketListener(bukkitPlugin, object : PacketListener {
    override fun onReceive(event: PacketEvent) { }
    override fun onSend(event: PacketEvent) { }
})

// 自定义实体（Ability 组合模式）
val entity = AsteroidAPI.createCustomEntity(player.location, 1.0, 2.0)
entity.addAbility(HitboxAbility(1.5, 2.0))
entity.addAbility(DamageAbility { event -> event.isCancelled = true })

// ItemTag — 跨版本 NBT/DataComponent 统一读写
val tag = ItemTag.fromItemStack(itemStack)
tag.putDeep("custom.damage", ItemTagData.of(100))
tag.saveTo(itemStack)

// 属性桥接
val bridge = AsteroidAPI.getAttributeBridge()
bridge.setModifier(player, "generic.max_health", "my_health", 20.0, 0) // 0 = ADD_NUMBER

// 生物 AI
val ai = AsteroidAPI.getMobAI()
ai.clearGoals(mob)
ai.addGoal(mob, 1, myCustomGoal)

// 物品序列化
val json = AsteroidAPI.getItemStackNMS().item2Json(itemStack)
val restored = AsteroidAPI.getItemStackNMS().json2Item(json)
```

disable 时由生成的主类自动调用 `AsteroidManager.shutdown()`，仅释放本插件本地引用——共享宿主 `BlinkAsteroidHost` 与全局数据包注入跨插件存活，不会被单个插件卸载拆掉。若用过 `AsteroidAPI.addPacketListener(bukkitPlugin, ...)`，请在自身 onDisable 调 `AsteroidAPI.removePacketListener(bukkitPlugin, ...)` 自行清理（框架不代管）。

`enableAsteroid = false` 时不会部署宿主、不下载任何依赖。

## 运行时依赖加载

onLoad 阶段自动下载：

| 依赖                                           | 条件                   |
|----------------------------------------------|----------------------|
| kotlin-stdlib / kotlin-reflect / annotations | 环境中不存在时              |
| nashorn-core + ASM                           | enableScript = true  |
| aria（部署为 BlinkAriaHost 共享插件）                 | enableAria = true    |
| asteroid-nms（部署为 BlinkAsteroidHost 共享插件）     | enableAsteroid = true |
| blink-libraries 声明的依赖                        | 始终                   |

`enableAsteroid = true` 时 asteroid-nms 由 `AsteroidSharedHost` 在 onLoad 阶段下载并包装成 `BlinkAsteroidHost.jar`（追加 plugin.yml + 宿主入口类），经 `PluginManager.loadPlugin`/`enablePlugin` 部署为全 JVM 唯一的共享 Bukkit 插件——不打包进消费者 jar、不 relocate。缓存于 `plugins/.blink-shared/BlinkAsteroidHost.jar`，仓库不可达时回退本地缓存。

下载目录：`plugins/<PluginName>/libs/`

自定义仓库和 Kotlin 版本通过 `plugins/<PluginName>/blink.yml` 配置：

```yaml
kotlin-version: "1.8.22"
repositories:
  - "https://maven.aliyun.com/repository/central"
  - "https://repo1.maven.org/maven2"
  - "https://repo.huaweicloud.com/repository/maven"
```


## 混淆 (Proteus)

Blink 集成 [Proteus](https://repo.arcartx.com) 混淆器，通过反射设置 Proteus DSL 属性实现自动配置。

### 基本用法

```kotlin
plugins {
    id("priv.seventeen.artist.proteus") version "1.0.13"
}
blink {
    obfuscate.set(true)
}
```

```bash
./gradlew build obfuscate
```

开启后 Blink 自动配置的默认方案：包名 underscore / 类名 keyword / 方法名 il / 字段名 o0，AES 字符串加密（per-class-key），控制流混淆，调试信息全部移除，类结构重组（成员重排序），Kotlin metadata 重写 + 协程感知 + 结构感知。Blink 运行时自动 exclude，BlinkGenerated* 入口类自动 keep，映射表输出到 `build/mapping.txt`。

额外的 keep / exclude 规则通过 Blink DSL 添加：

```kotlin
blink {
    obfuscate.set(true)
    obfuscateKeep.set(listOf("com.example.api.**"))
    obfuscateExclude.set(listOf("com.example.lib.**"))
}
```

### Proteus DSL 完整参考

直接使用 `proteus { }` 块可以覆盖 Blink 的自动配置。设置 `configFile` 后 Blink 的自动配置完全跳过。

```kotlin
proteus {
    // 输入输出
    inputFile.set("build/libs/xxx.jar")       // 默认取 jar task 输出
    outputFile.set("build/libs/xxx-obf.jar")  // 默认 input + outputSuffix
    outputSuffix.set("-obfuscated")
    libraries.addAll("libs/some-sdk.jar")     // 依赖库，用于继承分析
    mappingFile.set("build/mapping.txt")       // 映射表输出路径
    mappingInput.set("build/mapping-v1.txt")   // 增量混淆，读取上次映射
    seed.set(0L)                               // 随机种子，0 = 随机

    // 配置文件模式（设置后 DSL 属性被忽略，走 YAML 配置）
    configFile.set("proteus-config.yaml")

    // 名称混淆
    rename.set(true)
    renameStrategy.set("short")       // 统一回退策略（未单独设置的维度使用此值）
    renameLength.set(0)               // 统一回退最小长度
    packageStrategy.set("underscore") // 包名策略，不设则回退到 renameStrategy
    packageLength.set(30)
    classStrategy.set("keyword")      // 类名策略
    classLength.set(25)
    methodStrategy.set("il")          // 方法名策略
    methodLength.set(20)
    fieldStrategy.set("o0")           // 字段名策略
    fieldLength.set(15)
    forceDefaultPackage.set(true)     // 将所有被处理的类移到同一个包下
    defaultPackage.set("com.example") // 目标包名，空字符串表示默认包
    localVariables.set("remove")      // remove / keep / rename
    updateResources.set(true)         // 更新资源文件中的类名引用

    // 字符串加密
    stringEncryption.set(true)
    stringEncryptionAlgorithm.set("aes")  // xor / aes / rc4
    perClassKey.set(true)                  // 每个类使用独立密钥
    ignoredStrings.addAll("^$")            // 不加密的字符串模式（正则）

    // 控制流混淆
    controlFlow.set(true)                  // 不透明谓词注入

    // 调试信息移除
    debugRemoval.set(true)
    lineNumbers.set("remove")       // remove / keep / scramble
    sourceFile.set("remove")        // remove / keep / rename
    sourceFileValue.set("")         // rename 模式下的替换值
    generics.set("remove")          // remove / keep
    innerClasses.set("remove")      // remove / keep

    // 反调试
    antiDebug.set(false)
    debuggerDetection.set(true)     // JDWP 调试器检测
    timingCheck.set(false)          // 执行时间异常检测

    // 类结构重组
    restructure.set(true)
    memberReorder.set(true)         // 随机打乱方法/字段声明顺序
    methodInline.set(false)         // 小型 private 方法内联
    fieldMerge.set(false)           // 多个 boolean 字段合并为 int 位域

    // Kotlin 支持
    kotlinMetadataRewrite.set(true)  // 重写 @kotlin.Metadata 中的类名引用
    kotlinCoroutineAware.set(true)   // 协程状态机感知
    kotlinStructureAware.set(true)   // companion / data / sealed / object 识别

    // 过滤规则（支持 * 和 ** 通配符）
    exclude.addAll("META-INF/**", "kotlin.**", "kotlinx.**")
    keepClasses.addAll("com.example.api.**")
    keepAnnotations.addAll("kotlinx.serialization.Serializable")
}
```

### 命名策略

| 策略         | 效果                      | 示例               |
|------------|-------------------------|------------------|
| short      | 最短名称                    | a, b, aa, ab     |
| alphabet   | 纯小写字母序列                 | a, b, z, aa      |
| il         | I/l 混淆，等宽字体下难以区分        | I, l, Il, lI     |
| o0         | O/o/0 混淆                | O, o, OO, O0     |
| underscore | _/$ 组合                  | `_, $, __, _$`   |
| keyword    | Cyrillic 字符伪装成 Java 关键字 | іf, dо, fоr, nеw |
| unicode    | Unicode 视觉相似字符混合        | Ι, ı, Ӏl         |
| prefix     | 带固定前缀                   | _a, _b           |

`length` 参数强制最小名称长度，不足时用自身字符循环填充。各维度（包/类/方法/字段）可以使用不同策略。

### YAML 配置

通过 `proteus { configFile.set("proteus-config.yaml") }` 使用 YAML 配置。根节点为 `proteus:`，同时支持 kebab-case 和 camelCase。

```yaml
proteus:
  input: "build/libs/myapp.jar"       # Gradle 模式下可省略
  output: "build/libs/myapp-obf.jar"  # Gradle 模式下可省略
  mapping:
    output: "build/mapping.txt"
    input: ""                          # 增量混淆时填上次的映射文件
  seed: 42

  rename:
    enabled: true
    packages:
      strategy: "underscore"
      length: 30
      force-default-package: true
      default-package: "com.example"
    classes:
      strategy: "keyword"
      length: 25
    methods:
      strategy: "il"
      length: 20
    fields:
      strategy: "o0"
      length: 15
    local-variables: "remove"

  string-encryption:
    enabled: true
    algorithm: "aes"
    per-class-key: true

  control-flow:
    enabled: true

  debug-removal:
    enabled: true
    line-numbers: "remove"
    source-file: "remove"
    generics: "remove"
    inner-classes: "remove"
    throws-clause: "remove"

  anti-debug:
    enabled: false
    debugger-detection: true
    timing-check: false

  restructure:
    enabled: true
    member-reorder: true
    method-inline: false
    field-merge: false

  kotlin:
    metadata-rewrite: true
    coroutine-aware: true
    structure-aware: true

  keep:
    - class: "com.example.api.**"
    - class: "com.example.Main*"
      members: "main"
    - annotation: "kotlinx.serialization.Serializable"

  exclude:
    - "META-INF/**"
    - "kotlin.**"
    - "kotlinx.**"
```

### 混淆兼容

Blink 运行时自动 exclude，入口类自动 keep。生成的字节码通过 LambdaMetafactory + 直接调用引用用户代码，Proteus 可以正确追踪引用并同步重命名。

使用运行时反射的场景（如 Gson 序列化）需要额外 keep 相关类或注解。

## 项目结构参考

```
my-plugin/
├── build.gradle.kts
├── settings.gradle.kts
└── src/main/kotlin/com/example/myplugin/
    ├── Bootstrap.kt          @Awake 生命周期
    ├── Settings.kt           BlinkConfig
    ├── command/
    │   ├── MainCommand.kt    命令注册
    │   └── AdminCommands.kt  BlinkCommandGroup
    ├── listener/
    │   └── JoinListener.kt   @AutoListener
    └── service/
        ├── ScriptBridge.kt    JS 脚本桥接
        ├── AriaScriptBridge.kt Aria 脚本桥接
        ├── NMSBridge.kt       Asteroid NMS 桥接
        └── SomeService.kt
```
