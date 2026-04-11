# ⚡ Blink —— 闪烁



## 特性

- **生命周期管理** — `@Awake(LifeCycle.ENABLE)` 标记方法，支持 LOAD / ENABLE / ACTIVE / DISABLE 四个阶段和优先级控制
- **事件系统** — `@AutoListener` 编译期注册 + `EventManager` 运行时动态注册/注销
- **命令框架** — Lambda 和注解两种风格，支持子命令组、Tab 补全、参数解析、权限控制
- **配置系统** — 注解驱动的 YAML 配置，自动序列化/反序列化，支持嵌套 Section
- **JS 脚本引擎** — 通过 JSR-223 动态链接 Nashorn，按需下载
- **Aria 脚本引擎** — 集成 [Aria](https://github.com/17Artist/Aria) 脚本引擎，支持预编译、上下文隔离，按需下载
- **Asteroid NMS 桥接** — 集成 [Asteroid](https://github.com/17Artist/Asteroid) 跨版本 NMS 操作库，一套代码适配 MC 1.18.2 ~ 26.1，按需下载
- **运行时 Kotlin 加载** — 启动时检测环境并按需下载注入
- **混淆集成** — 一行配置接入 [Proteus](https://repo.arcartx.com) 混淆器，自动处理 keep / exclude / 入口类保留

## 快速开始

### 使用初始化脚本

```bash
# Linux / macOS
bash create.sh

# Windows
create.bat
```

输入项目名、作者和包名，自动生成完整的项目骨架。

构建:
```bash
./gradlew build
# 产物 build/libs/xxx-all.jar，放入 plugins/ 目录即可
```

## 混淆

集成 [Proteus](https://repo.arcartx.com) 混淆器。

```kotlin
plugins {
    id("priv.seventeen.artist.proteus") version "1.0.10"
}
blink {
    obfuscate.set(true)
}
```

```bash
./gradlew build obfuscate
```

Blink 自动配置激进的混淆方案（多策略命名混淆、AES 字符串加密、控制流混淆、调试信息移除、Kotlin metadata 重写），同时正确处理 Blink 运行时排除和入口类保留。

需要精细控制时可直接使用 Proteus DSL 覆盖，完整参考见 [DEVELOPER.md](DEVELOPER.md#混淆-proteus)。

## 文档

- **[DEVELOPER.md](DEVELOPER.md)** — 完整的开发者手册，包含所有 API 说明、配置参考、Proteus DSL 文档和 FAQ
- **[test-plugin/](test-plugin/)** — 示例项目，展示了 Blink 全部功能的用法，`build.gradle.kts` 中包含完整的 Proteus DSL 属性参考

## 仓库

Maven 仓库地址：`https://repo.arcartx.com/repository/maven-public/`

```kotlin
// settings.gradle.kts pluginManagement
maven("https://repo.arcartx.com/repository/maven-public/")

// build.gradle.kts repositories
maven("https://repo.arcartx.com/repository/maven-public/")

// 依赖坐标
implementation("priv.seventeen.artist.blink:blink-common:1.0.6")
```

## 协议

[Apache License 2.0](LICENSE)
