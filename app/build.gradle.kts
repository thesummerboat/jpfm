plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// GitHub Actions sätter GITHUB_RUN_NUMBER, så varje bygge får ett högre versionsnummer
// och kan installeras ovanpå det förra. Uppdateraren i appen jämför mot samma nummer.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

// Signeringsnyckeln ligger krypterad i repot (app/jpfm.keystore.enc). Bygget i GitHub Actions
// dekrypterar den med hemligheten JPFM_SIGNING_PASSWORD och pekar hit via JPFM_KEYSTORE.
// Lokala byggen utan nyckel signeras med vanliga debug-nyckeln.
val releaseKeystore = System.getenv("JPFM_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
val signingPassword = System.getenv("JPFM_SIGNING_PASSWORD")

android {
    namespace = "se.jpfm"
    compileSdk = 35

    defaultConfig {
        applicationId = "se.jpfm"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        buildConfigField("String", "UPDATE_REPO", "\"thesummerboat/jpfm\"")
    }

    signingConfigs {
        if (releaseKeystore != null && signingPassword != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = signingPassword
                keyAlias = "jpfm"
                keyPassword = signingPassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.media3.common.util.UnstableApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/AL2.0", "META-INF/LGPL2.1", "DebugProbesKt.bin")
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all { test ->
            // Visa hela felet i byggloggen när ett test inte går igenom.
            test.testLogging {
                events("failed")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.palette:palette-ktx:1.0.0")

    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    implementation("androidx.media3:media3-datasource:$media3")
    implementation("androidx.media3:media3-database:$media3")
    implementation("androidx.media3:media3-common:$media3")
    implementation("androidx.media3:media3-extractor:$media3")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.9.0")

    testImplementation("junit:junit:4.13.2")
    // Riktig org.json i testerna (Androids egen är bara en stubbe utanför telefonen).
    testImplementation("org.json:json:20240303")
    // XmlPullParser för att testa RSS-tolkningen utanför telefonen.
    testImplementation("net.sf.kxml:kxml2:2.3.0")
}
