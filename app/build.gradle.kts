import java.util.Properties

plugins { id("com.android.application"); kotlin("android") }
val signingProperties = Properties().apply { val config = rootProject.file("keystore.properties"); if (config.exists()) config.inputStream().use { load(it) } }
fun signingValue(env: String, key: String): String? = System.getenv(env) ?: signingProperties.getProperty(key)

android {
    namespace = "com.hongguotv.nativeapp"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.hongguotv.nativeapp"
        minSdk = 24
        targetSdk = 34
        // versionCode derived from versionName (major*10000 + minor*100 + patch, e.g. 1.0.9 -> 10009).
        // It used to be a fixed 999 so any two builds could replace each other in both directions,
        // but TV launchers (Dangbei) cache the app icon and only refresh it when versionCode changes,
        // so a constant code left users stuck with a stale/blank launcher icon forever (reported on
        // v1.0.8: icon fine inside the APK, never picked up by the launcher). Increasing codes mean
        // Android now rejects downgrade installs (use `adb install -d` or uninstall to go back);
        // the in-app updater still decides "newer" by versionName comparison, not by code.
        val name = System.getenv("HONGGUOTV_VERSION_NAME") ?: "1.0.0"
        fun leadingInt(part: String?) = part?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
        val parts = name.split('.')
        versionCode = leadingInt(parts.getOrNull(0)) * 10000 + leadingInt(parts.getOrNull(1)) * 100 + leadingInt(parts.getOrNull(2))
        versionName = name
    }
    buildFeatures { buildConfig = true }
    // Both v1 (JAR) and v2 signatures: Android 7/8 package parsers can only read v1
    // from an uninstalled APK archive, and the updater's validateApk compares signer
    // sets on those versions - a v2-only APK reads as "no signatures" there.
    signingConfigs {
        create("delivery") {
            storeFile = signingValue("HONGGUOTV_KEYSTORE", "storeFile")?.let { file(it) }
            storePassword = signingValue("HONGGUOTV_STORE_PASSWORD", "storePassword")
            keyAlias = signingValue("HONGGUOTV_KEY_ALIAS", "keyAlias")
            keyPassword = signingValue("HONGGUOTV_KEY_PASSWORD", "keyPassword")
            enableV1Signing = true
            enableV2Signing = true
        }
    }
    buildTypes {
        // Debug builds sign with the delivery config too (same key material): this is
        // what actually applies enableV1Signing to the shipped APKs — the default debug
        // signing path skips v1 when minSdk >= 24, leaving v2-only APKs whose signatures
        // Android 7/8 package parsers cannot read from an uninstalled archive.
        getByName("debug") { signingConfig = signingConfigs.getByName("delivery") }
        getByName("release") { signingConfig = signingConfigs.getByName("delivery"); isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17; isCoreLibraryDesugaringEnabled = true }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += setOf("META-INF/versions/**", "META-INF/*.kotlin_module") }
}
dependencies {
    implementation("androidx.core:core:1.8.0")
    implementation("com.google.zxing:core:3.5.3")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(project(":core")) { exclude(group = "org.json") }
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
}
