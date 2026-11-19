plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.spring") version "2.4.21"
    id("org.springframework.boot") version "4.1.2"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.google.cloud.tools.jib") version "3.5.4"
}

group = "io.granulee"
// release-please bumps ../version.txt on every release.
version = file("../version.txt").readText().trim()

// Puts the version on the classpath (META-INF/build-info.properties) for /api/health.
springBoot {
    buildInfo()
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("com.clickhouse:client-v2:0.11.2")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// `./gradlew bootJar -PwithFrontend` builds ../frontend and serves it from the jar.
val frontendDir = layout.projectDirectory.dir("../frontend")
val buildFrontend by tasks.registering(Exec::class) {
    workingDir = frontendDir.asFile
    commandLine("sh", "-c", "npm ci && npm run build")
    inputs.dir(frontendDir.dir("src"))
    inputs.file(frontendDir.file("package.json"))
    outputs.dir(frontendDir.dir("dist"))
}
if (project.hasProperty("withFrontend")) {
    tasks.named<ProcessResources>("processResources") {
        dependsOn(buildFrontend)
        from(frontendDir.dir("dist")) { into("static") }
    }
}

// Container image, with the frontend inside when built with -PwithFrontend.
// `./gradlew jibDockerBuild -PwithFrontend` loads it into the local Docker daemon as granulee:latest;
// `./gradlew jib -PwithFrontend -Djib.to.image=registry.example.com/granulee` pushes it, logging in with
// REGISTRY_USERNAME / REGISTRY_PASSWORD when set. Tags: -Djib.to.tags=a,b. A plain-HTTP registry also
// needs -Djib.allowInsecureRegistries=true -DsendCredentialsOverHttp=true.
jib {
    from {
        image = "eclipse-temurin:21-jre"
    }
    to {
        image = "granulee"
        tags = setOf("latest")
        auth {
            username = System.getenv("REGISTRY_USERNAME")
            password = System.getenv("REGISTRY_PASSWORD")
        }
    }
    container {
        mainClass = "io.granulee.GranuleeApplicationKt"
        ports = listOf("8080")
        volumes = listOf("/data")
        environment = mapOf("GRANULEE_DATA_DIR" to "/data")
        jvmFlags = listOf("-XX:MaxRAMPercentage=75")
        creationTime = "USE_CURRENT_TIMESTAMP"
    }
}
