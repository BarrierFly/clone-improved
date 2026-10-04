plugins {
    id("dev.kikugie.loom-back-compat")
}

// DO NOT set group = ...! It comes from mod.group in stonecutter.properties.toml.
version = "${property("mod.version")}+${sc.current.version}"
base.archivesName = property("mod.id") as String

val requiredJava: JavaVersion = when {
    sc.current.parsed >= "26.1" -> JavaVersion.VERSION_25
    sc.current.parsed >= "1.20.5" -> JavaVersion.VERSION_21
    else -> JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
}

dependencies {
    minecraft("com.mojang:minecraft:${sc.current.version}")
    loomx.applyMojangMappings()
    modImplementation("net.fabricmc:fabric-loader:${property("deps.fabric_loader")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("deps.fabric_api")}")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    withSourcesJar()
    targetCompatibility = requiredJava
    sourceCompatibility = requiredJava

    toolchain {
        languageVersion = JavaLanguageVersion.of(requiredJava.majorVersion)
    }
}

// On 26.1+ the unobfuscated loom variant re-attaches the raw source dirs, bypassing
// Stonecutter's preprocessed sources; re-assert them for every node after evaluation.
// Harmless for the remap-loom nodes, which already point at the same directories.
afterEvaluate {
    val generatedMain = file("build/generated/stonecutter/main/java")
    val generatedTest = file("build/generated/stonecutter/test/java")

    sourceSets {
        main { java { setSrcDirs(listOf("src/main/java", generatedMain)) } }
        test { java { setSrcDirs(listOf("src/test/java", generatedTest)) } }
    }
    tasks.named("compileJava") { dependsOn("stonecutterGenerate") }
    tasks.named("compileTestJava") { dependsOn("stonecutterGenerateTest") }
    tasks.matching { it.name == "sourcesJar" }.configureEach {
        dependsOn("stonecutterGenerate")
    }
}

tasks {
    processResources {
        fun MutableMap<String, String>.register(key: String, property: String) {
            val value: String = sc.properties[property]
            inputs.property(key, value)
            set(key, value)
        }

        val props = buildMap {
            register("id", "mod.id")
            register("name", "mod.name")
            register("minecraft", "mod.mc_compat")
            // fabric.mod.json version carries the MC version, like the jar name
            val modVersion: String = sc.properties["mod.version"]
            inputs.property("mod.version", modVersion)
            inputs.property("mc.version", sc.current.version)
            // Reuse the project version so the metadata can never drift from the jar name.
            set("version", project.version.toString())
        }

        filesMatching("fabric.mod.json") { expand(props) }
    }

    withType<Test> {
        useJUnitPlatform()
    }

    register<Copy>("buildAndCollect") {
        group = "build"
        description = "Copies the built mod jar into build/libs/<mod version>/"
        from(loomx.modJar.flatMap { it.archiveFile })
        into(rootProject.layout.buildDirectory.file("libs/${project.property("mod.version")}"))
    }
}
