plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.jiadroid.follow"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.jiadroid.follow"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        viewBinding = true
    }

    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/vosk"))
}

val voskModel = "vosk-model-small-en-us-0.15"

val fetchVoskModel by tasks.registering {
    val out = layout.buildDirectory.dir("generated/vosk/model-en-us")
    outputs.dir(out)
    doLast {
        val target = out.get().asFile
        if (target.resolve("uuid").exists()) return@doLast
        val zip = temporaryDir.resolve("$voskModel.zip")
        uri("https://alphacephei.com/vosk/models/$voskModel.zip").toURL().openStream().use { input ->
            zip.outputStream().use { input.copyTo(it) }
        }
        target.deleteRecursively()
        copy {
            from(zipTree(zip))
            into(target)
            eachFile { path = path.removePrefix("$voskModel/") }
            includeEmptyDirs = false
        }
        target.resolve("uuid").writeText(voskModel)
    }
}

tasks.named("preBuild") { dependsOn(fetchVoskModel) }

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("com.google.mlkit:pose-detection:18.0.0-beta5")
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    testImplementation("junit:junit:4.13.2")
}
