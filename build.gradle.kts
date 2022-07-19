plugins {
    kotlin("jvm") version "1.7.10"
    kotlin("kapt") version "1.7.10"
    id("com.github.johnrengelman.shadow") version "4.0.0"
}

rootProject.group = "com.bluedragonmc"
rootProject.version = "0.0.1"

repositories {
    mavenCentral()
    maven(url = "https://repo.papermc.io/repository/maven-public/")
    maven(url = "https://jitpack.io")
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("com.github.bluedragonmc:messagingsystem:3abc4b8a49")
    implementation("com.github.bluedragonmc:messages:e8603cbe6e")
    compileOnly("com.velocitypowered:velocity-api:3.0.1")
    kapt("com.velocitypowered:velocity-api:3.0.1")
}

tasks["build"].dependsOn(tasks["shadowJar"]) // have it so `gradle build` builds the shadow jar