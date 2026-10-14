plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21"
}

import java.io.File
import java.util.Properties
import groovy.json.JsonOutput
import org.gradle.api.GradleException

abstract class GenerateReleaseSbom : DefaultTask() {
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty
}

// Config di firma letta da release/keystore.properties (non tracciato) o da env vars
val keystorePropertiesFileCandidates = listOf(
    rootProject.file("release/keystore.properties"),
    rootProject.file("keystore.properties")
)
val keystorePropertiesFile = keystorePropertiesFileCandidates.firstOrNull { it.exists() }
    ?: keystorePropertiesFileCandidates.last()
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

fun signingProp(key: String, env: String): String? =
    keystoreProperties.getProperty(key)?.takeIf { it.isNotBlank() }
        ?: System.getenv(env)?.takeIf { it.isNotBlank() }

fun resolveSigningStoreFile(storePath: String): File =
    if (File(storePath).isAbsolute) {
        File(storePath)
    } else {
        keystorePropertiesFile.parentFile.resolve(storePath)
    }

fun hasSigningConfig(storePath: String?, storePass: String?, alias: String?, keyPass: String?): Boolean =
    storePath != null && storePass != null && alias != null && keyPass != null

fun gradleBooleanProperty(name: String): Boolean =
    providers.gradleProperty(name).orNull?.equals("true", ignoreCase = true) == true

fun shouldValidateNightlySigning(taskNames: List<String>): Boolean {
    if (taskNames.isEmpty()) {
        return true
    }
    val signingTaskHints = listOf(
        "assembleNightlyRelease",
        "bundleNightlyRelease",
        "packageNightlyRelease",
        "publishNightlyRelease",
        "installNightlyRelease",
        "assembleNightlyNoAccessibilityRelease",
        "bundleNightlyNoAccessibilityRelease",
        "packageNightlyNoAccessibilityRelease",
        "publishNightlyNoAccessibilityRelease",
        "installNightlyNoAccessibilityRelease"
    )
    return taskNames.any { task ->
        signingTaskHints.any { hint -> task.contains(hint, ignoreCase = true) }
    }
}

fun shouldValidateStableSigning(taskNames: List<String>): Boolean {
    if (taskNames.isEmpty()) {
        return true
    }
    val signingTaskHints = listOf(
        "assembleStableRelease",
        "bundleStableRelease",
        "packageStableRelease",
        "publishStableRelease",
        "installStableRelease"
    )
    return taskNames.any { task ->
        signingTaskHints.any { hint -> task.contains(hint, ignoreCase = true) }
    }
}

fun shouldValidatePlaySigning(taskNames: List<String>): Boolean =
    taskNames.any { task ->
        task.contains("PlayStableRelease", ignoreCase = true) ||
            task.contains("PlayNightlyRelease", ignoreCase = true)
    }

android {
    namespace = "rocks.pkb.plektra"
    compileSdk = 36

    val defaultVersionCode = 26090000
    val defaultVersionName = "26.09"
    val ciVersionCode = providers.gradleProperty("PLEKTRA_VERSION_CODE").orNull?.toIntOrNull()
    val ciVersionName = providers.gradleProperty("PLEKTRA_VERSION_NAME").orNull
    val nightlyVersionCode = providers.gradleProperty("PLEKTRA_NIGHTLY_VERSION_CODE").orNull?.toIntOrNull()
    val nightlyVersionNameSuffix = providers.gradleProperty("PLEKTRA_NIGHTLY_VERSION_SUFFIX").orNull ?: "-nightly"
    val isFdroidBuild = gradleBooleanProperty("PLEKTRA_FDROID_BUILD")
    val isUnsignedReleaseBuild = gradleBooleanProperty("PLEKTRA_UNSIGNED_RELEASE_BUILD")
    val githubRepository = providers.gradleProperty("PLEKTRA_GITHUB_REPOSITORY")
        .orNull ?: "pkb-rocks/plektra"
    if (!githubRepository.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) {
        throw GradleException(
            "PLEKTRA_GITHUB_REPOSITORY must have the form owner/repository"
        )
    }

    defaultConfig {
        applicationId = "rocks.pkb.plektra"
        minSdk = 29
        targetSdk = 36
        versionCode = ciVersionCode ?: defaultVersionCode
        versionName = ciVersionName ?: defaultVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GITHUB_REPOSITORY", "\"$githubRepository\"")
        buildConfigField("String", "APK_VARIANT", "\"full\"")
        buildConfigField("boolean", "ENABLE_ACCESSIBILITY_SERVICE", "true")
    }

    signingConfigs {
        create("release") {
            val storePath = signingProp("storeFile", "PLEKTRA_KEYSTORE_PATH")
            val storePass = signingProp("storePassword", "PLEKTRA_KEYSTORE_PASSWORD")
            val alias = signingProp("keyAlias", "PLEKTRA_KEY_ALIAS")
            val keyPass = signingProp("keyPassword", "PLEKTRA_KEY_PASSWORD")

            // Only configure signing if all credentials are provided
            if (hasSigningConfig(storePath, storePass, alias, keyPass)) {
                val resolvedStoreFile = resolveSigningStoreFile(storePath!!)
                storeFile = resolvedStoreFile
                storePassword = storePass
                keyAlias = alias
                keyPassword = keyPass
            }
        }
        create("nightly") {
            val storePath = signingProp("nightlyStoreFile", "PLEKTRA_NIGHTLY_KEYSTORE_PATH")
            val storePass = signingProp("nightlyStorePassword", "PLEKTRA_NIGHTLY_KEYSTORE_PASSWORD")
            val alias = signingProp("nightlyKeyAlias", "PLEKTRA_NIGHTLY_KEY_ALIAS")
            val keyPass = signingProp("nightlyKeyPassword", "PLEKTRA_NIGHTLY_KEY_PASSWORD")

            if (hasSigningConfig(storePath, storePass, alias, keyPass)) {
                val resolvedStoreFile = resolveSigningStoreFile(storePath!!)
                storeFile = resolvedStoreFile
                storePassword = storePass
                keyAlias = alias
                keyPassword = keyPass
            }
        }

    }

    flavorDimensions += "channel"

    productFlavors {
        create("stable") {
            dimension = "channel"
            manifestPlaceholders["appLabel"] = "Plektra"
            manifestPlaceholders["imeLabel"] = "Plektra"
            manifestPlaceholders["appIcon"] = "@mipmap/ic_launcher"
            manifestPlaceholders["appRoundIcon"] = "@mipmap/ic_launcher_round"
            manifestPlaceholders["closedOrangeLauncherEnabled"] = "true"
            manifestPlaceholders["openOrangeLauncherEnabled"] = "false"
            manifestPlaceholders["closedBlueLauncherEnabled"] = "false"
            manifestPlaceholders["openBlueLauncherEnabled"] = "false"
            buildConfigField("String", "RELEASE_CHANNEL", "\"stable\"")
            buildConfigField("String", "DISTRIBUTION_CHANNEL", "\"direct\"")
            buildConfigField("boolean", "IS_FDROID_BUILD", if (isFdroidBuild) "true" else "false")
            buildConfigField("boolean", "ENABLE_GITHUB_UPDATE_CHECKS", if (isFdroidBuild) "false" else "true")
            val storePath = signingProp("storeFile", "PLEKTRA_KEYSTORE_PATH")
            val storePass = signingProp("storePassword", "PLEKTRA_KEYSTORE_PASSWORD")
            val alias = signingProp("keyAlias", "PLEKTRA_KEY_ALIAS")
            val keyPass = signingProp("keyPassword", "PLEKTRA_KEY_PASSWORD")
            if (!isUnsignedReleaseBuild && hasSigningConfig(storePath, storePass, alias, keyPass)) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        create("nightly") {
            dimension = "channel"
            applicationIdSuffix = ".nightly"
            if (nightlyVersionCode != null) {
                versionCode = nightlyVersionCode
            }
            versionNameSuffix = nightlyVersionNameSuffix
            manifestPlaceholders["appLabel"] = "Plektra Nightly"
            manifestPlaceholders["imeLabel"] = "Plektra Nightly"
            manifestPlaceholders["appIcon"] = "@mipmap/ic_launcher_nightly"
            manifestPlaceholders["appRoundIcon"] = "@mipmap/ic_launcher_nightly_round"
            manifestPlaceholders["closedOrangeLauncherEnabled"] = "false"
            manifestPlaceholders["openOrangeLauncherEnabled"] = "false"
            manifestPlaceholders["closedBlueLauncherEnabled"] = "true"
            manifestPlaceholders["openBlueLauncherEnabled"] = "false"
            buildConfigField("String", "RELEASE_CHANNEL", "\"nightly\"")
            buildConfigField("String", "DISTRIBUTION_CHANNEL", "\"direct\"")
            buildConfigField("boolean", "IS_FDROID_BUILD", if (isFdroidBuild) "true" else "false")
            buildConfigField("boolean", "ENABLE_GITHUB_UPDATE_CHECKS", if (isFdroidBuild) "false" else "true")
            val storePath = signingProp("nightlyStoreFile", "PLEKTRA_NIGHTLY_KEYSTORE_PATH")
            val storePass = signingProp("nightlyStorePassword", "PLEKTRA_NIGHTLY_KEYSTORE_PASSWORD")
            val alias = signingProp("nightlyKeyAlias", "PLEKTRA_NIGHTLY_KEY_ALIAS")
            val keyPass = signingProp("nightlyKeyPassword", "PLEKTRA_NIGHTLY_KEY_PASSWORD")
            if (!isUnsignedReleaseBuild && hasSigningConfig(storePath, storePass, alias, keyPass)) {
                signingConfig = signingConfigs.getByName("nightly")
            }
        }
        create("nightlyNoAccessibility") {
            initWith(getByName("nightly"))
            buildConfigField("String", "APK_VARIANT", "\"no-accessibility\"")
            buildConfigField("boolean", "ENABLE_ACCESSIBILITY_SERVICE", "false")
        }
        create("playStable") {
            dimension = "channel"
            applicationIdSuffix = ".unsovereign"
            manifestPlaceholders["appLabel"] = "Plektra"
            manifestPlaceholders["imeLabel"] = "Plektra"
            manifestPlaceholders["appIcon"] = "@mipmap/ic_launcher"
            manifestPlaceholders["appRoundIcon"] = "@mipmap/ic_launcher_round"
            manifestPlaceholders["closedOrangeLauncherEnabled"] = "true"
            manifestPlaceholders["openOrangeLauncherEnabled"] = "false"
            manifestPlaceholders["closedBlueLauncherEnabled"] = "false"
            manifestPlaceholders["openBlueLauncherEnabled"] = "false"
            buildConfigField("String", "RELEASE_CHANNEL", "\"stable\"")
            buildConfigField("String", "DISTRIBUTION_CHANNEL", "\"play\"")
            buildConfigField("boolean", "IS_FDROID_BUILD", "false")
            buildConfigField("boolean", "ENABLE_GITHUB_UPDATE_CHECKS", "false")
        }
        create("playNightly") {
            dimension = "channel"
            applicationIdSuffix = ".unsovereign.nightly"
            if (nightlyVersionCode != null) {
                versionCode = nightlyVersionCode
            }
            versionNameSuffix = nightlyVersionNameSuffix
            manifestPlaceholders["appLabel"] = "Plektra Nightly"
            manifestPlaceholders["imeLabel"] = "Plektra Nightly"
            manifestPlaceholders["appIcon"] = "@mipmap/ic_launcher_nightly"
            manifestPlaceholders["appRoundIcon"] = "@mipmap/ic_launcher_nightly_round"
            manifestPlaceholders["closedOrangeLauncherEnabled"] = "false"
            manifestPlaceholders["openOrangeLauncherEnabled"] = "false"
            manifestPlaceholders["closedBlueLauncherEnabled"] = "true"
            manifestPlaceholders["openBlueLauncherEnabled"] = "false"
            buildConfigField("String", "RELEASE_CHANNEL", "\"nightly\"")
            buildConfigField("String", "DISTRIBUTION_CHANNEL", "\"play\"")
            buildConfigField("boolean", "IS_FDROID_BUILD", "false")
            buildConfigField("boolean", "ENABLE_GITHUB_UPDATE_CHECKS", "false")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Disable lint for release to avoid file lock issues
            isDebuggable = false
        }
    }
    
    // Validate signing config only when building release
    tasks.whenTaskAdded {
        if (!isFdroidBuild && name.equals("preStableReleaseBuild", ignoreCase = true)) {
            doFirst {
                if (isUnsignedReleaseBuild) {
                    logger.lifecycle("Building an unsigned stable release for separate PIV signing.")
                    return@doFirst
                }
                if (!shouldValidateStableSigning(gradle.startParameter.taskNames)) {
                    logger.lifecycle("Skipping stable signing validation for non-packaging task(s): ${gradle.startParameter.taskNames}")
                    return@doFirst
                }
                val storePath = signingProp("storeFile", "PLEKTRA_KEYSTORE_PATH")
                val storePass = signingProp("storePassword", "PLEKTRA_KEYSTORE_PASSWORD")
                val alias = signingProp("keyAlias", "PLEKTRA_KEY_ALIAS")
                val keyPass = signingProp("keyPassword", "PLEKTRA_KEY_PASSWORD")

                if (!hasSigningConfig(storePath, storePass, alias, keyPass)) {
                    throw GradleException(
                        "Missing signing config for release build. Define storeFile, storePassword, keyAlias e keyPassword in " +
                            "keystore.properties (non tracciato) o nelle variabili d'ambiente PLEKTRA_KEYSTORE_PATH, " +
                            "PLEKTRA_KEYSTORE_PASSWORD, PLEKTRA_KEY_ALIAS, PLEKTRA_KEY_PASSWORD. " +
                            "Use -PPLEKTRA_FDROID_BUILD=true only for the unsigned stable F-Droid release path."
                    )
                }
            }
        }
        if (
            name.equals("preNightlyReleaseBuild", ignoreCase = true) ||
            name.equals("preNightlyNoAccessibilityReleaseBuild", ignoreCase = true)
        ) {
            doFirst {
                if (isUnsignedReleaseBuild) {
                    logger.lifecycle("Building an unsigned nightly release for separate PIV signing.")
                    return@doFirst
                }
                if (!shouldValidateNightlySigning(gradle.startParameter.taskNames)) {
                    logger.lifecycle("Skipping nightly signing validation for non-packaging task(s): ${gradle.startParameter.taskNames}")
                    return@doFirst
                }
                val storePath = signingProp("nightlyStoreFile", "PLEKTRA_NIGHTLY_KEYSTORE_PATH")
                val storePass = signingProp("nightlyStorePassword", "PLEKTRA_NIGHTLY_KEYSTORE_PASSWORD")
                val alias = signingProp("nightlyKeyAlias", "PLEKTRA_NIGHTLY_KEY_ALIAS")
                val keyPass = signingProp("nightlyKeyPassword", "PLEKTRA_NIGHTLY_KEY_PASSWORD")

                if (!hasSigningConfig(storePath, storePass, alias, keyPass)) {
                    throw GradleException(
                        "Missing signing config for nightly build. Define nightlyStoreFile, nightlyStorePassword, nightlyKeyAlias e nightlyKeyPassword in " +
                            "keystore.properties (non tracciato) o nelle variabili d'ambiente PLEKTRA_NIGHTLY_KEYSTORE_PATH, " +
                            "PLEKTRA_NIGHTLY_KEYSTORE_PASSWORD, PLEKTRA_NIGHTLY_KEY_ALIAS, PLEKTRA_NIGHTLY_KEY_PASSWORD."
                    )
                }
            }
        }
        if (
            name.equals("prePlayStableReleaseBuild", ignoreCase = true) ||
            name.equals("prePlayNightlyReleaseBuild", ignoreCase = true)
        ) {
            doFirst {
                if (isUnsignedReleaseBuild) {
                    logger.lifecycle("Building an unsigned Play bundle for separate PIV signing.")
                    return@doFirst
                }
                if (!shouldValidatePlaySigning(gradle.startParameter.taskNames)) return@doFirst
                throw GradleException(
                    "Play bundles use separate hardware-backed upload keys. " +
                        "Build with scripts/build-play-bundle-unsigned.sh, then sign with scripts/sign-play-bundle-piv.py."
                )
            }
        }
    }
    
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            // BouncyCastle (bcpkix/bcutil/bcprov) and jspecify ship duplicate OSGI metadata.
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            excludes += "META-INF/versions/**/OSGI-INF/**"
        }
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

// Release packages carry their runtime dependencies and bundled third-party assets.
listOf("stable", "nightly", "nightlyNoAccessibility", "playStable", "playNightly").forEach { flavor ->
    val nightlyFlavor = flavor.startsWith("nightly", ignoreCase = true) || flavor == "playNightly"
    val flavorTitle = flavor.replaceFirstChar { it.uppercase() }
    val sbomAssets = layout.buildDirectory.dir("generated/sbom/${flavor}Release/assets")
    val runtimeClasspath = provider { configurations.getByName("${flavor}ReleaseRuntimeClasspath") }
    tasks.register<GenerateReleaseSbom>("generate${flavorTitle}ReleaseSbom") {
        outputDirectory.set(sbomAssets)
        inputs.files(runtimeClasspath)
        val releaseVersionName = (android.defaultConfig.versionName ?: "unknown") +
            if (nightlyFlavor) providers.gradleProperty("PLEKTRA_NIGHTLY_VERSION_SUFFIX").orElse("-nightly").get() else ""
        val releaseVersionCode = if (nightlyFlavor) {
            providers.gradleProperty("PLEKTRA_NIGHTLY_VERSION_CODE").orNull?.toIntOrNull() ?: android.defaultConfig.versionCode ?: 0
        } else android.defaultConfig.versionCode ?: 0
        inputs.property("versionName", releaseVersionName)
        inputs.property("versionCode", releaseVersionCode)
        doLast {
            val versionName = releaseVersionName
            val appRef = "pkg:generic/plektra${if (nightlyFlavor) "-nightly" else ""}@$versionName"
            val libraries = runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts
                .map { artifact ->
                    val id = artifact.moduleVersion.id
                    linkedMapOf<String, Any>(
                        "type" to "library",
                        "group" to id.group,
                        "name" to id.name,
                        "version" to id.version,
                        "bom-ref" to "pkg:maven/${id.group}/${id.name}@${id.version}",
                        "purl" to "pkg:maven/${id.group}/${id.name}@${id.version}"
                    )
                }
                .distinctBy { it["bom-ref"] }
                .sortedBy { it["bom-ref"].toString() }
            fun bundled(name: String, ref: String, source: String, license: String) =
                linkedMapOf<String, Any>(
                    "type" to "data",
                    "name" to name,
                    "bom-ref" to ref,
                    "externalReferences" to listOf(mapOf("type" to "website", "url" to source)),
                    "licenses" to listOf(mapOf("license" to mapOf("id" to license)))
                )
            val bundledAssets = listOf(
                bundled("AOSP LatinIME-derived visuals", "vendored:aosp-latinime:127336e9f29d69607eab55982324b210279ae8c5", "https://android.googlesource.com/platform/packages/inputmethods/LatinIME", "Apache-2.0"),
                bundled("Google Material Symbols / Material Icons artwork", "vendored:material-icons", "https://github.com/google/material-design-icons", "Apache-2.0"),
                bundled("OpenGameArt keyboard soundpack", "vendored:opengameart-keyboard-soundpack", "https://opengameart.org/content/keyboard-soundpack-1-typing-and-single-keystrokes", "CC0-1.0"),
                bundled("OpenGameArt typewriter sounds", "vendored:opengameart-typewriter-sounds", "https://opengameart.org/content/typewriter-sounds", "CC0-1.0"),
                bundled("OpenGameArt mechanical sounds", "vendored:opengameart-mechanical-sounds", "https://opengameart.org/content/mechanical-sounds", "CC0-1.0"),
                bundled("Unicode CLDR emoji annotations", "vendored:unicode-cldr-annotations", "https://github.com/unicode-org/cldr-json", "Unicode-DFS-2016"),
                bundled("Leipzig Corpora frequency data", "vendored:leipzig-corpora", "https://corpora.uni-leipzig.de/", "CC-BY-3.0"),
                bundled("Shizuku wireless-ADB sources and libadb.so", "vendored:shizuku-wireless-adb", "https://github.com/RikkaApps/Shizuku", "Apache-2.0")
            )
            val components = libraries + bundledAssets
            val bom = linkedMapOf<String, Any>(
                "bomFormat" to "CycloneDX",
                "specVersion" to "1.6",
                "version" to 1,
                "metadata" to mapOf("component" to mapOf(
                    "type" to "application",
                    "name" to if (nightlyFlavor) "Plektra Nightly" else "Plektra",
                    "version" to versionName,
                    "bom-ref" to appRef,
                    "purl" to appRef,
                    "properties" to listOf(mapOf(
                        "name" to "android:versionCode",
                        "value" to releaseVersionCode.toString()
                    ))
                )),
                "components" to components,
                "dependencies" to listOf(mapOf(
                    "ref" to appRef,
                    "dependsOn" to components.map { it["bom-ref"] }
                ))
            )
            outputDirectory.get().file("plektra-sbom.cdx.json").asFile.apply {
                parentFile.mkdirs()
                writeText(JsonOutput.prettyPrint(JsonOutput.toJson(bom)) + "\n")
            }
        }
    }
}

androidComponents.onVariants(androidComponents.selector().withBuildType("release")) { variant ->
    val title = variant.name.replaceFirstChar { it.uppercase() }
    variant.sources.assets?.addGeneratedSourceDirectory(
        tasks.named<GenerateReleaseSbom>("generate${title}Sbom"),
        GenerateReleaseSbom::outputDirectory
    )
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation("androidx.window:window:1.5.1")
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")
    implementation("io.noties.markwon:ext-tasklist:4.6.2")
    implementation("io.noties.markwon:ext-strikethrough:4.6.2")
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.squareup.okhttp3:okhttp:4.11.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // RecyclerView per performance ottimali nella griglia emoji
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // Emoji2 per supporto emoji future-proof
    implementation("androidx.emoji2:emoji2:1.4.0")
    implementation("androidx.emoji2:emoji2-views:1.4.0")
    implementation("androidx.emoji2:emoji2-views-helper:1.4.0")
    // Kotlinx Serialization for dictionary optimization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-cbor:1.6.3")
    // Shizuku for ADB shell access
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    // Embedded wireless-ADB broker (vendored moe.shizuku.manager.adb). BouncyCastle only builds test
    // certificates now: the ADB key and its certificate come from AndroidKeyStore (finding 1.3d).
    testImplementation("org.bouncycastle:bcpkix-jdk18on:1.80")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.9.2")
    testImplementation(libs.junit)
    testImplementation("com.squareup.okhttp3:mockwebserver:4.11.0")
    testImplementation(libs.robolectric)
    testImplementation("org.mockito:mockito-core:5.11.0")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
