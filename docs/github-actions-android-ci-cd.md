# GitHub Actions Android CI/CD 配置指南

自动构建 Release APK、创建 GitHub Release、提取 CHANGELOG 作为 Release Notes。

## 前置条件

### 1. 项目文件

- `app/build.gradle.kts` — Android 应用模块，定义 `signingConfigs { release { ... } }`
- `gradlew` — Gradle Wrapper 脚本（**必须加入版本控制，且设置可执行权限**）
- `gradle/wrapper/gradle-wrapper.properties` — 指向 Gradle 发行版 URL
- `CHANGELOG.md` — 遵循 [Keep a Changelog](https://keepachangelog.com/) 格式

### 2. CHANGELOG 格式要求

版本条目按**最新在前**排列，用 `---` 分隔：

```markdown
## [v1.2.0] - 2026-07-27

### Added
- 新功能描述

### Fixed
- 修复描述

---

## [v1.1.0] - 2026-07-20
```

### 3. 版本号管理

- `app/build.gradle.kts` 中的 `versionName` 与 Git tag 保持一致
- 每次发版时：更新 `versionName` → 更新 `CHANGELOG.md` → 打 `v*` tag 推送

### 4. Gradle 相关

项目根目录下必须包含 `gradlew`、`gradlew.bat`、`gradle/wrapper/` 目录。

```bash
# 确保 gradlew 有可执行权限（Linux/macOS CI 需要）
git update-index --chmod=+x gradlew
```

`gradle-wrapper.properties` 中 `distributionUrl` 必须指向可公开访问的 Gradle 发行版：

```properties
distributionUrl=https\://services.gradle.org/distributions/gradle-8.12-bin.zip
```

> ⚠️ 不要使用 `file:///` 本地路径，否则 CI 构建会失败。

---

## 完整工作流文件

创建 `.github/workflows/build.yml`：

```yaml
name: Build

on:
  push:
    branches: [ main, develop ]
    tags: [ 'v*' ]          # 推送 v* tag 时触发
  pull_request:
    branches: [ main ]

jobs:
  build:
    runs-on: ubuntu-latest
    permissions:
      contents: write        # 创建 Release 需要

    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'

      # Android SDK — 安装许可证和所需平台
      - name: Set up Android SDK
        run: |
          yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses 2>&1 || true
          $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --install "platforms;android-34" "build-tools;34.0.0" 2>&1 || true

      # 签名密钥 — 生成临时 keystore 供 CI 签名
      - name: Create keystore for CI
        run: |
          keytool -genkey -v -keystore app/ci-keystore.jks -alias ci -keyalg RSA -keysize 2048 -validity 3650 -storepass ci-pass -keypass ci-pass -dname "CN=CI,OU=CI,O=CI,L=CI,S=CI,C=CI"
          echo "storeFile=ci-keystore.jks" > keystore.properties
          echo "storePassword=ci-pass" >> keystore.properties
          echo "keyAlias=ci" >> keystore.properties
          echo "keyPassword=ci-pass" >> keystore.properties

      - name: Build release APK
        run: ./gradlew assembleRelease --stacktrace

      - name: Upload APK
        uses: actions/upload-artifact@v4
        with:
          name: app-release
          path: app/build/outputs/apk/release/

      # 提取 CHANGELOG 中从上一次 Release 到当前版本的内容
      - name: Extract release notes from CHANGELOG
        if: startsWith(github.ref, 'refs/tags/v')
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          TAG="${{ github.ref_name }}"
          # 获取上一个 release 的版本号（取倒数第二个）
          PREV_TAG=$(gh release list --limit 2 --json tagName --jq '.[1].tagName' 2>/dev/null || echo "")
          if [ -n "$PREV_TAG" ] && [ "$PREV_TAG" != "$TAG" ]; then
            echo "Previous release: $PREV_TAG, current: $TAG"
            awk -v prev="$PREV_TAG" '
              /^## \[/ { found=1 }
              found && $0 ~ "^## \\[" prev "\\]" { exit }
              found { print }
            ' CHANGELOG.md > release_notes.md
          else
            echo "No previous release found, extracting current version only"
            awk -v tag="$TAG" '
              $0 ~ "^## \\[" tag "\\]" { found=1; next }
              found && /^## \[/ { exit }
              found { print }
            ' CHANGELOG.md > release_notes.md
          fi
          echo "=== Release notes ==="
          cat release_notes.md

      # 创建（或更新）GitHub Release
      - name: Create Release
        if: startsWith(github.ref, 'refs/tags/v')
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          TAG="${{ github.ref_name }}"
          APK="app/build/outputs/apk/release/app-release.apk"
          if gh release view "$TAG" --json tagName &>/dev/null; then
            echo "Deleting existing release $TAG..."
            gh release delete "$TAG" --yes
          fi
          echo "Creating release $TAG..."
          ARGS=("--notes-file" "release_notes.md" "--title" "项目名 $TAG")
          if [ -f "$APK" ]; then
            gh release create "$TAG" "${ARGS[@]}" "$APK"
          else
            gh release create "$TAG" "${ARGS[@]}"
          fi
```

---

## 各项目适配说明

### 适配新项目时需要修改的地方

| 位置 | 说明 |
|------|------|
| `compileSdk` / `targetSdk` | 修改 SDK 版本号，并同步更新 `sdkmanager --install` 中的版本 |
| `app/` 路径 | 如果 APK 模块不在 `app/` 目录，修改所有 `app/` 路径引用 |
| APK 文件名 | `app-release.apk` 可能不同，根据 `build.gradle.kts` 中的配置调整 |
| `displayName` | 修改 `--title "项目名 $TAG"` 中的项目名 |
| `keystore.properties` | 如果签名配置读取不同属性名，调整 CI 中的写入内容 |

### 非 Android 项目

如果是 Python 等项目，替换：

| 步骤 | 替换为 |
|------|--------|
| `Set up Android SDK` | 移除 |
| `Create keystore for CI` | 移除 |
| `Build release APK` | 改为 `python build.py` 或 `go build` 等 |
| `Upload APK` | 改为上传其他产物 |
| `app-release.apk` | 改为对应的产物路径 |

---

## 常见问题

### 1. `gradlew: Permission denied`

**原因**：Git 没有保存可执行权限。

**修复**：
```bash
git update-index --chmod=+x gradlew
git commit -m "fix(ci): gradlew 添加可执行权限"
```

### 2. `Keystore file not found for signing config 'release'`

**原因**：keystore 路径与 `build.gradle.kts` 中 `file()` 的相对路径不一致。

**修复**：确保 keystore 创建在 `app/` 目录下（与 `build.gradle.kts` 同级）：
```bash
keytool -genkey -v -keystore app/ci-keystore.jks ...
```

### 3. `Resource not accessible by integration`

**原因**：`GITHUB_TOKEN` 缺少 `contents: write` 权限。

**修复**：在 workflow 的 job 中添加：
```yaml
permissions:
  contents: write
```

### 4. `gh` 命令找不到或认证失败

**原因**：`gh` CLI 需要 `GH_TOKEN` 环境变量。

**修复**：在需要 `gh` 的步骤中添加：
```yaml
env:
  GH_TOKEN: ${{ github.token }}
```

### 5. Gradle 依赖下载失败（阿里云镜像在 CI 不可达）

**原因**：`settings.gradle.kts` 中配置了阿里云镜像，CI 在美国无法访问。

**修复**：将标准仓库放在阿里云镜像前面：
```kotlin
repositories {
    google()              // 优先
    mavenCentral()        // 优先
    maven { url = uri("https://jitpack.io") }
    maven { url = uri("https://maven.aliyun.com/repository/central") }  // 兜底
    maven { url = uri("https://maven.aliyun.com/repository/google") }
    maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
}
```

### 6. 构建通过但 Release 没有创建

**原因**：必须推送 `v*` 格式的 tag，且 tag 必须与推送的 commit 一致。

**修复**：
```bash
git tag v1.2.3
git push origin main --tags
```

---

## 发版流程速查

```bash
# 1. 更新版本号
#    编辑 app/build.gradle.kts → versionName = "1.2.3"

# 2. 更新 CHANGELOG.md
#    在顶部插入新版本条目

# 3. 提交并推送
git add .
git commit -m "chore: 升级至 v1.2.3"
git push origin main

# 4. 打 tag 并推送（触发 CI 构建 + Release）
git tag v1.2.3
git push origin main --tags
```

CI 构建完成后（约 3-5 分钟），Release 会自动发布到 GitHub Releases 页面。