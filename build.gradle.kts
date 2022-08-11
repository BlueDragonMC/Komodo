plugins {
    kotlin("jvm") version "1.7.10"
    kotlin("kapt") version "1.7.10"
    id("com.github.johnrengelman.shadow") version "4.0.0"
}

rootProject.group = "com.bluedragonmc"
rootProject.version = "0.1.0"

repositories {
    mavenCentral()
    maven(url = "https://repo.papermc.io/repository/maven-public/")
    maven(url = "https://jitpack.io")
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("com.github.bluedragonmc:messagingsystem:3abc4b8a49")
    implementation("com.github.bluedragonmc:messages:a2a08c9d8e")
    compileOnly("com.velocitypowered:velocity-api:3.0.1")
    implementation("io.kubernetes:client-java:16.0.0")
    kapt("com.velocitypowered:velocity-api:3.0.1")
}

tasks["build"].dependsOn(tasks["shadowJar"]) // have it so `gradle build` builds the shadow jar