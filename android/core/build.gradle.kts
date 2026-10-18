import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin/JVM: OTP engine and Noise wrappers, shared by the app and testable without a phone.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation("org.signal.forks:noise-java:0.1.1")
    // Argon2id for backup passwords.
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")
    // Android ships org.json; plain JVM tests get it below.
    compileOnly("org.json:json:20240303")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.json:json:20240303")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    val repoRoot = rootDir.parentFile
    systemProperty("totem.vectors", repoRoot.resolve("test-vectors").path)
    // Sample Aegis / 2FAS exports, fetched by scripts/fetch-import-samples.sh (GPL, so not in this repo).
    systemProperty("totem.importSamples", repoRoot.resolve("target/foreign-fixtures").path)
    // Built by `cargo build -p totem-protocol --example noise_peer`; interop tests are skipped without it.
    val exe = if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""
    systemProperty("totem.rustPeer", repoRoot.resolve("target/debug/examples/noise_peer$exe").path)
    testLogging { events("passed", "skipped", "failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
