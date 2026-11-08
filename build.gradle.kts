import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.2.21"
    id("com.gradleup.shadow") version "9.2.2"
}

group = "dev.etran"
version = providers.gradleProperty("pluginVersion").get()

val spigotApiVersion: String by project

// The BedWars1058 API is compiled against the jar in libs/ (bedwars-api 24.9), not a remote repository.
// Use another API jar with:  ./gradlew build -PbedwarsApiJar=/path/to/bedwars-api.jar
val bedwarsApiJar: String = providers.gradleProperty("bedwarsApiJar").orNull ?: "libs/bedwars-api-24.9.jar"

repositories {
    mavenCentral()
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/") { name = "spigot" }
}

dependencies {
    // spigot-api 1.8.8 points at a bungeecord-chat 1.8-SNAPSHOT that no longer exists in any public repo,
    // so the chat library comes from the oldest release on Maven Central. Only classes that also exist in
    // the 1.8 server (TextComponent, ClickEvent, HoverEvent) are used.
    compileOnly("org.spigotmc:spigot-api:$spigotApiVersion") {
        exclude(group = "net.md-5", module = "bungeecord-chat")
    }
    compileOnly("net.md-5:bungeecord-chat:1.16-R0.4")
    compileOnly(files(bedwarsApiJar))
    implementation(kotlin("stdlib"))
}

// Minecraft 1.8 servers run on Java 8, so the bytecode must be Java 8 compatible.
// (You can still build with any newer JDK.)
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
        freeCompilerArgs.addAll("-Xno-param-assertions", "-Xno-call-assertions", "-Xno-receiver-assertions")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.jar {
    // The plain jar has no Kotlin runtime; only the shaded jar is meant for servers.
    archiveClassifier.set("plain")
}

tasks.shadowJar {
    archiveBaseName.set("BedWars1058-ArenaJoinMessage")
    archiveClassifier.set("")

    // Bundle + relocate the Kotlin runtime so it cannot clash with other plugins shipping their own.
    val libs = "dev.etran.arenajoinmessage.libs"
    relocate("kotlin", "$libs.kotlin")
    relocate("org.jetbrains.annotations", "$libs.jetbrains.annotations")
    relocate("org.intellij.lang.annotations", "$libs.intellij.annotations")

    exclude("DebugProbesKt.bin")
    exclude("META-INF/*.kotlin_module")
    exclude("META-INF/versions/**")
    exclude("META-INF/maven/**")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
