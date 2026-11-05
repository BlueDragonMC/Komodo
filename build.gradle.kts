plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.shadow)
}

rootProject.group = "com.bluedragonmc"
rootProject.version = "0.1.0"

repositories {
    mavenCentral()
    maven(url = "https://repo.papermc.io/repository/maven-public/")
    maven(url = "https://reposilite.bluedragonmc.com/releases")
    mavenLocal()
}

dependencies {
    compileOnly(libs.velocity.api)
    kapt(libs.velocity.api)

    implementation(libs.kotlin.stdlib)
    implementation(libs.caffeine)
    implementation(libs.minimessage)
    implementation(libs.coroutines.core)

    implementation(libs.grpc.netty)
    implementation(libs.grpc.protobuf)
    implementation(libs.grpc.kotlin.stub)
    implementation(libs.protobuf.kotlin)
    implementation(libs.rpc)
}

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(libs.versions.jvm.get().toInt()))
    }
}

tasks.shadowJar {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
}

tasks["build"].dependsOn(tasks["shadowJar"])