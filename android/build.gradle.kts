plugins {
    // AGP 9.0 起内置 Kotlin 支持，无需再单独声明 org.jetbrains.kotlin.android
    id("com.android.application") version "9.4.1" apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
