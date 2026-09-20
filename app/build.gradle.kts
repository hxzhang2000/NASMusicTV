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
        versionCode = 156
        versionName = "2.36.2"

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
    //
    // storeFile 用 rootProject.file() 而非 project.file()：前者让相对路径相对**项目根**解析
    // （`release-key.jks` / `ci-keystore.jks` 都放在项目根），后者会找 `app/<file>` 找不到。
    // 绝对路径两种都兼容。详见 docs/technical-overview.md §10.164。
    signingConfigs {
        if (keystoreStoreFile.isNotBlank()) {
            create("release") {
                storeFile = rootProject.file(keystoreStoreFile)
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
    // 1.0.0 在 Windows(Robolectric)下连续写同名文件有 rename 竞态（SingleProcessDataStore.kt:433），1.1.1 修复
    implementation("androidx.datastore:datastore-preferences:1.1.1")

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
    //
    // ⚠️ 版本被「minSdk 22」与「16KB 页对齐」双重锁定，**不要随手升级**（2026-09-14 实测结论）
    //
    // lint 的 `Aligned16KB` 会报这个依赖：1.17.1 的 native 库 PT_LOAD 段 `p_align = 4096`，
    // 未满足 Android 16KB 页要求。但升级不是「改个版本号」的事：
    //
    //   版本     libonnxruntime.so   libonnxruntime4j_jni.so    minSdk 要求
    //   1.17.1   4096  (未对齐)      4096  (未对齐)             21
    //   1.20.0   16384 (已对齐)      4096  (未对齐)             21   ← 陷阱，见下
    //   1.21.1   16384 (已对齐)      arm64 已对齐 / v7a 未对齐   24
    //   1.29.0   16384 (已对齐)      16384 (已对齐)             24
    //
    // 两个关键事实（均实测，非推测）：
    //
    // 1) **只有 1.29.0 能让警告消失**。lint 的检查器（AGP 的
    //    `PageAlignmentDetector.getIncidentsFromAndroidLibrary`）遍历 AAR 解包目录下
    //    **全部 4 个 ABI × 全部 native 库**（不受 `abiFilters` 影响），命中第一个未对齐的库
    //    就 `return` —— 所以每个依赖**只报 1 条**（报告里的 "3" 是聚合重复）。
    //    1.20.0 的 `libonnxruntime.so` 虽已对齐，但 `libonnxruntime4j_jni.so` 仍是 4096，
    //    lint 只会改报那个文件，**警告并不会消失**；且 16KB 真机上该库 `dlopen` 仍会失败。
    // 2) **1.29.0 的 AAR manifest 要求 `minSdkVersion 24`**，而本项目是 22。直接改会让
    //    manifest merger 失败；改 minSdk 则等于砍掉 Android 5.0/5.1/6.0 设备
    //    （含开发用的创维 Android 5.1.1 电视 —— 真机回归的基准设备）。
    //
    // 因此当前**有意保持 1.17.1**：`targetSdk 34` 下 Google Play 的 16KB 强制要求尚未触发
    // （那是 `targetSdk 35+` 的事），且这条是 Warning 而非 Error（lint 当前 0 errors）。
    // **触发条件**：升 `targetSdk 35` 或上架前必须处理 —— 届时只有两条路：升 minSdk 24 并换
    // 1.29.0，或自编 ONNX Runtime（加 `-Wl,-z,max-page-size=16384`）。
    // 完整实测矩阵见 `docs/technical-overview.md` §10.146 的 §七-4 遗留项。
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
    // v2.35.0：Room 迁移测试（MigrationTestHelper）
    testImplementation("androidx.room:room-testing:2.7.1")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}

/**
 * P3-4（2026-09-16）：Room schema 导出目录。
 *
 * `LocalMusicDatabase` 声明了 `exportSchema = true`，但此前**没有**配置 `room.schemaLocation`，
 * Room 只能打印 "Schema export directory was not provided…" 警告并且**一个 schema 都不导出**——
 * 于是「v3 破坏性迁移」这件事在仓库里没有任何可追溯的记录（P3-4 发现的问题）。
 *
 * 现在 schema JSON 会写到 `app/schemas/<DB 全限定名>/<version>.json`，需随代码一并提交入库。
 * 用途不是支撑 Migration（本地索引仍走 `fallbackToDestructiveMigration`，可由重扫重建），
 * 而是**留档**：将来若要改回 Migration、或需要核对某个版本的表结构/索引定义，有权威快照可比对。
 *
 * ⚠️ 每次 bump version 都会新增一个 JSON，**不要删除旧文件**——删掉就丢了版本历史。
 *
 * v2.35.0（多码率）：`DownloadDatabase` 也改为 `exportSchema = true`。
 * 原因是它现在有了**真实迁移**（`MIGRATION_1_2`），而 `MigrationTestHelper` 必须
 * 读取基线 schema（`1.json`）才能构造 v1 库、验证迁移后的表结构与实体定义一致。
 * 没有基线 schema 就只能靠手工建表 + 手工算 identityHash，测试会变得脆弱且不可信。
 * 产物位于 `app/schemas/com.nasmusic.tv.backend.download.db.DownloadDatabase/{1,2}.json`。
 */
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
