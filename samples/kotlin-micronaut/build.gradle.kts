import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    application
    kotlin("jvm") version "1.4.21"
    kotlin("kapt") version "1.4.21"
}

repositories {
    mavenLocal()
    mavenCentral()
    jcenter()
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.4.2")

    implementation("io.micronaut:micronaut-runtime:2.2.3")
    implementation("io.micronaut:micronaut-http-server-netty:2.2.3")
    implementation("ch.qos.logback:logback-classic:1.2.3")

    runtimeOnly("com.fasterxml.jackson.module:jackson-module-kotlin:2.9.7")

    kapt("io.micronaut:micronaut-inject-java:2.2.3")
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_1_8.toString()
        javaParameters = true
    }
}

application {
    mainClassName = "hello.WebAppKt"
}

tasks.replace("assemble").dependsOn("installDist")

tasks.register<DefaultTask>("stage") {
    dependsOn("installDist")
}
