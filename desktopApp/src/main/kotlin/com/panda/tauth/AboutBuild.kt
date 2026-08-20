package com.panda.tauth

// A run from the build tree is not an installed application and carries no packaged version.
internal const val DEVELOPMENT_VERSION = "development build"

internal const val LICENCE_NOTICE = "Apache-2.0. Bundled fonts are under the SIL Open Font License 1.1."

// jpackage records the version it packaged as a system property of the launched application.
internal fun applicationVersion(property: (String) -> String? = System::getProperty): String =
    property("jpackage.app-version")?.takeIf { it.isNotBlank() } ?: DEVELOPMENT_VERSION
