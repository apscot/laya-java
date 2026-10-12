plugins { `java-library` }

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
tasks.withType<JavaCompile>().configureEach { options.release.set(17) }

dependencies {
    api(project(":laya-core"))
    implementation("net.java.dev.jna:jna:5.17.0")
}
