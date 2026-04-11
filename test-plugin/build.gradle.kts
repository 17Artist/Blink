plugins {
    kotlin("jvm") version "1.8.22"
    id("priv.seventeen.artist.blink") version "1.0.4"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    id("priv.seventeen.artist.proteus") version "1.0.10"
}

group = "com.example.testplugin"
version = "1.0.0"

repositories {
    maven("https://repo.arcartx.com/repository/maven-public/")
    mavenCentral()
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    maven("https://oss.sonatype.org/content/repositories/snapshots/")
}

blink {
    name.set("BlinkTest")
    version.set("1.0.0")
    description.set("Blink framework test plugin")
    authors.set(listOf("17Artist"))
    apiVersion.set("1.20")
    enableScript.set(true)
    enableAria.set(true)
    enableAsteroid.set(true)
    packageName.set("com.example.testplugin")
    libraries.set(listOf("com.google.code.gson:gson:2.10.1"))

    // 开启混淆，Blink 会自动配置 Proteus
    // 如需细节控制混淆，往下看 proteus { } 块
    obfuscate.set(true)
}

// 下面这些属性在 obfuscate.set(true) 时已经被 Blink 自动设置了
// 你只需要写你想覆盖的部分，不用全写
//
// proteus {
//     // ---- 输入输出 ----
//     inputFile.set("build/libs/xxx.jar")       // 默认取 jar task 输出
//     outputFile.set("build/libs/xxx-obf.jar")  // 默认 input + outputSuffix
//     outputSuffix.set("-obfuscated")
//     libraries.addAll("libs/some-sdk.jar")     // 依赖库，用于继承分析
//     mappingFile.set("build/mapping.txt")       // 映射表输出
//     mappingInput.set("build/mapping-v1.txt")   // 增量混淆，读取上次的映射
//     seed.set(0L)                               // 随机种子，0 = 随机
//
//     // ---- 配置文件模式 ----
//     // 设了这个就是 YAML 模式，Blink 的自动配置会跳过
//     // configFile.set("proteus-config.yaml")
//
//     // ---- 名称混淆 ----
//     rename.set(true)
//     renameStrategy.set("short")       // 统一回退策略（没单独设的维度用这个）
//     renameLength.set(0)               // 统一回退最小长度
//
//     // 按维度独立设置，不设就用上面的 renameStrategy/renameLength
//     packageStrategy.set("underscore") // 包名策略
//     packageLength.set(30)             // 包名最小长度
//     classStrategy.set("keyword")      // 类名策略
//     classLength.set(25)
//     methodStrategy.set("il")          // 方法名策略
//     methodLength.set(20)
//     fieldStrategy.set("o0")           // 字段名策略
//     fieldLength.set(15)
//
//     // 可选策略: short / alphabet / il / o0 / underscore / keyword / unicode / prefix
//     //   short      - 最短名，a b c aa ab...
//     //   alphabet   - 纯小写字母序列
//     //   il         - I 和 l 混淆
//     //   o0         - O o 0 混淆
//     //   underscore - _ 和 $ 组合
//     //   keyword    - Cyrillic 字符伪装成 Java 关键字 (if, do, for, new...)
//     //   unicode    - Unicode 视觉相似字符混合
//     //   prefix     - 带固定前缀，需要配合 packages.prefix 参数
//
//     forceDefaultPackage.set(true)     // 所有类移到同一个包下
//     defaultPackage.set("com.example") // 目标包名，空字符串 = 默认包
//     localVariables.set("remove")      // remove / keep / rename
//     updateResources.set(true)         // 更新资源文件中的类引用
//
//     // ---- 字符串加密 ----
//     stringEncryption.set(true)
//     stringEncryptionAlgorithm.set("aes")  // xor / aes / rc4
//     perClassKey.set(true)                  // 每个类用不同的密钥
//     ignoredStrings.addAll("^$")            // 不加密的字符串（正则）
//
//     // ---- 控制流混淆 ----
//     controlFlow.set(true)                  // 不透明谓词注入
//
//     // ---- 调试信息移除 ----
//     debugRemoval.set(true)
//     lineNumbers.set("remove")       // remove / keep / scramble
//     sourceFile.set("remove")        // remove / keep / rename
//     sourceFileValue.set("")         // rename 时的替换值
//     generics.set("remove")          // remove / keep
//     innerClasses.set("remove")      // remove / keep
//
//     // ---- 反调试 ----
//     antiDebug.set(false)
//     debuggerDetection.set(true)     // JDWP 调试器检测
//     timingCheck.set(false)          // 执行时间异常检测
//
//     // ---- 类结构重组 ----
//     restructure.set(true)
//     memberReorder.set(true)         // 随机打乱方法/字段声明顺序
//     methodInline.set(false)         // 小型 private 方法内联
//     fieldMerge.set(false)           // 多个 boolean 合并为 int 位域
//
//     // ---- Kotlin ----
//     kotlinMetadataRewrite.set(true)  // 重写 @kotlin.Metadata 中的类名引用
//     kotlinCoroutineAware.set(true)   // 协程状态机感知
//     kotlinStructureAware.set(true)   // companion/data/sealed/object 识别
//
//     // ---- 过滤 ----
//     exclude.addAll(                  // 完全不处理（支持 * 和 ** 通配符）
//         "META-INF/**",
//         "kotlin.**",
//         "kotlinx.**"
//     )
//     keepClasses.addAll(              // 不重命名的类
//         "com.example.api.**"
//     )
//     keepAnnotations.addAll(          // 带这些注解的类/成员不重命名
//         "kotlinx.serialization.Serializable",
//         "com.google.gson.annotations.SerializedName"
//     )
// }

dependencies {
    implementation("priv.seventeen.artist.blink:blink-common:1.0.4")
    compileOnly("org.spigotmc:spigot-api:1.20.4-R0.1-SNAPSHOT")
    compileOnly("com.google.code.gson:gson:2.10.1")
}

kotlin {
    jvmToolchain(17)
}

tasks.named("build") {
    dependsOn("shadowJar")
}
