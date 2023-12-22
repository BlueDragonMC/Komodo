plugins {
    kotlin("jvm") version "1.9.0"
    kotlin("kapt") version "1.9.0"
    id("com.github.johnrengelman.shadow") version "7.0.0"
}

rootProject.group = "com.bluedragonmc"
rootProject.version = "0.1.0"

repositories {
    mavenCentral()
    maven(url = "https://repo.papermc.io/repository/maven-public/")
    maven(url = "https://jitpack.io")
    mavenLocal()
}

val grpcKotlinVersion = "1.3.0"
val protoVersion = "3.21.9"
val grpcVersion = "1.51.0"

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.1")
    compileOnly("com.velocitypowered:velocity-api:3.1.1")
    implementation("net.kyori:adventure-text-minimessage:4.11.0")
    kapt("com.velocitypowered:velocity-api:3.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")

    implementation("io.grpc:grpc-netty:$grpcVersion")
    implementation("io.grpc:grpc-protobuf:$grpcVersion")
    implementation("io.grpc:grpc-kotlin-stub:$grpcKotlinVersion")
    implementation("com.google.protobuf:protobuf-kotlin:$protoVersion")
    implementation("com.github.bluedragonmc:rpc:b7071251fb")
    implementation("com.github.BlueDragonMC:Jukebox:3b2e9f051a")
}

tasks.shadowJar {
    mergeServiceFiles()
}

tasks["build"].dependsOn(tasks["shadowJar"])