plugins {
    kotlin("jvm") version "2.2.10" apply false
    kotlin("plugin.serialization") version "2.2.10" apply false
    id("com.android.application") version "9.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}

allprojects {
    group = "com.cielvitrine"
    version = "1.0.0-alpha01"
}

subprojects {
    if (name == "app") return@subprojects
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlin.plugin.serialization")

    configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(17)
    }

    dependencies {
        val coroutinesVersion = "1.9.0"
        val serializationVersion = "1.7.3"
        val junitVersion = "5.11.3"

        "implementation"("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
        "implementation"("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")

        "testImplementation"("org.junit.jupiter:junit-jupiter:$junitVersion")
        "testImplementation"("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}
