plugins {
    id("com.gradleup.nmcp.aggregation") version "1.6.2"
    id("com.gradleup.nmcp") version "1.6.2" apply false
}

val libVersion = (findProperty("layaVersion") as String?) ?: "0.1.0-SNAPSHOT"

allprojects {
    group = "io.github.apscot"
    version = libVersion
    repositories { mavenCentral() }
}

val published = listOf("laya-core", "laya-accelerate", "laya-api", "laya-client", "laya-spring-boot-starter", "laya-gradle-plugin", "laya-bom")

subprojects {
    if (name !in published) return@subprojects
    apply(plugin = "maven-publish")
    apply(plugin = "com.gradleup.nmcp")
    apply(plugin = "signing")

    plugins.withId("java-library") {
        extensions.configure<JavaPluginExtension> {
            withSourcesJar()
            withJavadocJar()
        }
        tasks.withType<Javadoc>().configureEach {
            (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
            options.encoding = "UTF-8"
        }
    }
    plugins.withId("java-gradle-plugin") {
        extensions.configure<JavaPluginExtension> {
            withSourcesJar()
            withJavadocJar()
        }
        tasks.withType<Javadoc>().configureEach {
            (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
        }
    }
    plugins.withId("java-platform") {
        extensions.configure<PublishingExtension> {
            publications.create<MavenPublication>("maven") { from(components["javaPlatform"]) }
        }
    }

    afterEvaluate {
        extensions.configure<PublishingExtension> {
            // java-gradle-plugin publishes its module as "pluginMaven"; a second publication would collide with it
            if (plugins.hasPlugin("java-library") && !plugins.hasPlugin("java-gradle-plugin"))
                publications.create<MavenPublication>("maven") { from(components["java"]) }
            publications.withType<MavenPublication>().configureEach {
                pom {
                    name.set(project.name)
                    description.set(
                        when (project.name) {
                            "laya-core" -> "Pure-Java inference engine for Laya typed-decision models (ModernBERT + decision heads)"
                            "laya-accelerate" -> "Optional macOS Accelerate (cblas_sgemm) backend for laya-core via JNA"
                            "laya-api" -> "Jev /v1/systemone and OpenAI /v1/decisions protocol handlers and a dependency-free HTTP server for Laya"
                            "laya-client" -> "Tiny JDK-only client for Laya / Jev compatible decision servers"
                            "laya-spring-boot-starter" -> "Spring Boot starter exposing /v1/systemone and /v1/decisions backed by a local Laya model"
                            "laya-gradle-plugin" -> "Gradle plugin to run a local Laya server for development and integration tests"
                            else -> "Laya for Java: local typed decisions (choice, score, yes/no) with Jev and OpenAI Decisions compatible endpoints"
                        }
                    )
                    url.set("https://github.com/apscot/laya-java")
                    licenses {
                        license {
                            name.set("Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                    developers {
                        developer {
                            id.set("apscot")
                            name.set("apscot")
                            url.set("https://github.com/apscot")
                        }
                    }
                    scm {
                        url.set("https://github.com/apscot/laya-java")
                        connection.set("scm:git:https://github.com/apscot/laya-java.git")
                        developerConnection.set("scm:git:ssh://git@github.com/apscot/laya-java.git")
                    }
                }
            }
        }
        extensions.configure<SigningExtension> {
            val key = System.getenv("SIGNING_KEY")
            val pass = System.getenv("SIGNING_PASSWORD")
            isRequired = false
            if (key != null) {
                useInMemoryPgpKeys(key, pass)
                sign(extensions.getByType<PublishingExtension>().publications)
            }
        }
    }
}

dependencies {
    published.forEach { nmcpAggregation(project(":$it")) }
}

nmcpAggregation {
    centralPortal {
        username = System.getenv("CENTRAL_USERNAME")
        password = System.getenv("CENTRAL_PASSWORD")
        publishingType = "USER_MANAGED"
    }
}
