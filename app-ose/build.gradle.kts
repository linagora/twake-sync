/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.mikepenz.aboutLibraries.android)
    id("davx5.common-buildconfig")
}

val keystorePropertiesFile = rootProject.file("key.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists())
        keystorePropertiesFile.inputStream().use { stream -> load(stream) }
}

android {
    defaultConfig {
        applicationId = "com.twake.android.sync"

        // versionCode is defined in the build-logic submodule (AppVersion); Twake Sync uses date-based version names
        versionName = "2026.03.24.01"

        base.archivesName = "twake-sync-$versionCode-$versionName"

        /* Android prevents having two apps installed with the same provider authority name. In that case,
        Google Play just shows a generic "Can't install DAVx5" message. So we derive the authority names
        from the package ID, so that the build variants (and clones) have their own authority names and
        can be installed beside DAVx5. */
        val webdavAuthority = "${applicationId}.provider.webdav"
        val debugInfoAuthority = "${applicationId}.provider.debuginfo"
        manifestPlaceholders["webdavAuthority"] = webdavAuthority
        manifestPlaceholders["debugInfoAuthority"] = debugInfoAuthority
        /* Override the default string values from the core library (core/src/main/res/values/strings.xml)
        so that code using getString(R.string.webdav_authority) etc. gets the correct authority. */
        resValue("string", "webdav_authority", webdavAuthority)
        resValue("string", "authority_debug_provider", debugInfoAuthority)

        // Currently no instrumentation tests for app-ose, so no testInstrumentationRunner
    }

    buildFeatures {
        compose = true
        resValues = true
    }

    // Java namespace for our classes (not to be confused with Android package ID)
    namespace = "com.davx5.ose"

    flavorDimensions += "distribution"
    productFlavors {
        create("ose") {
            dimension = "distribution"
            versionNameSuffix = "-ose"
        }
    }

    androidResources {
        generateLocaleConfig = true
    }

    // The dependencies info block is only useful for Google Play [https://developer.android.com/build/dependencies#dependency-info-play]
    // and encrypted with Google's public key (encryption is non-deterministic by design → breaks reproducible builds).
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    @Suppress("UnstableApiUsage")
    testOptions {
        managedDevices {
            localDevices {
                create("virtual") {
                    device = "Pixel 3"
                    // TBD: API level 35 and higher causes network tests to fail sometimes, see https://github.com/bitfireAT/davx5-ose/issues/1525
                    // Suspected reason: https://developer.android.com/about/versions/15/behavior-changes-all#background-network-access
                    apiLevel = 34
                    systemImageSource = "aosp-atd"
                }
            }
        }
    }

    signingConfigs {
        create("bitfire") {
            if (keystorePropertiesFile.exists()) {
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                storeFile = file(keystoreProperties.getProperty("storeFile") ?: "/dev/null")
                storePassword = keystoreProperties.getProperty("storePassword")
            } else {
                storeFile = file(System.getenv("ANDROID_KEYSTORE") ?: "/dev/null")
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules-release.pro")

            isShrinkResources = true

            // Must be after signingConfigs {} block. Uncomment following line to test release build with debug signing key:
            // signingConfig = signingConfigs.getByName("debug")
            signingConfig = signingConfigs.findByName("bitfire")
        }
    }
}

dependencies {
    // include core subproject (manages its own dependencies itself, however from same version catalog)
    implementation(project(":core"))

    // Kotlin / Android
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines)
    coreLibraryDesugaring(libs.android.desugaring)

    // Hilt
    implementation(libs.hilt.android.base)
    ksp(libs.androidx.hilt.compiler)
    ksp(libs.hilt.android.compiler)

    // support libs
    implementation(libs.androidx.core)
    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.lifecycle.viewmodel.base)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.work.base)

    // Jetpack Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.ui.toolingPreview)

    // own libraries
    implementation(libs.bitfire.cert4android)

    // third-party libs
    implementation(libs.guava)
    implementation(libs.okhttp.base)
    implementation(libs.openid.appauth)
}

// Translations pulled from l10n still contain the DAVx⁵/ICSx⁵ brand. Each of them must be overridden
// in src/main/res/values-*/strings_twake.xml, otherwise a new locale or string would show the
// upstream brand after bumping the l10n submodule.
val verifyTwakeBranding by tasks.registering {
    val translationsDir = file("$rootDir/l10n/translations/ose")   // local copies: the check below must
    val overridesDir = file("src/main/res")                         // not capture the build script

    doLast {
        val stringRegex = Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        val brandRegex = Regex("DAVx|ICSx")
        // the download hint points to the real DAVx⁵ app on purpose
        val ignored = setOf("login_download_button", "login_download_regular_davx5")

        val missing = translationsDir.listFiles { f -> f.isDirectory && f.name.startsWith("values-") }.orEmpty()
            .sortedBy { it.name }
            .flatMap { localeDir ->
                val translations = File(localeDir, "strings.xml").takeIf { it.exists() } ?: return@flatMap emptyList()
                val overridesFile = File(overridesDir, "${localeDir.name}/strings_twake.xml")
                val overridden = if (overridesFile.exists())
                    stringRegex.findAll(overridesFile.readText()).map { it.groupValues[1] }.toSet()
                else
                    emptySet()

                stringRegex.findAll(translations.readText())
                    .filter { brandRegex.containsMatchIn(it.groupValues[2]) }
                    .map { it.groupValues[1] }
                    .filter { it !in ignored && it !in overridden }
                    .map { "${localeDir.name}: $it" }
                    .toList()
            }

        require(missing.isEmpty()) {
            "Translations with the DAVx⁵ brand are not overridden in app-ose/src/main/res/values-*/strings_twake.xml:\n" +
                    missing.joinToString("\n")
        }
    }
}

tasks.named("preBuild") {
    dependsOn(verifyTwakeBranding)
}