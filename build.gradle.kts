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
                    name.set(
                        when (project.name) {
                            "laya-core" -> "laya-java core"
                            "laya-accelerate" -> "laya-java Accelerate backend"
                            "laya-api" -> "laya-java API and server"
                            "laya-client" -> "laya-java client"
                            "laya-spring-boot-starter" -> "laya-java Spring Boot starter"
                            "laya-gradle-plugin" -> "laya-java Gradle plugin"
                            else -> "laya-java BOM"
                        }
                    )
                    description.set(
                        when (project.name) {
                            "laya-core" -> "Run Laya typed-decision models (yes/no, choice, score) in pure Java: tokenizer, ModernBERT encoder and decision heads, no Python or native model runtime."
                            "laya-accelerate" -> "Optional macOS speed-up for laya-core using Apple's Accelerate framework through JNA. Picked up automatically when on the classpath."
                            "laya-api" -> "Laya over HTTP: OpenAI Decisions API (/v1/decisions) and Jev / System One (/v1/systemone) handlers plus a dependency-free standalone server. No Spring required."
                            "laya-client" -> "Tiny JDK-only client for Laya and Jev-compatible decision servers (/v1/systemone, /v1/decisions)."
                            "laya-spring-boot-starter" -> "Spring Boot 3 starter: add one dependency and your app serves Laya at /v1/decisions (OpenAI Decisions API) and /v1/systemone (Jev, works with Spring AI TypeSafe)."
                            "laya-gradle-plugin" -> "Gradle plugin that starts and stops a local Laya server for development and integration tests (layaServe, layaStop)."
                            else -> "Bill of Materials that keeps all laya-java artifacts on the same version."
                        }
                    )
                    url.set("https://github.com/apscot/laya-java")
                    inceptionYear.set("2026")
                    issueManagement {
                        system.set("GitHub")
                        url.set("https://github.com/apscot/laya-java/issues")
                    }
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
        // AUTOMATIC: Central publishes as soon as validation passes (set CENTRAL_PUBLISH=manual to review in the portal first)
        publishingType = if (System.getenv("CENTRAL_PUBLISH") == "manual") "USER_MANAGED" else "AUTOMATIC"
    }
}
