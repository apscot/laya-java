plugins { `java-library` }

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
tasks.withType<JavaCompile>().configureEach { options.release.set(17); options.compilerArgs.add("-parameters") }

dependencies {
    api(platform("org.springframework.boot:spring-boot-dependencies:3.4.5"))
    api(project(":laya-api"))
    runtimeOnly(project(":laya-accelerate"))
    api("org.springframework.boot:spring-boot-starter-web")
    annotationProcessor(platform("org.springframework.boot:spring-boot-dependencies:3.4.5"))
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--add-modules", "jdk.incubator.vector", "-Xmx3g")
    environment("LAYA_MODEL_DIR", System.getenv("LAYA_MODEL_DIR") ?: "")
}
