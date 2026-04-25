plugins { id("kotlin-client-module-base") }

dependencies {
    api(project(":logplay-client-core"))
    api(libs.slf4j.api)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)

    // Coroutines are compileOnly: suspend extensions ship in the jar but only load if the
    // consumer adds kotlinx-coroutines on their classpath. Java users incur zero coroutine
    // runtime cost.
    compileOnly(libs.kotlinx.coroutines.core)
    compileOnly(libs.kotlinx.coroutines.jdk8)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.jdk8)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.slf4j.simple)
}

tasks.test { useJUnitPlatform { excludeTags("integration") } }

tasks.register<Test>("integrationTest") {
    description = "Runs SDK integration tests against an externally-running LogPlay server."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("integration") }
    shouldRunAfter(tasks.test)
    val baseUrl =
        (project.findProperty("logplay.server.baseUrl") as String?)
            ?: System.getenv("LOGPLAY_SERVER_BASE_URL")
            ?: "http://localhost:8080"
    systemProperty("logplay.server.baseUrl", baseUrl)
    val httpTimeout =
        (project.findProperty("logplay.server.httpTimeout") as String?)
            ?: System.getenv("LOGPLAY_SERVER_HTTP_TIMEOUT")
    if (httpTimeout != null) systemProperty("logplay.server.httpTimeout", httpTimeout)
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
}

tasks.register("verifyJvmTarget") {
    description = "Verifies that compiled classes target Java 11 (major version 55)."
    group = "verification"
    dependsOn(tasks.classes)
    doLast {
        val classFile =
            fileTree(layout.buildDirectory.dir("classes/kotlin/main"))
                .matching { include("**/*.class") }
                .firstOrNull() ?: error("No compiled class files found.")
        val bytes = classFile.readBytes()
        require(bytes.size >= 8 && bytes[0] == 0xCA.toByte() && bytes[1] == 0xFE.toByte()) {
            "Not a class file: ${classFile.path}"
        }
        val major = ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF)
        require(major == 55) {
            "Expected JVM 11 bytecode (major=55) but got major=$major in ${classFile.path}"
        }
        logger.lifecycle("verifyJvmTarget OK: ${classFile.name} major=$major")
    }
}

tasks.named("check") { dependsOn("verifyJvmTarget") }
