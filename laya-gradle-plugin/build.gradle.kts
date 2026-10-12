plugins {
    `java-gradle-plugin`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
tasks.withType<JavaCompile>().configureEach { options.release.set(17) }

gradlePlugin {
    plugins {
        create("laya") {
            id = "io.github.apscot.laya"
            implementationClass = "io.github.apscot.laya.gradle.LayaPlugin"
            displayName = "Laya"
            description = "Run a local Laya decision server (/v1/systemone, /v1/decisions) for development and integration tests"
        }
    }
}

tasks.jar {
    manifest { attributes("Implementation-Version" to project.version) }
}
