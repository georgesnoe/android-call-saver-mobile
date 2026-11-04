import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
}

fun envOrProperty(name: String, default: String = ""): String =
    providers.gradleProperty(name)
        .orElse(providers.environmentVariable(name))
        .orElse(default)
        .get()

fun quoted(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val apiBaseUrl = envOrProperty("API_BASE_URL")
val apiUploadPath = envOrProperty("API_UPLOAD_PATH", "/api/upload")
val apiAccessCode = envOrProperty("API_ACCESS_CODE")
val apiAccessHeader = envOrProperty("API_ACCESS_HEADER", "x-access-code")
val defaultCountryCode = envOrProperty("DEFAULT_COUNTRY_CODE", "+228")
val recordingAudioSource = envOrProperty("RECORDING_AUDIO_SOURCE", "auto")
val recordingBitrate = envOrProperty("RECORDING_BITRATE", "32000")
    .toIntOrNull()
    ?.coerceIn(12_000, 128_000)
    ?: 32_000
val allowCleartext = envOrProperty("ALLOW_CLEARTEXT", "false").equals("true", ignoreCase = true)
val versionNameValue = envOrProperty("VERSION_NAME", "1.0.0")
val versionCodeValue = envOrProperty("VERSION_CODE", "1").toIntOrNull() ?: 1

val keystorePath = envOrProperty("ANDROID_KEYSTORE_PATH")
val keystorePassword = envOrProperty("ANDROID_KEYSTORE_PASSWORD")
val keyAliasValue = envOrProperty("ANDROID_KEY_ALIAS")
val keyPassword = envOrProperty("ANDROID_KEY_PASSWORD")
val hasReleaseKeystore = keystorePath.isNotBlank() && file(keystorePath).exists() &&
    keystorePassword.isNotBlank() && keyAliasValue.isNotBlank() && keyPassword.isNotBlank()

android {
    namespace = "com.callsaver.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.callsaver.app"
        minSdk = 26
        targetSdk = 33
        versionCode = versionCodeValue
        versionName = versionNameValue

        buildConfigField("String", "API_BASE_URL", quoted(apiBaseUrl))
        buildConfigField("String", "API_UPLOAD_PATH", quoted(apiUploadPath))
        buildConfigField("String", "API_ACCESS_CODE", quoted(apiAccessCode))
        buildConfigField("String", "API_ACCESS_HEADER", quoted(apiAccessHeader))
        buildConfigField("String", "DEFAULT_COUNTRY_CODE", quoted(defaultCountryCode))
        buildConfigField("String", "RECORDING_AUDIO_SOURCE", quoted(recordingAudioSource))
        buildConfigField("int", "RECORDING_BITRATE", recordingBitrate.toString())
        buildConfigField("boolean", "ALLOW_CLEARTEXT", allowCleartext.toString())

        manifestPlaceholders["usesCleartextTraffic"] = allowCleartext.toString()
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                keyAlias = keyAliasValue
                keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        lintConfig = file("lint.xml")
        abortOnError = true
        checkReleaseBuilds = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    testImplementation("junit:junit:4.13.2")
}

if (apiBaseUrl.isBlank() || apiAccessCode.isBlank()) {
    logger.warn(
        "CallSaver: API_BASE_URL and/or API_ACCESS_CODE are empty. " +
            "The APK will build, but uploads are disabled until they are provided at build time.",
    )
}
logger.lifecycle(
    "CallSaver: baseUrl=${if (apiBaseUrl.isBlank()) "<missing>" else apiBaseUrl}, " +
        "uploadPath=$apiUploadPath, accessCode=${if (apiAccessCode.isBlank()) "<missing>" else "<set>"}, " +
        "country=$defaultCountryCode, source=$recordingAudioSource, bitrate=$recordingBitrate",
)
