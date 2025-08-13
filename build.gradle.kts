import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.1.10"
    kotlin("kapt") version "2.1.10"
    id("com.gradleup.shadow") version "9.0.1"
}

rootProject.group = "com.bluedragonmc"
rootProject.version = "0.1.0"

repositories {
    mavenCentral()
    maven(url = "https://repo.papermc.io/repository/maven-public/")
    maven(url = "https://jitpack.io")
    mavenLocal()
}

val grpcKotlinVersion = "1.4.1"
val protoVersion = "4.30.1"
val grpcVersion = "1.71.0"
val velocityVersion = "3.4.0-SNAPSHOT"

dependencies {
    compileOnly("com.velocitypowered:velocity-api:$velocityVersion")
    kapt("com.velocitypowered:velocity-api:$velocityVersion")

    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    implementation("com.github.ben-manes.caffeine:caffeine:3.2.0")
    implementation("net.kyori:adventure-text-minimessage:4.19.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")

    implementation("io.grpc:grpc-netty:$grpcVersion")
    implementation("io.grpc:grpc-protobuf:$grpcVersion")
    implementation("io.grpc:grpc-kotlin-stub:$grpcKotlinVersion")
    implementation("com.google.protobuf:protobuf-kotlin:$protoVersion")
    implementation("com.github.bluedragonmc:rpc:d40ac743b5")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

tasks.shadowJar {
    mergeServiceFiles()
}

tasks["build"].dependsOn(tasks["shadowJar"])