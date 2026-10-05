plugins {
    kotlin("jvm")
    application
}

application {
    mainClass.set("com.cielvitrine.pos.refdecoder.StandaloneReferenceDecoderKt")
}

dependencies {
    // Zero internal module dependencies to ensure independent decoder implementation (POS-007)
    testImplementation(project(":pos-format"))
}
