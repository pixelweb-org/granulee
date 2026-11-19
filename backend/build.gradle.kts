plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.spring") version "2.4.21"
    id("org.springframework.boot") version "4.1.2"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "io.granulee"
version = "0.1.0"

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
