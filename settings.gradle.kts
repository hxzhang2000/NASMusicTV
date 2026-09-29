pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://jitpack.io") }
        // 阿里云镜像（国内加速，CI 环境不可达时自动跳过）
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        // ⛔ jcenter 镜像不可省：`com.github.promeg:tinypinyin:2.0.3` 只存在于 JCenter 存档里。
        //    JitPack 的 2.0.3 构建上游已失败（`/api/builds/com.github.promeg/tinypinyin/2.0.3`
        //    返回 status=Error），Maven Central 无此坐标（search.maven.org numFound=0）。
        //    2026-09-29 CI 因此炸在依赖解析（build 与 lint 两个 job），实测仅本仓库返回 200
        //    且 jar 96410 字节与本地 Gradle 缓存完全一致。
        maven { url = uri("https://maven.aliyun.com/repository/jcenter") }
    }
}

rootProject.name = "NASMusicTV"
include(":app")
