#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""只取 AAR（ZIP）里的小文件 / 大文件的开头若干字节，不下载整个 28~52MB 包。

做法：HTTP Range 请求
  1. 取尾部 64KB → 解析 EOCD → 中央目录的偏移与长度
  2. 取中央目录 → 找到目标条目的 local header 偏移与压缩后大小
  3. 取该条目的 local header + 数据 → 解压

两种模式：
  * 默认：完整解出该条目并写到 stdout（适合 AndroidManifest.xml 这类几百字节的小文件）
  * `--head N`：只取压缩流的前若干字节做**增量**解压，输出前 N 字节。
    deflate 是流式的，解前 4KB 只需压缩流的头几 KB —— 于是 28MB 的
    `libonnxruntime.so` 也能在几百 KB 流量内拿到 ELF 头与程序头表。
    这正是核对 16KB 对齐（PT_LOAD 的 p_align）所需的全部内容。

用法：
    python fetch_zip_entry.py <url> AndroidManifest.xml
    python fetch_zip_entry.py <url> jni/arm64-v8a/libonnxruntime.so --head 65536 -o out.bin
    python fetch_zip_entry.py <url> --list

历史坑（2026-09-14）：本脚本第一版 `parse_central_directory` 的格式串写成
`"<HHHHIIIHHHHHII"`（4 个 H），实际中央目录从 method 起只有 3 个 H
（method/mtime/mdate），导致 14 个字段塞进 13 个变量 → `ValueError`。
当时调用方带了 `2>/dev/null`，错误被吞掉，表现为「5 个版本全部无输出」。
**排查结论：调试期绝不要屏蔽 stderr。**
"""
import struct
import sys
import urllib.request
import zlib


def http_range(url, start, end, retries=3):
    last = None
    for _ in range(retries):
        try:
            req = urllib.request.Request(url, headers={"Range": "bytes=%d-%d" % (start, end)})
            with urllib.request.urlopen(req, timeout=180) as r:
                return r.read()
        except Exception as e:      # 网络抖动就重试
            last = e
    raise last


def content_length(url):
    """先试 HEAD；某些镜像不支持 HEAD，则用 Range: bytes=0-0 读 Content-Range。"""
    try:
        req = urllib.request.Request(url, method="HEAD")
        with urllib.request.urlopen(req, timeout=60) as r:
            if "Content-Length" in r.headers:
                return int(r.headers["Content-Length"])
    except Exception:
        pass
    req = urllib.request.Request(url, headers={"Range": "bytes=0-0"})
    with urllib.request.urlopen(req, timeout=60) as r:
        cr = r.headers.get("Content-Range")          # 形如 bytes 0-0/51897836
        if cr and "/" in cr:
            return int(cr.rsplit("/", 1)[1])
        raise RuntimeError("无法确定文件大小（HEAD 与 Content-Range 均失败）")


def find_eocd(tail):
    """在尾部数据里找 EOCD 签名 PK\\x05\\x06，返回 (cd_offset, cd_size)。"""
    sig = b"PK\x05\x06"
    idx = tail.rfind(sig)
    if idx < 0:
        raise RuntimeError("未找到 EOCD 签名")
    # EOCD: sig(4) disk(2) cd_disk(2) n_this(2) n_total(2) cd_size(4) cd_offset(4) comment_len(2)
    _, _, _, _, _, cd_size, cd_offset = struct.unpack_from("<IHHHHII", tail, idx)
    if cd_offset == 0xFFFFFFFF or cd_size == 0xFFFFFFFF:
        raise RuntimeError("需要 ZIP64 支持（本脚本未实现）")
    return cd_offset, cd_size


def parse_central_directory(cd):
    """遍历中央目录，返回 {name: (local_offset, comp_size, method, uncomp_size)}。

    中央目录项布局（定长 46 字节 + 变长）：
      0  sig(4)          4  ver_made(2)     6  ver_need(2)    8  flags(2)
     10  method(2)      12  mtime(2)       14  mdate(2)       16  crc(4)
     20  comp_size(4)   24  uncomp_size(4) 28  name_len(2)    30  extra_len(2)
     32  comment_len(2) 34  disk(2)        36  int_attr(2)    38  ext_attr(4)
     42  local_offset(4)  → 46
    从偏移 10 起是 36 字节：H H H I I I H H H H H I I（13 个字段）。
    """
    out = {}
    i = 0
    sig = b"PK\x01\x02"
    fmt = "<HHHIIIHHHHHII"       # method mtime mdate crc comp uncomp name extra comment disk int_attr ext_attr local_off
    while i + 46 <= len(cd):
        if cd[i:i + 4] != sig:
            break
        (method, _mt, _md, _crc, comp_size, uncomp_size, name_len, extra_len,
         comment_len, _disk, _ia, _ea, local_off) = struct.unpack_from(fmt, cd, i + 10)
        name = cd[i + 46: i + 46 + name_len].decode("utf-8", "replace")
        out[name] = (local_off, comp_size, method, uncomp_size)
        i += 46 + name_len + extra_len + comment_len
    return out


def data_start_of(url, local_off):
    """读 local header，返回（数据起始偏移, comp_size, method, uncomp_size）。"""
    head = http_range(url, local_off, local_off + 29)
    # Local header: sig(4) ver(2) flags(2) method(2) mtime(2) mdate(2) crc(4)
    #               comp_size(4) uncomp_size(4) name_len(2) extra_len(2)  → 30
    method, = struct.unpack_from("<H", head, 8)
    comp_size, uncomp_size = struct.unpack_from("<II", head, 18)
    name_len, extra_len = struct.unpack_from("<HH", head, 26)
    return local_off + 30 + name_len + extra_len, comp_size, method, uncomp_size


def read_entry(url, entry):
    """完整解出条目内容。"""
    local_off, comp_size, method, _uncomp = entry
    start, comp_size2, method2, _u2 = data_start_of(url, local_off)
    comp_size = comp_size2 or comp_size
    method = method2
    raw = http_range(url, start, start + comp_size - 1)
    if method == 0:
        return raw
    if method == 8:
        return zlib.decompress(raw, -15)
    raise RuntimeError("未知压缩方法 %d" % method)


def read_head(url, entry, nbytes):
    """只解出条目内容的前 nbytes 字节（增量解压，省流量）。"""
    local_off, comp_size, method, _uncomp = entry
    start, comp_size2, method2, _u2 = data_start_of(url, local_off)
    comp_size = comp_size2 or comp_size
    method = method2
    if method == 0:                                   # stored，直接读前 n 字节
        return http_range(url, start, start + nbytes - 1)

    # deflate：从压缩流头部开始取块，边取边解，够了就停
    d = zlib.decompressobj(-15)
    out = b""
    chunk = 65536
    pos = start
    end = start + comp_size
    while pos < end and len(out) < nbytes:
        raw = http_range(url, pos, min(pos + chunk - 1, end - 1))
        out += d.decompress(raw)
        pos += len(raw)
        if not raw:
            break
    return out[:nbytes]


def main():
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        return 2
    url = args[0]
    rest = args[1:]

    head_n = None
    out_path = None
    target = None
    i = 0
    while i < len(rest):
        a = rest[i]
        if a == "--head":
            i += 1
            head_n = int(rest[i])
        elif a in ("-o", "--out"):
            i += 1
            out_path = rest[i]
        elif a == "--list":
            target = None
            out_path = "__LIST__"
        else:
            target = a
        i += 1

    total = content_length(url)
    tail_start = max(0, total - 65536)
    tail = http_range(url, tail_start, total - 1)
    cd_offset, cd_size = find_eocd(tail)
    cd = http_range(url, cd_offset, cd_offset + cd_size - 1)
    entries = parse_central_directory(cd)

    if out_path == "__LIST__":
        for k, v in entries.items():
            print("%-58s comp=%-9d uncomp=%-9d m=%d" % (k, v[1], v[3], v[2]))
        return 0

    if target not in entries:
        print("条目不存在: %s" % target, file=sys.stderr)
        print("可用条目（前 30）:", list(entries)[:30], file=sys.stderr)
        return 1

    data = read_head(url, entries[target], head_n) if head_n else read_entry(url, entries[target])
    if out_path:
        with open(out_path, "wb") as f:
            f.write(data)
        print("已写入 %s（%d 字节）" % (out_path, len(data)))
    else:
        sys.stdout.buffer.write(data)
    return 0


if __name__ == "__main__":
    sys.exit(main())
