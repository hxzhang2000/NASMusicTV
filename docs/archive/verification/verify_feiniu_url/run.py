"""
FeiniuUrl 独立 JVM 验证 harness（2026-09-14）

为什么存在：本机 Gradle 的测试 worker 一启动即死（exit 268435466），
`./gradlew testDebugUnitTest` 无法作为验证手段（AGENTS.md 已记录）。
因此绕过 Gradle，用 kotlin-compiler-embeddable 直接编译真实源码 +
真实测试类，再用 JUnitCore 跑。

与 logs_temp/verify_resampler 的区别：FeiniuUrl.kt **零 Android 依赖**
（只用 okhttp3 的 HttpUrl + JDK），所以不需要抽取/打桩，直接编源文件与测试文件。

用法：
    python run.py          # 编译 + 运行测试
    python run.py --clean  # 先清 out/
"""
import os
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
CACHE = r"C:\Users\hxzha\.gradle\caches\modules-2\files-2.1"
JAVA = r"C:\Program Files\Android\Android Studio\jbr\bin\java.exe"

# 编译器 classpath：沿用 verify_resampler 已验证可用的组合
COMPILER_CP = [
    r"org.jetbrains.kotlin\kotlin-compiler-embeddable\2.2.10\6882d39ef03b18879c1a5d2a83c2212361bed5fe\kotlin-compiler-embeddable-2.2.10.jar",
    r"org.jetbrains.kotlin\kotlin-stdlib\2.2.10\30de6faa127a4a012db8e71bf1b9c0a99b1402b2\kotlin-stdlib-2.2.10.jar",
    r"org.jetbrains.kotlin\kotlin-reflect\2.2.10\98d0ca9819d98cb3aa5c0a25793830b6659feee2\kotlin-reflect-2.2.10.jar",
    r"org.jetbrains.kotlin\kotlin-script-runtime\2.2.10\712f9bc08f378c13a4a00f0bbb28c76e59183e83\kotlin-script-runtime-2.2.10.jar",
    r"org.jetbrains.kotlin\kotlin-daemon-embeddable\2.2.10\212fd3c2c6e4e5d0f4db3c4191233e2b622380cc\kotlin-daemon-embeddable-2.2.10.jar",
    r"org.jetbrains.kotlinx\kotlinx-coroutines-core-jvm\1.10.2\4a9f78ef49483748e2c129f3d124b8fa249dafbf\kotlinx-coroutines-core-jvm-1.10.2.jar",
    r"com.intellij\annotations\12.0\bbcf6448f6d40abe506e2c83b70a3e8bfd2b4539\annotations-12.0.jar",
]

# 运行时 classpath
RUNTIME_CP = [
    r"org.jetbrains.kotlin\kotlin-stdlib\2.2.10\30de6faa127a4a012db8e71bf1b9c0a99b1402b2\kotlin-stdlib-2.2.10.jar",
    r"com.squareup.okhttp3\okhttp\4.12.0\2f4525d4a200e97e1b87449c2cd9bd2e25b7e8cd\okhttp-4.12.0.jar",
    r"com.squareup.okio\okio-jvm\3.6.0\5600569133b7bdefe1daf9ec7f4abeb6d13e1786\okio-jvm-3.6.0.jar",
    r"junit\junit\4.13.2\8ac9e16d933b6fb43bc7f576336b8f4d7eb5ba12\junit-4.13.2.jar",
    r"org.hamcrest\hamcrest-core\1.3\42a25dc3219429f0e5d060061f71acb49bf010a0\hamcrest-core-1.3.jar",
]

# 直接指向仓库真实源码，**不做副本**（副本会与源码漂移 —— verify_resampler/README
# 里特别警告过这个坑）。FeiniuUrl.kt 零 Android 依赖，可直接编。
SOURCES = [
    os.path.join(ROOT, "app", "src", "main", "java", "com", "nasmusic", "tv",
                 "backend", "impl", "FeiniuUrl.kt"),
    os.path.join(ROOT, "app", "src", "test", "java", "com", "nasmusic", "tv",
                 "backend", "impl", "FeiniuUrlTest.kt"),
]
TEST_CLASS = "com.nasmusic.tv.backend.impl.FeiniuUrlTest"
OUT = os.path.join(HERE, "out")


def join_cp(rel_paths):
    parts = []
    for rel in rel_paths:
        full = os.path.join(CACHE, rel)
        if not os.path.isfile(full):
            print("MISSING JAR: " + full)
            sys.exit(1)
        parts.append(full)
    return ";".join(parts)


def main():
    if "--clean" in sys.argv and os.path.isdir(OUT):
        shutil.rmtree(OUT)

    compiler_cp = join_cp(COMPILER_CP)
    runtime_cp = join_cp(RUNTIME_CP)

    print("=== 1/2 编译（kotlin-compiler-embeddable 2.2.10）===")
    compile_cmd = [
        JAVA, "-cp", compiler_cp,
        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-nowarn", "-cp", runtime_cp, "-d", OUT,
    ] + [os.path.join(HERE, s) for s in SOURCES]
    rc = subprocess.call(compile_cmd, cwd=HERE)
    if rc != 0:
        print("COMPILE FAILED rc=%d" % rc)
        sys.exit(rc)
    print("compile OK -> %s" % OUT)

    print("")
    print("=== 2/2 运行 JUnit4 ===")
    run_cmd = [
        JAVA, "-cp", OUT + ";" + runtime_cp,
        "org.junit.runner.JUnitCore", TEST_CLASS,
    ]
    rc = subprocess.call(run_cmd, cwd=HERE)
    sys.exit(rc)


if __name__ == "__main__":
    main()
