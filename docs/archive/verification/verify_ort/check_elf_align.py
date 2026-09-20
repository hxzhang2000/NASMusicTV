#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""检查 ELF 共享库的 PT_LOAD 段对齐（Android 16KB page size 要求 p_align >= 16384）。

不依赖 readelf（Windows 上没有），直接解析 ELF 头与程序头。
支持 ELF32 / ELF64，小端。
"""
import struct
import sys


def parse(path):
    with open(path, "rb") as f:
        data = f.read()

    if data[:4] != b"\x7fELF":
        return None, "不是 ELF 文件"
    ei_class = data[4]      # 1 = 32bit, 2 = 64bit
    ei_data = data[5]       # 1 = little endian
    if ei_data != 1:
        return None, "仅支持小端"

    if ei_class == 2:       # ELF64，phdr 56 字节
        e_phoff, = struct.unpack_from("<Q", data, 0x20)
        e_phentsize, e_phnum = struct.unpack_from("<HH", data, 0x36)
        # p_type(0,4) p_flags(4,4) p_offset(8,8) p_vaddr(16,8) p_paddr(24,8)
        # p_filesz(32,8) p_memsz(40,8) p_align(48,8)
        fmt = "<IIQQQQQQ"
        idx_type, idx_flags, idx_align = 0, 1, 7
    elif ei_class == 1:     # ELF32，phdr 32 字节
        e_phoff, = struct.unpack_from("<I", data, 0x1C)
        e_phentsize, e_phnum = struct.unpack_from("<HH", data, 0x2A)
        # p_type(0,4) p_offset(4,4) p_vaddr(8,4) p_paddr(12,4)
        # p_filesz(16,4) p_memsz(20,4) p_flags(24,4) p_align(28,4)
        fmt = "<IIIIIIII"
        idx_type, idx_flags, idx_align = 0, 6, 7
    else:
        return None, "未知 EI_CLASS=%d" % ei_class

    loads = []
    for i in range(e_phnum):
        base = e_phoff + i * e_phentsize
        fields = struct.unpack_from(fmt, data, base)
        if fields[idx_type] != 1:       # PT_LOAD
            continue
        align = fields[idx_align]
        flags = fields[idx_flags]
        perm = "".join(c for c, bit in (("R", 4), ("W", 2), ("X", 1)) if flags & bit)
        loads.append((align, perm))

    return (ei_class, loads), None


def main():
    worst = None
    for path in sys.argv[1:]:
        info, err = parse(path)
        name = path.replace("\\", "/").split("/")[-1]
        parent = path.replace("\\", "/").split("/")[-2] if "/" in path else ""
        tag = "%s/%s" % (parent, name) if parent else name
        if err:
            print("%-46s  解析失败: %s" % (tag, err))
            continue
        ei_class, loads = info
        aligns = [a for a, _ in loads]
        mn = min(aligns) if aligns else 0
        ok = mn >= 16384
        detail = ", ".join("%s align=%d" % (p, a) for a, p in loads)
        print("%-46s  %s  最小 p_align=%d  [%s]" % (tag, "✅ 16KB" if ok else "❌ 不足", mn, detail))
        if worst is None or mn < worst:
            worst = mn
    print("")
    print("结论：最小 p_align = %d → %s" % (
        worst or 0,
        "满足 Android 16KB page size 要求" if (worst or 0) >= 16384 else "不满足（lint 会报 Aligned16KB）"))


if __name__ == "__main__":
    main()
