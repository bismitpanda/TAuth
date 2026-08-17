package com.panda.tauth

// A run from the build tree is not an installed application and carries no packaged version.
internal const val DEVELOPMENT_VERSION = "development build"

// The repository declares no licence, and the About group states what is true of the build it runs in.
internal const val LICENCE_NOTICE = "None declared"

// jpackage records the version it packaged as a system property of the launched application.
internal fun applicationVersion(property: (String) -> String? = System::getProperty): String =
    property("jpackage.app-version")?.takeIf { it.isNotBlank() } ?: DEVELOPMENT_VERSION
