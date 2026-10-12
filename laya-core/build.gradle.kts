plugins { `java-library` }

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.compilerArgs.addAll(listOf("--add-modules", "jdk.incubator.vector", "-Xlint:-incubating"))
}

dependencies {
    testRuntimeOnly(project(":laya-accelerate"))
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--add-modules", "jdk.incubator.vector", "-Xmx4g")
    // Model directory for parity tests (skipped when unset)
    systemProperty("laya.model.dir", System.getenv("LAYA_MODEL_DIR") ?: "")
    systemProperty("laya.model.dir.multilingual", System.getenv("LAYA_MODEL_DIR_MULTILINGUAL") ?: "")
    systemProperty("laya.model.dir.typed", System.getenv("LAYA_MODEL_DIR_TYPED") ?: "")
    testLogging { events("passed", "skipped", "failed"); showStandardStreams = true }
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).addStringOption("-add-modules", "jdk.incubator.vector")
}
