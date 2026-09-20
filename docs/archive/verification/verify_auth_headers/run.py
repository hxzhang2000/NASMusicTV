"""
BackendAuthHeaders 独立 JVM 验证 harness（2026-09-15，修复 F-2 配套）

与 verify_feiniu_url 同一思路：绕过 Gradle（本机 test worker 不可用，见 AGENTS.md），
用 kotlin-compiler-embeddable 编译真实源码 + 真实测试类 + AppLog 桩，JUnitCore 运行。

BackendAuthHeaders.kt 只依赖 com.nasmusic.tv.util.AppLog（Android 侧），
故提供 stubs/AppLog.kt 打桩（真实 AppLog 依赖 BuildConfig + android.util.Log）。
"""
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
CACHE = r"C:\Users\hxzha\.gradle\caches\modules-2\files-2.1"
JAVA = r"C:\Program Files\Android\Android Studio\jbr\bin\java.exe"

COMPILER_CP = [
    r"org.jetbrains.kotlin\kotlin-compiler-embeddable\2.2.10\6882d39ef03b18879c1a5d2a83c2212361bed5fe\kotlin-compiler-embeddable-2.2.10.jar",
    r"org.jetbrains.kotlin\kotlin-stdlib\2.2.10\30de6faa127a4a012db8e71bf1b9c0a99b1402b2\kotlin-stdlib-2.2.10.jar",
    r"org.jetbrains.kotlin\kotlin-reflect\2.2.10\98d0ca9819d98cb3aa5c0a25793830b6659feee2\kotlin-reflect-2.2.10.jar",
    r"org.jetbrains.kotlin\kotlin-script-runtime\2.2.10\712f9bc08f378c13a4a00f0bbb28c76e59183e83\kotlin-script-runtime-2.2.10.jar",
    r"org.jetbrains.kotlin\kotlin-daemon-embeddable\2.2.10\212fd3c2c6e4e5d0f4db3c4191233e2b622380cc\kotlin-daemon-embeddable-2.2.10.jar",
    r"org.jetbrains.kotlinx\kotlinx-coroutines-core-jvm\1.10.2\4a9f78ef49483748e2c129f3d124b8fa249dafbf\kotlinx-coroutines-core-jvm-1.10.2.jar",
    r"com.intellij\annotations\12.0\bbcf6448f6d40abe506e2c83b70a3e8bfd2b4539\annotations-12.0.jar",
]

RUNTIME_CP = [
    r"org.jetbrains.kotlin\kotlin-stdlib\2.2.10\30de6faa127a4a012db8e71bf1b9c0a99b1402b2\kotlin-stdlib-2.2.10.jar",
    r"junit\junit\4.13.2\8ac9e16d933b6fb43bc7f576336b8f4d7eb5ba12\junit-4.13.2.jar",
    r"org.hamcrest\hamcrest-core\1.3\42a25dc3219429f0e5d060061f71acb49bf010a0\hamcrest-core-1.3.jar",
]

SOURCES = [
    os.path.join(ROOT, "app", "src", "main", "java", "com", "nasmusic", "tv",
                 "backend", "BackendAuthHeaders.kt"),
    os.path.join(ROOT, "app", "src", "test", "java", "com", "nasmusic", "tv",
                 "backend", "BackendAuthHeadersTest.kt"),
    os.path.join(HERE, "stubs", "AppLog.kt"),
]
TEST_CLASS = "com.nasmusic.tv.backend.BackendAuthHeadersTest"
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
    compiler_cp = join_cp(COMPILER_CP)
    runtime_cp = join_cp(RUNTIME_CP)

    print("=== 1/2 编译（kotlin-compiler-embeddable 2.2.10）===")
    compile_cmd = [
        JAVA, "-cp", compiler_cp,
        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-nowarn", "-cp", runtime_cp, "-d", OUT,
    ] + SOURCES
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
