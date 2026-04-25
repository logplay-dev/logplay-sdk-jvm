plugins { id("kotlin-client-module-base") }

dependencies {
    api(libs.slf4j.api)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.slf4j.simple)
}

tasks.test { useJUnitPlatform() }
