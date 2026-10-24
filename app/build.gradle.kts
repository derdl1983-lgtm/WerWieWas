import java.net.URL
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// --- Offline-Stimmen (Kokoro „Victoria") über sherpa-onnx ------------------
// Die Engine kommt als AAR mit festgenagelter Version UND Prüfsumme (SHA-256):
// Wird die Datei beim Bauen auf GitHub ausgetauscht oder beschädigt, bricht
// der Build kontrolliert ab. Der Download landet im Gradle-Cache, sodass er
// nur einmal pro Rechner/CI-Lauf erfolgt.
val sherpaOnnxAar: File = run {
    val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-static-link-onnxruntime-1.13.8.aar"
    val sha = "b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471"
    val dir = File(gradle.gradleUserHomeDir, "caches/werwiewas-pinned/sherpa-onnx")
    val target = File(dir, "sherpa-onnx-1.13.8.aar")

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    if (!target.exists() || sha256(target) != sha) {
        dir.mkdirs()
        val part = File(dir, "sherpa-onnx-1.13.8.aar.part")
        URL(url).openStream().use { input ->
            part.outputStream().use { output -> input.copyTo(output, 1 shl 20) }
        }
        if (!part.exists() || sha256(part) != sha) {
            throw GradleException("sherpa-onnx-AAR ungültig (Prüfsumme stimmt nicht) – Build abgebrochen.")
        }
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
    }
    target
}


android {
    namespace = "de.derdl1983.weriewas"

    compileSdk = 34

    defaultConfig {
        applicationId = "de.derdl1983.weriewas"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "0.1.4"
    }

    // Festes Debug-Keystore: Ohne das signiert jeder CI-Lauf mit einem neuen Zufallsschlüssel,
    // und neue APKs lassen sich nicht über die installierte Version drüberinstallieren.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            // R8-Minifizierung: deutlich kleinere und schnellere APK.
            // Signiert mit dem festen Debug-Keystore (wie die Debug-APK), damit
            // Updates direkt über die installierte Version laufen.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        jniLibs {
            // x86/x86_64 läuft nur auf Emulatoren – weglassen spart ~30 MB APK.
            // Zum Testen im Emulator die beiden Zeilen entfernen und neu bauen.
            excludes += listOf("lib/x86/**", "lib/x86_64/**")
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // WebSocket-Client für die gratis Edge-TTS-Online-Stimmen
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Verschlüsselte Speicherung der API-Schlüssel (EncryptedSharedPreferences, Android Keystore)
    implementation("androidx.security:security-crypto:1.0.0")

    // Offline-Stimmen (Kokoro „Victoria"): sherpa-onnx-Engine als geprüftes AAR
    // (statisch gelinkt, inkl. aller nativen Bibliotheken) + Tar/bzip2 zum
    // Entpacken der espeak-ng-Aussprache-Daten beim ersten Download.
    implementation(files(sherpaOnnxAar))
    implementation("org.apache.commons:commons-compress:1.27.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
