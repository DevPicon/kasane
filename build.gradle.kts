import com.google.protobuf.gradle.*

plugins {
    kotlin("jvm") version "2.3.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21"
    id("org.jetbrains.compose") version "1.10.3"
    id("com.google.protobuf") version "0.9.4"
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material3:material3:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("io.grpc:grpc-netty-shaded:1.69.1")
    implementation("io.grpc:grpc-protobuf:1.69.1")
    implementation("io.grpc:grpc-stub:1.69.1")
    implementation("io.grpc:grpc-kotlin-stub:1.4.1")
    implementation("com.google.protobuf:protobuf-java:3.25.5")
    compileOnly("javax.annotation:javax.annotation-api:1.3.2")
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.jetbrains.compose.ui:ui-test-junit4:1.10.3")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.4")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:3.25.5" }
    plugins {
        create("grpc") { artifact = "io.grpc:protoc-gen-grpc-java:1.69.1" }
        create("grpckt") { artifact = "io.grpc:protoc-gen-grpc-kotlin:1.4.1:jdk8@jar" }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                create("grpc")
                create("grpckt")
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "dev.picon.androidhub.MainKt"
        nativeDistributions {
            packageName = "Kasane"
            packageVersion = providers.gradleProperty("appVersion").get()
            vendor = "Armando Picón (@devpicon)"
            description = "A macOS hub for Android emulators"
            macOS { bundleID = "dev.picon.kasane" }
        }
    }
}

tasks.test { useJUnitPlatform() }

tasks.register<JavaExec>("probe") {
    description = "Validate the connection to a running emulator without opening the UI."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.picon.androidhub.TransportProbeKt")
    if (providers.gradleProperty("exercise").orNull == "true") args("--exercise")
    if (providers.gradleProperty("controls").orNull == "true") args("--controls")
    providers.gradleProperty("serial").orNull?.let { args("--serial", it) }
}
