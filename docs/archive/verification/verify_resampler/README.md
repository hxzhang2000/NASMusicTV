# LinearResampler 独立数值验证 harness

## 为什么存在

本机（沙箱）Gradle 的测试 worker **一启动即死**（exit `268435466` = `0x1000000A`，
低 16 位为 Windows `ERROR_BAD_ENVIRONMENT`，`test-results/` 下 0 个 XML）。
因此 `./gradlew testDebugUnitTest` 在本机无法作为验证手段。

这个 harness 绕过 Gradle：**用 Kotlin 编译器把真实源码抽出来，编成独立 JVM 程序跑**。
它验证的是 `DemucsSeparator.kt` 里那份实现本身，不是手抄的副本。

## 文件

| 文件 | 内容 |
|---|---|
| `Resampler.kt` | 从 `DemucsSeparator.kt` 抽取的 `LinearResampler`（脚本抽取，去缩进 + 去 `private`） |
| `ByteConv.kt` | 从同文件抽取的 `shortToByteArray` + `putShortLE` |
| `StubDemucs.kt` | `DemucsSeparator` 桩类，内部含抽取出的 `LinearResampler`，用于跑真实的 `LinearResamplerTest.kt` |
| `Main.kt` | 39 条断言的验证程序（重采样数值 + 字节写出路径） |
| `result.txt` | 最近一次 `MainKt` 的运行结果（39 PASS / 0 FAIL） |
| `cp.txt` | 编译所需的 Kotlin 编译器 classpath（由下方命令生成） |
| `out/` `outtest/` | 编译产物 |

## 运行方式

### A. 跑 39 条断言的验证程序

```bash
cd logs_temp/verify_resampler

# 1) 重新抽取（源码改动后必须重跑，保证验证的是最新实现）
python ../../logs_temp/verify_resampler/extract.py     # 见下"抽取"节

# 2) 组装编译器 classpath（一次性）
C=/c/Users/hxzha/.gradle/caches/modules-2/files-2.1
CP=""
for j in \
  "$C/org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.10/6882d39ef03b18879c1a5d2a83c2212361bed5fe/kotlin-compiler-embeddable-2.2.10.jar" \
  "$C/org.jetbrains.kotlin/kotlin-stdlib/2.2.10/30de6faa127a4a012db8e71bf1b9c0a99b1402b2/kotlin-stdlib-2.2.10.jar" \
  "$C/org.jetbrains.kotlin/kotlin-reflect/2.2.10/98d0ca9819d98cb3aa5c0a25793830b6659feee2/kotlin-reflect-2.2.10.jar" \
  "$C/org.jetbrains.kotlin/kotlin-script-runtime/2.2.10/712f9bc08f378c13a4a00f0bbb28c76e59183e83/kotlin-script-runtime-2.2.10.jar" \
  "$C/org.jetbrains.kotlin/kotlin-daemon-embeddable/2.2.10/212fd3c2c6e4e5d0f4db3c4191233e2b622380cc/kotlin-daemon-embeddable-2.2.10.jar" \
  "$C/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.10.2/4a9f78ef49483748e2c129f3d124b8fa249dafbf/kotlinx-coroutines-core-jvm-1.10.2.jar" \
  "$C/com.intellij/annotations/12.0/bbcf6448f6d40abe506e2c83b70a3e8bfd2b4539/annotations-12.0.jar" ; do
  CP="$CP$(cygpath -w "$j");"
done
echo "$CP" > cp.txt

STD=$(cygpath -w $C/org.jetbrains.kotlin/kotlin-stdlib/2.2.10/30de6faa127a4a012db8e71bf1b9c0a99b1402b2/kotlin-stdlib-2.2.10.jar)
JAVA="/c/Program Files/Android/Android Studio/jbr/bin/java.exe"

# 3) 编译 + 运行
"$JAVA" -cp "$(cat cp.txt)" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -nowarn -cp "$STD" -d out Resampler.kt ByteConv.kt Main.kt
"$JAVA" -Dfile.encoding=UTF-8 -cp "out;$STD" MainKt
```

### B. 直接跑**提交到仓库的** `LinearResamplerTest.kt`

这是更有价值的一种：跑的就是 CI 会跑的那份测试文件。

```bash
C=/c/Users/hxzha/.gradle/caches/modules-2/files-2.1
STD=$(cygpath -w $C/org.jetbrains.kotlin/kotlin-stdlib/2.2.10/30de6faa127a4a012db8e71bf1b9c0a99b1402b2/kotlin-stdlib-2.2.10.jar)
JU=$(cygpath -w $C/junit/junit/4.13.2/8ac9e16d933b6fb43bc7f576336b8f4d7eb5ba12/junit-4.13.2.jar)
HC=$(cygpath -w $C/org.hamcrest/hamcrest-core/1.3/42a25dc3219429f0e5d060061f71acb49bf010a0/hamcrest-core-1.3.jar)
T=$(cygpath -w ../../app/src/test/java/com/nasmusic/tv/player/LinearResamplerTest.kt)
JAVA="/c/Program Files/Android/Android Studio/jbr/bin/java.exe"

rm -rf outtest
"$JAVA" -cp "$(cat cp.txt)" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -nowarn -cp "$STD;$JU;$HC" -d outtest StubDemucs.kt "$T"
"$JAVA" -Dfile.encoding=UTF-8 -cp "outtest;$STD;$JU;$HC" \
  org.junit.runner.JUnitCore com.nasmusic.tv.player.LinearResamplerTest
```

结果应为 `OK (10 tests)`。

## 抽取（源码改动后必须重做）

`Resampler.kt` / `StubDemucs.kt` 由脚本按标记抽取，避免手抄导致"测试与源码分叉"：

- 起点：包含 `流式线性插值重采样器` 的那行，往上找到最近的 `/**`
- 终点：`class LinearResampler(` 之后第一个恰为 4 空格 + `}` 的行
- 变换：去掉 4 空格缩进；`private class` → `class`（`StubDemucs.kt` 保留 `internal class` 并外包一层 `class DemucsSeparator { ... }` + `package com.nasmusic.tv.player`）

## 关键坑（都踩过）

1. **Windows 的 `java.exe` 不认 cygwin 路径**。`-cp /c/Users/...` 会报
   `ClassNotFoundException`，必须 `cygpath -w` 转成 `C:\Users\...`。
2. **`kotlin-compiler-embeddable` 自身也要 stdlib 在 `-cp` 上**，否则报
   `NoClassDefFoundError: kotlin/jvm/internal/markers/KMappedMarker`；
   还缺 `kotlinx-coroutines-core`（`ClassNotFoundException: kotlinx.coroutines.CoroutineScope`）。
3. **`-Dfile.encoding=UTF-8`** 否则中文断言名在 GBK 控制台里乱码。
4. **`floor((n-1)/ratio)` 不可靠**：double 除法在整除边界会给出 `3968.999…`。
   实测 `in=48000 out=44100 n=4321`，真值 3970 而浮点算法给 3969。
   期望值必须用精确整数运算 `(n-1)*outRate/inRate + 1`。

## 结论摘要

**39 PASS / 0 FAIL**（`result.txt`），关键几条：

- 同速率（ratio=1.0）逐样本 **bit-exact** 透传，帧数相等
- 48000→44100 与「整段离线按 `k*ratio` 理想插值」**逐点 bit-exact**（48000 个点全部一致）
- 频率保持（1000Hz 测回 1000.02Hz）、时长保持（1.00000s）、无相邻跳变
- 392 组 (inRate, outRate, n) 属性测试：帧数与理想插值全部一致
- 200 万帧无浮点累积漂移
- 尾部截断 < `outRate/inRate` 个样本（48k→44.1k 时 < 1 个；8k→44.1k 时 ≤ 5 个 = 0.125ms）
- `putShortLE` 与 `shortToByteArray` 在全部 65536 个取值下字节一致（小端）
- PCM 限幅：伴奏越界（±2.0）不回绕
