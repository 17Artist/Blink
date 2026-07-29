plugins {
    kotlin("jvm") version "1.8.22"
    id("priv.seventeen.artist.blink") version "1.3.13"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    id("priv.seventeen.artist.proteus") version "1.0.13"
}

group = "com.example.blinkobfsmoke"
version = "1.0.0"

repositories {
    maven("https://repo.arcartx.com/repository/maven-public/")
    mavenCentral()
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    maven("https://oss.sonatype.org/content/repositories/snapshots/")
}

val obfuscationLevel = providers.gradleProperty("obfuscationLevel")
    .orElse("heavy")
    .map { it.lowercase() }
    .get()

require(obfuscationLevel in setOf("simple", "medium", "heavy")) {
    "obfuscationLevel must be simple, medium, or heavy"
}

blink {
    name.set("BlinkObfuscationSmoke")
    version.set(project.version.toString())
    description.set("Runtime smoke test for Blink and Proteus obfuscation")
    authors.set(listOf("Codex"))
    apiVersion.set("1.20")
    packageName.set("com.example.blinkobfsmoke")
    enableScript.set(true)
    obfuscate.set(true)
}

proteus {
    outputSuffix.set("-$obfuscationLevel")
    mappingFile.set(layout.buildDirectory.file("mapping-$obfuscationLevel.txt").get().asFile.absolutePath)
    seed.set(1701L)
}

// Blink applies its safe aggressive defaults after project evaluation. This later callback
// keeps Blink's generated keep/exclude rules and only dials individual transforms down for
// the simple and medium comparison artifacts.
afterEvaluate {
    proteus {
        mappingFile.set(
            layout.buildDirectory.file("mapping-$obfuscationLevel.txt").get().asFile.absolutePath
        )
        when (obfuscationLevel) {
            "simple" -> {
                packageStrategy.set("short")
                packageLength.set(1)
                forceDefaultPackage.set(false)
                classStrategy.set("short")
                classLength.set(1)
                methodStrategy.set("short")
                methodLength.set(1)
                fieldStrategy.set("short")
                fieldLength.set(1)
                localVariables.set("keep")

                stringEncryption.set(false)
                controlFlow.set(false)

                debugRemoval.set(false)
                lineNumbers.set("keep")
                sourceFile.set("keep")
                generics.set("keep")
                innerClasses.set("keep")

                restructure.set(false)
                memberReorder.set(false)
            }

            "medium" -> {
                packageStrategy.set("alphabet")
                packageLength.set(4)
                classStrategy.set("alphabet")
                classLength.set(4)
                methodStrategy.set("alphabet")
                methodLength.set(4)
                fieldStrategy.set("alphabet")
                fieldLength.set(4)
                localVariables.set("remove")

                stringEncryption.set(true)
                stringEncryptionAlgorithm.set("xor")
                perClassKey.set(true)
                controlFlow.set(true)
                controlFlowLevel.set("normal")

                debugRemoval.set(true)
                lineNumbers.set("scramble")
                sourceFile.set("rename")
                sourceFileValue.set("Smoke")
                generics.set("remove")
                innerClasses.set("keep")

                restructure.set(true)
                memberReorder.set(true)
            }

            "heavy" -> {
                // The heavy artifact intentionally uses Blink 1.3.13's complete built-in
                // Proteus preset without weakening any transform.
            }
        }
    }
}

dependencies {
    implementation("priv.seventeen.artist.blink:blink-common:1.3.13")
    compileOnly("org.spigotmc:spigot-api:1.20.4-R0.1-SNAPSHOT")
}

kotlin {
    jvmToolchain(17)
}

tasks.named("build") {
    dependsOn("shadowJar")
}
