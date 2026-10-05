plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    implementation(project(":core-model"))
}

val interopFixtures = rootProject.layout.buildDirectory.dir("pos-fixtures")
tasks.test {
    systemProperty("fixture.root", interopFixtures.get().asFile.absolutePath)
    outputs.dir(interopFixtures).withPropertyName("interopFixtures")
}
