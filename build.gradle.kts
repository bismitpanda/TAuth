import dev.detekt.gradle.Detekt
import org.jmailen.gradle.kotlinter.tasks.ConfigurableKtLintTask

plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.kotlinter) apply false
}

subprojects {
    // Compose's resource generator writes Kotlin into build/generated. It is not
    // ours to format, and ktlint's own output is not stable across regeneration.
    // For detekt this also cuts analysis errors from 18 to 3 on the current sources.
    // detekt/detekt#9304 reports task-level excludes dropping files from type
    // resolution under AGP 9; that does not bite here, because the generated classes
    // still reach the analysis through the compiled output on the classpath. Recheck
    // if generated code starts producing unresolved-reference errors.
    val isGenerated = { path: String -> "${File.separator}build${File.separator}" in path }

    tasks.withType<ConfigurableKtLintTask>().configureEach {
        exclude { isGenerated(it.file.absolutePath) }
    }

    tasks.withType<Detekt>().configureEach {
        exclude { isGenerated(it.file.absolutePath) }
        reports {
            html.required.set(true)
            sarif.required.set(true)
            checkstyle.required.set(false)
            markdown.required.set(false)
        }
    }

    // In a KMP module the aggregate `detekt` task is NO-SOURCE: the analysable
    // tasks are per-compilation. Without this, `check` silently analyses nothing
    // in :shared, which is where nearly all the code lives.
    //
    // The *SourceSet tasks run without type resolution. The per-compilation
    // alternatives (detektMainJvm, detektMain…) would enable it, and are not used:
    // detekt/detekt#9602 makes a type-resolution task spanning two KMP source sets
    // analyse `expect` and `actual` as one fragment, so type-aware rules misfire in
    // both directions while the build still reports success. The upstream report
    // documents false UnusedPrivateProperty findings nearly acted on as dead code.
    // That is the wrong failure mode for a module whose contents are cryptographic.
    //
    // Type checking is the Kotlin compiler's job and is unaffected by this. What is
    // given up is the subset of detekt's own smell rules that need type information;
    // those rules are skipped rather than run badly. Switch to the per-compilation
    // tasks once #9602 is fixed.
    //
    // "Dev" is Compose's hot-reload compilation and duplicates main.
    val detektAnalysis = tasks.withType<Detekt>().matching { task ->
        task.name.endsWith("SourceSet") && !task.name.contains("Dev")
    }
    // `matching` rather than `named`: this block runs before the subproject's own
    // build script applies the plugin that creates `check`.
    tasks.matching { it.name == "check" }.configureEach { dependsOn(detektAnalysis) }
}
