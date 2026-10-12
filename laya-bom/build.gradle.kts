plugins { `java-platform` }

dependencies {
    constraints {
        api(project(":laya-core"))
        api(project(":laya-accelerate"))
        api(project(":laya-api"))
        api(project(":laya-client"))
        api(project(":laya-spring-boot-starter"))
    }
}
