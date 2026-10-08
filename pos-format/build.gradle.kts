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
    systemProperty("pos2.fixture.root", rootProject.layout.buildDirectory.dir("pos2-fixtures").get().asFile.absolutePath)
    systemProperty("pos2.python.script", rootProject.file("pos-reference-decoder/python/ciel_pos2_decoder.py").absolutePath)
    outputs.dir(rootProject.layout.buildDirectory.dir("pos2-fixtures")).withPropertyName("pos2Fixtures")
}
