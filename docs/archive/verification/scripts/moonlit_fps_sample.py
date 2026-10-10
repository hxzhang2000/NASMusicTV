#!/usr/bin/env python3
# 明月（E44）真机帧率**采样器**。
#
# 为什么需要：§十三 U1 要求「三档各稳态 ≥ 25/20/15 fps」，而 2026-10-10 那次验收所有者给的是
# **整体定性裁决**，U1/U2/U3 一笔数字都没采 ⇒ 文档只能写「判读通过（未留数）」。
# 本脚本把「重测 U1」变成一条命令，采出来的东西可复查（截图条带 + 元数据），⛔ 不再靠口头。
#
# 用法（仓库根，电视已 adb 连接、已切到「明月」且在播放、档位已手动设好）：
#   python -X utf8 docs/archive/verification/scripts/moonlit_fps_sample.py --tier HIGH
#   python -X utf8 docs/archive/verification/scripts/moonlit_fps_sample.py --tier LOW --samples 20 --gap 2
# 环境变量：NASMUSIC_ADB（adb 路径）/ NASMUSIC_SERIAL（设备 serial），缺省见下。
#
# 读数口径 = 画面右上角的 `nasmusic_fps` 徽标（`FpsMeter`，0.5 s **滚动窗**，量的是 Compose
# 帧回调到点率 ≈ 上屏间隔；见 `app/src/main/java/com/nasmusic/tv/visualizer/FpsMeter.kt` 的 KDoc）。
# 开关：`adb shell settings put global nasmusic_fps 1`（0 或删键即关，release 包同样有效）。
#
# ⚠️ **本脚本不做 OCR**：它只负责「按固定节拍抓 N 帧右上角裁片 + 堆成一张条带 + 落一份元数据」，
#   数字由判读者从条带上读。自检只保证**每帧裁片里真的有绿色读数**（颜色不符就抛错）——
#   读数没开、没进可视化、切走效果这三种"白采"情形都会被当场挡下。
# ⚠️ API 22 上 `dumpsys gfxinfo <pkg> framestats` 与 `--latency` 均不可用（实测 framestats
#   只回落到旧版报告、无 profile 行），所以徽标是这台设备上唯一可复现的帧率通道。

import argparse
import datetime
import io
import os
import subprocess
import sys
import time

from PIL import Image

DEFAULT_ADB = r"C:\Users\hxzha\AppData\Local\Android\Sdk\platform-tools\adb.exe"
DEFAULT_SERIAL = "192.168.0.114:5555"
PKG = "com.nasmusic.tv"
# 右上角徽标裁片（1920x1080 实测：文字色 0xFF9BFFB4，圆角黑底）
BADGE_BOX = (1740, 0, 1920, 70)
BADGE_RGB = (0x9B, 0xFF, 0xB4)
# 一帧裁片里至少要有这么多"接近徽标色"的像素，否则判为读数不在屏上
# （实测 1920x1080 下 "23.2 fps" 这类读数为 108 px；读数不在屏上时为 0 ⇒ 60 是"有字"与"没字"的分界）
MIN_BADGE_PIXELS = 60


def fail(msg):
    raise SystemExit("FAIL: " + msg)


def adb(adb_path, serial, args, binary=False):
    cmd = [adb_path, "-s", serial] + args
    out = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=True).stdout
    return out if binary else out.decode("utf-8", "replace")


def grab(adb_path, serial):
    raw = adb(adb_path, serial, ["exec-out", "screencap", "-p"], binary=True)
    if len(raw) < 1000:
        fail("screencap 返回 %d 字节，抓帧失败" % len(raw))
    return Image.open(io.BytesIO(raw)).convert("RGB")


def badge_pixels(img):
    """裁片里徽标色的像素数（容差按 1/3 通道，抗缩放与黑底 alpha 混色）。"""
    c = img.crop(BADGE_BOX)
    px = c.load()
    n = 0
    for y in range(c.size[1]):
        for x in range(c.size[0]):
            r, g, b = px[x, y]
            if abs(r - BADGE_RGB[0]) < 85 and abs(g - BADGE_RGB[1]) < 55 and abs(b - BADGE_RGB[2]) < 75:
                n += 1
    return n


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tier", required=True, choices=["LOW", "MED", "HIGH"],
                    help="画质档（须由判读者在设置里手动确认，本脚本读不到 DataStore）")
    ap.add_argument("--samples", type=int, default=12)
    ap.add_argument("--gap", type=float, default=3.0)
    ap.add_argument("--out", default=os.path.join("output", "moonlit_fps"))
    args = ap.parse_args()

    adb_path = os.environ.get("NASMUSIC_ADB", DEFAULT_ADB)
    serial = os.environ.get("NASMUSIC_SERIAL", DEFAULT_SERIAL)
    if not os.path.exists(adb_path):
        fail("找不到 adb：%s（用 NASMUSIC_ADB 指定）" % adb_path)
    if os.environ.get("ANDROID_SERIAL") and os.environ["ANDROID_SERIAL"] != serial:
        print("note: ANDROID_SERIAL=%s 被忽略，本脚本用 NASMUSIC_SERIAL=%s"
              % (os.environ["ANDROID_SERIAL"], serial))

    if adb(adb_path, serial, ["get-state"]).strip() != "device":
        fail("设备 %s 不在 device 状态" % serial)
    info = adb(adb_path, serial, ["shell", "dumpsys", "package", PKG])
    ver = [l.strip() for l in info.splitlines() if "versionName" in l or "versionCode" in l][:2]
    if not ver:
        fail("%s 未安装在 %s 上" % (PKG, serial))
    if adb(adb_path, serial, ["shell", "settings", "get", "global", "nasmusic_fps"]).strip() != "1":
        fail("帧率徽标未开启 ⇒ 采了也白采。先跑：adb -s %s shell settings put global nasmusic_fps 1"
             % serial)

    if not os.path.isdir(args.out):
        os.makedirs(args.out)
    ts = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    strip_path = os.path.join(args.out, "fps_strip_%s_%s.png" % (args.tier, ts))
    meta_path = os.path.join(args.out, "fps_samples_%s_%s.txt" % (args.tier, ts))

    crops = []
    first = grab(adb_path, serial)
    if first.size != (1920, 1080):
        print("note: 分辨率 %dx%d（裁片坐标按 1920x1080 标定）" % first.size)
    for i in range(args.samples):
        im = first if i == 0 else grab(adb_path, serial)
        n = badge_pixels(im)
        if n < MIN_BADGE_PIXELS:
            fail("第 %d 帧裁片里徽标色像素只有 %d（<%d）⇒ 读数不在屏上："
                 "可能没进可视化、效果被切走、或画面全黑" % (i + 1, n, MIN_BADGE_PIXELS))
        crops.append(im.crop(BADGE_BOX))
        print("sample %d/%d badge_px=%d" % (i + 1, args.samples, n))
        if i < args.samples - 1:
            time.sleep(args.gap)

    w = BADGE_BOX[2] - BADGE_BOX[0]
    h = BADGE_BOX[3] - BADGE_BOX[1]
    mont = Image.new("RGB", (w, h * len(crops) + (len(crops) - 1) * 4), (38, 38, 38))
    y = 0
    for c in crops:
        mont.paste(c, (0, y))
        y += h + 4
    mont.save(strip_path)

    with io.open(meta_path, "w", encoding="utf-8", newline="\n") as f:
        f.write("# E44 明月 U1 帧率采样（读数由判读者从条带图读取）\n")
        f.write("timestamp_local=%s\n" % ts)
        f.write("device=%s\n" % serial)
        f.write("model=%s\n" % adb(adb_path, serial, ["shell", "getprop", "ro.product.model"]).strip())
        f.write("android=%s (api %s)\n" % (
            adb(adb_path, serial, ["shell", "getprop", "ro.build.version.release"]).strip(),
            adb(adb_path, serial, ["shell", "getprop", "ro.build.version.sdk"]).strip()))
        f.write("package=%s\n" % " ".join(ver))
        f.write("tier_declared_by_owner=%s\n" % args.tier)
        f.write("samples=%d gap_s=%.1f window_s=%.1f\n"
                % (len(crops), args.gap, (len(crops) - 1) * args.gap))
        f.write("meter=FpsMeter 0.5s rolling window (Compose frame-callback rate)\n")
        f.write("strip=%s\n" % os.path.basename(strip_path))
        f.write("\n# 读数（判读者填写后才是结论）：\n")

    print("strip: %s" % strip_path)
    print("meta : %s" % meta_path)


if __name__ == "__main__":
    main()
