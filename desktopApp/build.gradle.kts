import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinter)
    alias(libs.plugins.detekt)
}

// kotlinter owns formatting and reads its rules from .editorconfig.
// detekt owns code smells only; its formatting ruleset is deliberately absent so
// the two tools cannot disagree about the same line.
detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.files("config/detekt/detekt.yml"))
    parallel = true
}

dependencies {
    implementation(project(":shared"))

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)

    implementation(libs.compose.uiToolingPreview)

    testImplementation(libs.kotlin.test)
}

// The launcher records `mainClass` as a string and resolves it at startup, so nothing in
// the build fails when it names a class that does not exist. Handing the declaration
// itself to the test puts the assertion on it rather than on a copy of it.
tasks.test {
    systemProperty(
        "tauth.mainClass",
        requireNotNull(compose.desktop.application.mainClass) { "compose.desktop.application.mainClass is unset" },
    )
}

compose.desktop {
    application {
        mainClass = "com.panda.tauth.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "com.panda.tauth"
            packageVersion = "1.0.0"
        }
    }
}