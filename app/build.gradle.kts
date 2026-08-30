plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

fun readKeystoreProperty(name: String): String {
    val f = rootProject.file("keystore.properties")
    if (!f.exists()) return ""
    return try {
        val lines = f.readLines()
        val prefix = "$name="
        lines.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix) ?: ""
    } catch (_: Exception) { "" }
}

val keystoreStoreFile = readKeystoreProperty("storeFile")
val keystoreStorePassword = readKeystoreProperty("storePassword")
val keystoreKeyAlias = readKeystoreProperty("keyAlias")
val keystoreKeyPassword = readKeystoreProperty("keyPassword")

// 百度网盘开放平台 AppKey/SecretKey（从 keystore.properties 读取，gitignored，不硬编码在源码）
val baiduAppId = readKeystoreProperty("baiduAppId")
val baiduAppSecret = readKeystoreProperty("baiduAppSecret")

android {
    namespace = "com.nasmusic.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nasmusic.tv"
        minSdk = 22
        targetSdk = 34
versionCode = 70
versionName = "2.25.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        // 百度网盘 AppKey/SecretKey（运行时由 BaiduOAuthClient 经 BuildConfig 读取）
        buildConfigField("String", "BAIDU_APP_ID", "\"$baiduAppId\"")
        buildConfigField("String", "BAIDU_APP_SECRET", "\"$baiduAppSecret\"")
    }

    signingConfigs {
        create("release") {
            storeFile = file(keystoreStoreFile)
            storePassword = keystoreStorePassword
            keyAlias = keystoreKeyAlias
            keyPassword = keystoreKeyPassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.addAll(
                "-opt-in=androidx.tv.material3.ExperimentalTvMaterial3Api"
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // APK 文件名格式：NASMusicTV-release-v2-24-2.apk（版本号点号替换为横线）
    applicationVariants.all {
        val variant = this
        variant.outputs.all {
            val output = this as com.android.build.gradle.internal.api.ApkVariantOutputImpl
            val vName = variant.versionName.replace(".", "-")
            output.outputFileName = "NASMusicTV-${variant.name}-v${vName}.apk"
        }
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Compose for TV
    implementation("androidx.tv:tv-foundation:1.0.0-alpha10")
    implementation("androidx.tv:tv-material:1.0.0-alpha10")

    // Compose core
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Activity Compose
    implementation("androidx.activity:activity-compose:1.8.2")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    // Media3 / ExoPlayer
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-session:1.2.1")
    implementation("androidx.media3:media3-ui:1.2.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.2.1")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Image loading
    implementation("io.coil-kt:coil-compose:2.5.0")

    // DataStore (Preferences)
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // JSON
    implementation("com.google.code.gson:gson:2.10.1")

    // 二维码生成（手机扫码输入）
    implementation("com.google.zxing:core:3.5.3")

    // 本地 HTTP server（接收手机浏览器提交的文字）
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // 拼音转换（兼容 API 22+，不依赖 ICU）
    implementation("com.github.promeg:tinypinyin:2.0.3")

    // Leanback (TV support)
    implementation("androidx.leanback:leanback:1.0.0")

    // ONNX Runtime (Spleeter 高质量人声分离)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.1")

    // Room (本地音乐索引持久化)
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("org.mockito:mockito-core:5.3.1")
    testImplementation("org.mockito:mockito-inline:5.2.0")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
