plugins { kotlin("jvm"); kotlin("plugin.serialization") }
dependencies { implementation(project(":pos-format")) }

tasks.test {
    // A child-process restart must use this task's actual classes and dependencies.
    doFirst { systemProperty("ciel.benchmark.runtimeClasspath", sourceSets.test.get().runtimeClasspath.asPath) }
}
