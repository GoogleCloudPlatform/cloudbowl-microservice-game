import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    application
    kotlin("jvm") version "1.3.61"
    kotlin("kapt") version "1.3.61"
}

repositories {
    mavenLocal()
    mavenCentral()
    jcenter()
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.3.3")

    implementation("io.micronaut:micronaut-runtime:1.3.0")
    implementation("io.micronaut:micronaut-http-server-netty:1.3.0")
    implementation("ch.qos.logback:logback-classic:1.2.3")

    runtimeOnly("com.fasterxml.jackson.module:jackson-module-kotlin:2.9.7")

    kapt("io.micronaut:micronaut-inject-java:1.3.0")
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        jvmTarget = "1.8"
        javaParameters = true
    }
}

application {
    mainClassName = "hello.WebAppKt"
}

tasks.register<DefaultTask>("stage") {
    dependsOn("installDist")
}
