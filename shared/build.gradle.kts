plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinter)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kotlinSerialization)
}

// kotlinter owns formatting and reads its rules from .editorconfig.
// detekt owns code smells only; its formatting ruleset is deliberately absent so
// the two tools cannot disagree about the same line.
detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.files("config/detekt/detekt.yml"))
    parallel = true
}

tasks.withType<Test>().configureEach {
    // The Compose test rule renders off-screen into a Skiko surface. Pinning headless
    // keeps the suite independent of whether the machine running it has a display.
    systemProperty("java.awt.headless", "true")
}

kotlin {
    jvm()


    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
        }
        jvmMain.dependencies {
            implementation(libs.bouncycastle.prov)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmTest.dependencies {
            implementation(libs.compose.uiTestJUnit4)
            // Compose's JVM artifacts carry the Skiko Java API but not its native library, and the
            // test rule renders for real. The classifier is the build host's, which only the plugin
            // can pick, so this one has no coordinate for the catalog to hold.
            implementation(compose.desktop.currentOs)
        }
    }
}