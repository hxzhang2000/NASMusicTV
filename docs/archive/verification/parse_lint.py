"""解析 lint 文本报告，统计 UnsafeOptInUsageError 站点分布。"""
import collections
import sys

PATH = (
    "app/build/intermediates/lint_intermediate_text_report/debug/"
    "lintReportDebug/lint-results-debug.txt"
)

with open(PATH, encoding="utf-8", errors="replace") as fh:
    text = fh.read()

sites = []
for raw in text.splitlines():
    if "UnsafeOptInUsageError" not in raw:
        continue
    if not raw.startswith("D:"):
        continue
    # 形如  D:\path\File.kt:123: Error: ... [UnsafeOptInUsageError ...]
    head, _, _rest = raw.partition(": Error:")
    path, _, line = head.rpartition(":")
    short = path.split("app\\src\\main\\java\\")[-1]
    sites.append((short, int(line)))

print("UnsafeOptInUsageError 站点数:", len(sites))
by_file = collections.defaultdict(list)
for short, line in sites:
    by_file[short].append(line)

print("涉及文件数:", len(by_file))
for short, lines in sorted(by_file.items(), key=lambda kv: -len(kv[1])):
    print(f"  {len(lines):3}  {short}")
    print(f"        {sorted(lines)}")

if len(sys.argv) > 1:
    total = 0
    for short, lines in by_file.items():
        total += len(lines)
    print("总计:", total)
