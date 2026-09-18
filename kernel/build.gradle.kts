import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("kotlin")
    application
}

kotlin.compilerOptions.jvmTarget = JvmTarget.JVM_1_8
java.targetCompatibility = JavaVersion.VERSION_1_8

dependencies {
    implementation(project(":core"))
    implementation(libs.gdx)
    implementation(libs.gdx.backend.headless)
    implementation(libs.ktor.serialization)
    testImplementation(libs.junit)
}

application.mainClass.set("com.unciv.godot.GatewayMainKt")

tasks.named<JavaExec>("run") {
    workingDir = rootProject.file("android/assets")
    args("--root", rootProject.projectDir.absolutePath)
}

tasks.test {
    workingDir = rootProject.file("android/assets")
    systemProperty("unciv.root", rootProject.projectDir.absolutePath)
    // Godot 闭环会读取这些原格式场景；缺失时应重新执行测试以生成存档。
    outputs.files(rootProject.file("godot/.local/tests/start.json"),
        rootProject.file("godot/.local/tests/settlement-promise.json"))
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = true }
}
