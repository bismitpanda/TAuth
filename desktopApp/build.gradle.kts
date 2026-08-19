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
    implementation(libs.compose.components.resources)
    implementation(libs.composeNativeTray)
    implementation(libs.kotlinx.coroutinesSwing)

    implementation(libs.compose.uiToolingPreview)

    implementation(libs.zxing.core)
    implementation(libs.zxing.javase)

    testImplementation(libs.kotlin.test)
}

// Named rather than derived from the project name, so the accessors the drawable generates sit in
// this application's own namespace.
compose.resources {
    packageOfResClass = "com.panda.tauth.resources"
}

// The launcher records `mainClass` as a string and resolves it at startup, so nothing in
// the build fails when it names a class that does not exist. Handing the declaration
// itself to the test puts the assertion on it rather than on a copy of it.
tasks.test {
    systemProperty(
        "tauth.mainClass",
        requireNotNull(compose.desktop.application.mainClass) { "compose.desktop.application.mainClass is unset" },
    )
    // jpackage resolves the declared icons only when it packages, so a rename in `icons/` fails at
    // release rather than here. Handing the declarations themselves over puts the assertion on them.
    val distributions = compose.desktop.application.nativeDistributions
    val icons = listOf(distributions.linux.iconFile, distributions.macOS.iconFile, distributions.windows.iconFile)
    systemProperty("tauth.icon.linux", icons[0].get().asFile.path)
    systemProperty("tauth.icon.macos", icons[1].get().asFile.path)
    systemProperty("tauth.icon.windows", icons[2].get().asFile.path)
    // Named as inputs as well as passed across: a task that does not re-run when one of them is
    // renamed away would report on the files as they stood at the last run.
    inputs.files(icons).withPropertyName("declaredIcons").optional()
}

compose.desktop {
    application {
        mainClass = "com.panda.tauth.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "TAuth"
            packageVersion = "1.0.0"
            description = "A desktop authenticator for time-based one-time codes"
            vendor = "TAuth"
            copyright = "TAuth contributors"

            // Traded against installer size: a stripped runtime fails at the module a code path
            // reaches only on one platform, and the failure lands on the user rather than the build.
            includeAllModules = true

            // Cut from src/main/composeResources/drawable/tauth.svg, which is the mark itself and
            // what the application reads for its tray and title bar. jpackage takes these three.
            val icons = project.layout.projectDirectory.dir("icons")

            linux {
                iconFile.set(icons.file("tauth.png"))
                packageName = "tauth"
                debMaintainer = "tauth@localhost"
                menuGroup = "Utility"
                appCategory = "utils"
            }

            macOS {
                iconFile.set(icons.file("tauth.icns"))
                // The identifier a keychain entry and a Screen Recording grant are remembered
                // against, so it is fixed here rather than derived from the package name.
                bundleID = "com.panda.tauth"
                dockName = "TAuth"
            }

            windows {
                iconFile.set(icons.file("tauth.ico"))
                menuGroup = "TAuth"
                // Fixed for the lifetime of the application: a changed one installs a second copy
                // beside the first rather than upgrading it.
                upgradeUuid = "6d2b7f41-9a3e-4c58-b0d1-7e4a2c9f5b83"
            }
        }
    }
}