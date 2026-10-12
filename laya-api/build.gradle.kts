plugins { `java-library` }

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
tasks.withType<JavaCompile>().configureEach { options.release.set(17) }

dependencies {
    testRuntimeOnly(project(":laya-accelerate"))
    api(project(":laya-core"))
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--add-modules", "jdk.incubator.vector", "-Xmx3g")
    environment("LAYA_MODEL_DIR", System.getenv("LAYA_MODEL_DIR") ?: "")
}

tasks.register<JavaExec>("serve") {
    group = "application"
    description = "Run the standalone Laya HTTP server (env: LAYA_PORT, LAYA_API_KEY, LAYA_MODELS, LAYA_PRELOAD)"
    classpath = sourceSets["main"].runtimeClasspath + configurations["testRuntimeClasspath"].filter { it.name.startsWith("jna") || it.name.contains("laya-accelerate") }
    mainClass.set("io.github.apscot.laya.api.LayaHttpServer")
    jvmArgs("--add-modules", "jdk.incubator.vector", "-Xmx3g")
}
