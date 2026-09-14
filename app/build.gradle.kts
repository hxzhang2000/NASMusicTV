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

// S1 阶段 A+（2026-09-14）：CryptoUtils AES-256 派生口令不再在仓库内提供默认值。
// 取值顺序：keystore.properties.cryptoPassphrase → 环境变量 CRYPTO_PASSPHRASE。
// keystore.properties 已在 .gitignore 中（不入仓），仓库 HEAD 不再包含该口令。
//
// ⚠️ 兼容性：既有安装的加密凭据（百度 refresh_token 等）由历史口令加密，本地必须提供
// 与之相同的 cryptoPassphrase，否则已保存凭据将无法解密。为防止静默发布"换过密钥"的包，
// release 打包在口令缺失时会直接失败（见文件末尾 guard），debug/CI 不受影响。
// 注意：口令仍会被编译进 APK 的 BuildConfig（"混淆级"而非"保密级"）；彻底方案见
// CryptoUtils.kt 注释（AndroidKeyStore/StrongBox + 既有数据迁移）。
val cryptoPassphrase = readKeystoreProperty("cryptoPassphrase")
    .ifBlank { System.getenv("CRYPTO_PASSPHRASE").orEmpty() }

android {
    namespace = "com.nasmusic.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nasmusic.tv"
        minSdk = 22
        targetSdk = 34
        versionCode = 142
        versionName = "2.32.3"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        // 百度网盘 AppKey/SecretKey（运行时由 BaiduOAuthClient 经 BuildConfig 读取）
        buildConfigField("String", "BAIDU_APP_ID", "\"$baiduAppId\"")
        buildConfigField("String", "BAIDU_APP_SECRET", "\"$baiduAppSecret\"")
        
        // S1 修复（2026-09-13）：CryptoUtils AES-256 派生口令（运行时由 CryptoUtils 经 BuildConfig 读取）
        buildConfigField("String", "CRYPTO_PASSPHRASE", "\"$cryptoPassphrase\"")
    }

    // CI 的 Unit tests job 不生成 keystore.properties：无 release 签名配置时跳过创建，
    // 否则 file("") 在配置期抛 IllegalArgumentException，导致所有 Gradle 任务失败（含 testDebugUnitTest）。
    signingConfigs {
        if (keystoreStoreFile.isNotBlank()) {
            create("release") {
                storeFile = file(keystoreStoreFile)
                storePassword = keystoreStorePassword
                keyAlias = keystoreKeyAlias
                keyPassword = keystoreKeyPassword
            }
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
            // findByName：无 keystore.properties 时返回 null（AGP 跳过签名，打包阶段才失败），
            // 而非 getByName 的配置期 NoSuchElementException。
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            isDebuggable = true
        }
    }

    testOptions {
        // 纯 JVM 单测允许 android.* API 返回默认值（E25 催眠渲染器构造含 Paint）
        unitTests.isReturnDefaultValues = true
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
                // 注：Media3 的 androidx.media3.common.util.UnstableApi 不能在这里 -opt-in。
                // 它走的是 androidx 的 @RequiresOptIn 机制，Kotlin 编译器不认（加进来会报
                // "not an opt-in requirement marker"），lint 也不认。正确做法是在使用处标
                // @androidx.annotation.OptIn(UnstableApi::class)，见 PcmTapProcessor 等 7 个文件。
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

// S1 阶段 A+ guard：release 打包前校验口令已配置，避免静默发布一个换了密钥的包
// （既有用户凭据将无法解密）。仅作用于 release 打包任务，debug / CI 不受影响。
tasks.configureEach {
    if (name == "packageRelease") {
        doFirst {
            if (cryptoPassphrase.isBlank()) {
                throw GradleException(
                    "CRYPTO_PASSPHRASE 未配置：请在 keystore.properties 添加 " +
                        "cryptoPassphrase=<历史口令>，或设置环境变量 CRYPTO_PASSPHRASE。" +
                        "缺失会改变加密密钥，导致既有已保存凭据无法解密。"
                )
            }
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

    // Concurrent Futures (for ResolvableFuture used in CoilBitmapLoader)
    implementation("androidx.concurrent:concurrent-futures:1.1.0")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Image loading
    implementation("io.coil-kt:coil-compose:2.5.0")

    // 封面取色（可视化效果 T5）
    implementation("androidx.palette:palette-ktx:1.0.0")

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

    // AppCompat (for AppCompatDelegate.setApplicationLocales)
    implementation("androidx.appcompat:appcompat:1.6.1")

    // Core KTX (for LocaleListCompat)
    implementation("androidx.core:core-ktx:1.12.0")

    // Leanback (TV support)
    implementation("androidx.leanback:leanback:1.0.0")

    // ONNX Runtime (Spleeter 高质量人声分离)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.1")

    // Room (本地音乐索引持久化)
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")

    // jaudiotagger (音频元数据读写：封面/歌词内嵌，LGPL；GPL v3 项目整体合规)
    implementation("net.jthink:jaudiotagger:3.0.1")

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
