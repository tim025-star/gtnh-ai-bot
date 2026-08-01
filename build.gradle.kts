plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.1")
}

tasks.test {
    useJUnitPlatform()
}
