// couchlink: a zero-dependency LAN HTTP + WebSocket server that turns phones into controllers
// for a TV app. Plain JVM (java.net only), so it runs on Fire OS 6+ and on a desktop JVM for tests.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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

// "harness" = a desktop simulator of the TV side, used to test the phone page in a normal browser.
sourceSets {
    create("harness") {
        compileClasspath += sourceSets["main"].output
        runtimeClasspath += sourceSets["main"].output
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    "harnessImplementation"("org.json:json:20240303")
}

tasks.register<JavaExec>("runPhoneHarness") {
    group = "couchlink"
    description = "Serves app/src/main/assets on :8080 with a simulated TV so the phone page can be tested in a desktop browser."
    classpath = sourceSets["harness"].runtimeClasspath
    mainClass.set("dev.couchlink.harness.PhoneHarnessKt")
    args(rootProject.file("app/src/main/assets").absolutePath, (project.findProperty("port") as String?) ?: "8080")
}
