plugins {
    `maven-publish`
}

dependencies {
    compileOnly("org.spigotmc:spigot-api:1.20.4-R0.1-SNAPSHOT")
    compileOnly("priv.seventeen.artist.aria:aria:${property("ariaVersion")}")
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
