plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":control-storage"))
    implementation(project(":catalog-storage"))
}
