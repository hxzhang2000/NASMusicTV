#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""扫描 onnxruntime-android 各版本：minSdk 要求 + arm64 .so 的 16KB 对齐情况。

用法：
    python scan_ort_versions.py 1.20.0 1.21.1 1.22.0 1.23.2

对每个版本下载 AAR（约 28~52MB，缓存在 ./aar_cache/），然后报告：
    minSdk  |  arm64 libonnxruntime.so 的 PT_LOAD p_align  |  是否可升
"""
import io
import os
import re
import struct
import sys
import urllib.request
import zipfile

BASE = "https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android"
CACHE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "aar_cache")


def fetch(version):
    os.makedirs(CACHE, exist_ok=True)
    path = os.path.join(CACHE, "onnxruntime-android-%s.aar" % version)
    if os.path.exists(path) and os.path.getsize(path) > 1_000_000:
        return path, "缓存"
    url = "%s/%s/onnxruntime-android-%s.aar" % (BASE, version, version)
    tmp = path + ".part"
    with urllib.request.urlopen(url, timeout=300) as r, open(tmp, "wb") as f:
        while True:
            chunk = r.read(1 << 20)
            if not chunk:
                break
            f.write(chunk)
    os.replace(tmp, path)
    return path, "下载"


def min_sdk(aar):
    with zipfile.ZipFile(aar) as z:
        xml = z.read("AndroidManifest.xml").decode("utf-8", "replace")
    m = re.search(r'minSdkVersion="(\d+)"', xml)
    return int(m.group(1)) if m else None


def pt_load_aligns(blob):
    """返回 [(p_align, perm), ...]，仅 PT_LOAD 段。"""
    if blob[:4] != b"\x7fELF":
        return None
    ei_class = blob[4]
    if blob[5] != 1:
        return None
    if ei_class == 2:
        phoff, = struct.unpack_from("<Q", blob, 0x20)
        phentsize, phnum = struct.unpack_from("<HH", blob, 0x36)
        fmt, it, ifl, ia = "<IIQQQQQQ", 0, 1, 7
    else:
        phoff, = struct.unpack_from("<I", blob, 0x1C)
        phentsize, phnum = struct.unpack_from("<HH", blob, 0x2A)
        fmt, it, ifl, ia = "<IIIIIIII", 0, 6, 7
    out = []
    for i in range(phnum):
        f = struct.unpack_from(fmt, blob, phoff + i * phentsize)
        if f[it] != 1:
            continue
        perm = "".join(c for c, b in (("R", 4), ("W", 2), ("X", 1)) if f[ifl] & b)
        out.append((f[ia], perm))
    return out


def main():
    versions = sys.argv[1:]
    if not versions:
        print("用法: python scan_ort_versions.py <版本> [版本...]")
        return 1

    print("%-10s %-7s %-22s %-9s %s" % ("版本", "minSdk", "arm64 最小 p_align", "16KB", "结论（项目 minSdk=22）"))
    print("-" * 92)
    for v in versions:
        try:
            aar, how = fetch(v)
        except Exception as e:
            print("%-10s 下载失败: %s" % (v, e))
            continue
        try:
            ms = min_sdk(aar)
            with zipfile.ZipFile(aar) as z:
                blob = z.read("jni/arm64-v8a/libonnxruntime.so")
            aligns = pt_load_aligns(blob) or []
            mn = min(a for a, _ in aligns) if aligns else 0
            aligned = mn >= 16384
            if ms is not None and ms > 22:
                verdict = "❌ minSdk %d > 22，升不了" % ms
            elif aligned:
                verdict = "✅ 可升"
            else:
                verdict = "❌ 对齐仍不足，升了也没用"
            print("%-10s %-7s %-22s %-9s %s   [%s]" % (
                v, ms, mn, "✅" if aligned else "❌", verdict, how))
        except Exception as e:
            print("%-10s 解析失败: %s" % (v, e))
    return 0


if __name__ == "__main__":
    sys.exit(main())
