plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    implementation(project(":core-model"))
    implementation("org.xerial:sqlite-jdbc:3.45.2.0")
}
