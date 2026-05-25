plugins {
    `maven-publish`
}

dependencies {
    compileOnly("org.spigotmc:spigot-api:1.20.4-R0.1-SNAPSHOT")
    // Aria 不再以编译期依赖出现：blink-common 完全通过 AriaSharedHost 反射访问，
    // 共享 ClassLoader 由运行时按需下载注入。用户插件如需直接使用 Aria API，
    // 请在自己的 build.gradle 中声明 compileOnly("priv.seventeen.artist.aria:aria:...")。
    compileOnly("priv.seventeen.artist.asteroid:asteroid-nms:${property("asteroidVersion")}")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

val repoPassword = System.getenv("repo") ?: ""

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "blink-common"

            pom {
                name.set("Blink Common")
                description.set("Lightweight Spigot plugin development framework")
            }
        }
    }
    repositories {
        maven {
            url = uri(property("mavenRepoUrl") as String)
            isAllowInsecureProtocol = true
            credentials {
                username = property("mavenRepoUser") as String
                password = repoPassword
            }
        }
    }
}
