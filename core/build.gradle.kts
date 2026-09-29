plugins {
    `java-library`
    kotlin("jvm")
}

dependencies {
    implementation(kotlin("stdlib"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(projects.processor)
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<Copy>("copyConsumerRules") {
    from("consumer-rules.pro")
    into("build/resources/main/META-INF/proguard")
    rename { "lsparanoid-core.pro" }
}

tasks.named("processResources") {
    dependsOn("copyConsumerRules")
}
