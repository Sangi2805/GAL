import com.android.build.api.artifact.SingleArtifact
import java.security.SecureRandom
import java.util.Base64
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

/**
 * Release signing comes from keystore.properties at the project root, written by `./gradlew createReleaseKeystore`.
 * Neither that file nor the keystore is committed. Losing them means the next release cannot be installed over
 * the current one without uninstalling, which deletes the history.
 */
val keystorePropertiesFile: File = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.isFile) keystorePropertiesFile.inputStream().use { load(it) }
}
val releaseSigningConfigured = keystoreProperties.getProperty("storeFile") != null

android {
    namespace = "com.sangar.gal"
    // Platform 37 is what the local SDK has installed; behaviour is pinned by targetSdk.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.sangar.gal"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Not debuggable (the default for release, stated so it cannot drift): `adb shell run-as` is refused,
            // so the database cannot be copied off a phone without root. Backups are off in the manifest.
            isDebuggable = false
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    buildFeatures {
        compose = true
        viewBinding = true
        buildConfig = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core.ktx)
}

/**
 * The app must never be able to talk to the network. This fails the build if any
 * dependency sneaks android.permission.INTERNET into the merged manifest. It also refuses
 * QUERY_ALL_PACKAGES: Sidekick finds apps through the launcher <queries> block instead.
 */
abstract class VerifyNoInternetPermission : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val manifest = mergedManifest.get().asFile.readText()
        val banned = listOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.ACCESS_WIFI_STATE",
            "android.permission.QUERY_ALL_PACKAGES",
        ).filter { manifest.contains("\"$it\"") }
        if (banned.isNotEmpty()) {
            throw GradleException("Merged manifest declares banned permissions $banned. Find the dependency and remove them.")
        }
        report.get().asFile.writeText("OK: no network permissions, no QUERY_ALL_PACKAGES\n")
    }
}

/** Fails a release build early with instructions, instead of quietly producing an APK Android will not install. */
abstract class VerifyReleaseSigning : DefaultTask() {
    @get:Input
    abstract val configured: Property<Boolean>

    @TaskAction
    fun verify() {
        if (!configured.get()) {
            throw GradleException(
                "No release keystore. Run `./gradlew createReleaseKeystore` once, back up keystore/ and " +
                    "keystore.properties somewhere safe, then build the release again.",
            )
        }
    }
}

/** Creates the local release keystore and keystore.properties. Refuses to replace an existing one. */
abstract class CreateReleaseKeystore : DefaultTask() {
    @get:Internal
    abstract val propertiesFile: RegularFileProperty

    @get:Internal
    abstract val keystoreFile: RegularFileProperty

    @get:Internal
    abstract val relativeKeystorePath: Property<String>

    @TaskAction
    fun create() {
        val props = propertiesFile.get().asFile
        val store = keystoreFile.get().asFile
        if (props.exists() || store.exists()) {
            throw GradleException("A release keystore already exists ($props, $store). Not replacing it: APKs signed with a new key cannot update the installed app.")
        }
        store.parentFile.mkdirs()
        val password = ByteArray(24).also { SecureRandom().nextBytes(it) }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        val exe = if (System.getProperty("os.name").startsWith("Windows")) "keytool.exe" else "keytool"
        val keytool = File(System.getProperty("java.home"), "bin/$exe").path
        val process = ProcessBuilder(
            keytool, "-genkeypair", "-v",
            "-keystore", store.path, "-storetype", "PKCS12",
            "-alias", KEY_ALIAS, "-keyalg", "RSA", "-keysize", "4096", "-validity", "36500",
            "-storepass", password, "-keypass", password,
            "-dname", "CN=GAL, OU=Sideload, O=Personal",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0) throw GradleException("keytool failed: $output")
        props.writeText(
            """
            |# Release signing for GAL. Never commit this file. Back it up together with the keystore.
            |storeFile=${relativeKeystorePath.get()}
            |storePassword=$password
            |keyAlias=$KEY_ALIAS
            |keyPassword=$password
            |""".trimMargin(),
        )
        logger.lifecycle("Created $store and $props. Back both up: without them you cannot update the installed app.")
    }

    private companion object {
        const val KEY_ALIAS = "gal"
    }
}

tasks.register<CreateReleaseKeystore>("createReleaseKeystore") {
    group = "build setup"
    description = "Creates the local release signing key and keystore.properties."
    propertiesFile.set(keystorePropertiesFile)
    relativeKeystorePath.set("keystore/gal-release.jks")
    keystoreFile.set(rootProject.layout.projectDirectory.file("keystore/gal-release.jks"))
}

androidComponents {
    onVariants { variant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val verify = tasks.register<VerifyNoInternetPermission>("verify${name}NoInternet") {
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            report.set(layout.buildDirectory.file("reports/no-internet/${variant.name}.txt"))
        }
        tasks.matching { it.name == "assemble$name" }.configureEach { dependsOn(verify) }
        if (variant.buildType == "release") {
            val signing = tasks.register<VerifyReleaseSigning>("verify${name}Signing") {
                configured.set(releaseSigningConfigured)
            }
            tasks.matching { it.name == "assemble$name" || it.name == "package$name" }.configureEach { dependsOn(signing) }
        }
    }
}
