plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":pos-format"))
    implementation(project(":pos-reference-decoder"))
    implementation(project(":core-crypto"))
    implementation(project(":control-storage"))
    implementation(project(":catalog-storage"))
    implementation(project(":saga-engine"))
    implementation(project(":reader-share"))
    implementation(project(":provider-adapter"))

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
