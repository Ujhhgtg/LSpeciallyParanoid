plugins {
    alias(libs.plugins.kotlin)
}

dependencies {
    compileOnly(gradleApi())
    implementation(projects.core)
    implementation(libs.grip)
    implementation(libs.asm.common)
    implementation("org.ow2.asm:asm-tree:9.10.1")
    implementation("org.ow2.asm:asm-analysis:9.10.1")
    implementation("com.android.tools.build:aapt2-proto:9.4.1-15978811")
    implementation("com.google.protobuf:protobuf-java:3.25.8")
    testImplementation(gradleApi())
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
