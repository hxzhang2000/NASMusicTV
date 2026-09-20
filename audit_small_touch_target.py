r"""
扫描「小尺寸 + 直接挂 clickable/combinedClickable」的写法 —— P0-26 静态自查的盲区。

背景：P0-26 的自查 grep 是

    \.(size|height|width)\((\s*)(4[0-9]|5[0-3])\.dp\)

只覆盖 40~53dp 区间。**小于 40dp 的 `size(...).clickable{}` 完全逃过检查**，
但同样不满足 44 物理 dp（竖屏 Compose ≥ 53.7dp）的无障碍下限。

## ⚠️ 为什么不用简单正则

第一版写成 `\.size\((\d+(\.\d+)?)\.dp\)`，结果**空转**：真实代码里尺寸常是表达式
（`size(if (active) 8.dp else 6.dp)`），正则匹配不到 → 报"0 处"，
看起来"代码很干净"，实际什么都没检查到。

本版改为：取 `size(` 的**括号配对内容**，抽出其中**全部 `.dp` 字面量**：
- 一个字面量都没有（如 `size(coverSide)`）→ 无法判断，跳过
- 全部 < 40dp → 候选
- 有任一 ≥ 40dp → 跳过（如 `size(portraitTouchTarget(44.dp))` —— 44 是横屏参数，
  竖屏实际 56dp，属受认可的写法）

## ⚠️ 第二处空转：只看「后续行」的 modifier 链

`size(...)` 与 `clickable` 可能写在**同一行**：

    Box(modifier = Modifier.size(8.dp).clickable { x() })

`CLICK` 正则的 `^\s*\.` 锚点只认行首，匹配不到行中的 `.clickable` → 单行写法整类逃检。
故另设 `CLICK_SAME_LINE`（不锚行首）做**同行 + 后续行双判定**，并补两条自证用例：
单行写法必须命中、注释里举例必须不命中。

## 用法

    python audit_small_touch_target.py [源码根目录]      # 默认 app/src/main/java
    python audit_small_touch_target.py --selftest        # 负向自证（务必先跑）

本脚本**入库于项目根目录**（与 `check_chinese.py` 同级），供
`docs/conventions-adaptive-ui.md` §6.5 / §8 引用，保证"可复现"。
"""
import os
import re
import sys

DEFAULT_ROOT = os.path.join("app", "src", "main", "java")

SMALL_LIMIT = 40.0
DP_LITERAL = re.compile(r"(\d+(?:\.\d+)?)\.dp")
WIDENING = ("fillMaxSize", "fillMaxWidth", "fillMaxHeight", "weight(", "widthIn(min", "heightIn(min")
CLICK = re.compile(r"^\s*\.(clickable|combinedClickable)")
# 同行写法：`Modifier.size(8.dp).clickable { }` —— `CLICK` 的 `^\s*\.` 锚点只认行首，
# 匹配不到行中的 `.clickable`，故单列一条（**首版护栏正是漏在这里**）。
CLICK_SAME_LINE = re.compile(r"\.(clickable|combinedClickable)\b")


def _is_comment_line(line):
    """注释行不参与判定 —— 否则 KDoc 里举例的 `.size(8.dp).clickable` 会被误报。"""
    t = line.lstrip()
    return t.startswith("//") or t.startswith("*") or t.startswith("/*")


def _paren(text, open_idx):
    depth = 0
    i = open_idx
    in_str = False
    while i < len(text):
        ch = text[i]
        if in_str:
            if ch == "\\":
                i += 2
                continue
            if ch == '"':
                in_str = False
        else:
            if ch == '"':
                in_str = True
            elif ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
                if depth == 0:
                    return i
        i += 1
    return -1


def _is_non_interactive(lines, i):
    """`Spacer(` / `Divider` 常写在 `.size(...)` 的**前几行**（modifier 链的开头），
    只检查当前行会漏 —— 自证用例 `Spacer(\\n  modifier = ...\\n  .size(8.dp)` 就是这么挂的。"""
    for k in range(max(0, i - 3), i + 1):
        if "Spacer(" in lines[k] or "Divider" in lines[k]:
            return True
    return False


def scan_lines(lines):
    """返回命中的行号列表（1-based）"""
    hits = []
    for i, line in enumerate(lines):
        idx = line.find(".size(")
        if idx < 0:
            continue
        if _is_comment_line(line):
            continue
        if _is_non_interactive(lines, i):
            continue
        open_p = line.index("(", idx)
        # 尺寸表达式可能跨行 → 先在本行内配对，失败则向后拼接
        blob = line[open_p:]
        end = _paren(blob, 0)
        if end < 0:
            for k in range(i + 1, min(len(lines), i + 4)):
                blob += "\n" + lines[k]
                end = _paren(blob, 0)
                if end >= 0:
                    break
        if end < 0:
            continue
        args = blob[1:end]
        literals = [float(v) for v in DP_LITERAL.findall(args)]
        if not literals:
            continue  # 无法判断（变量 / 表达式无字面量）
        if any(v >= SMALL_LIMIT for v in literals):
            continue
        # 手势可能在**同一行**（`Modifier.size(8.dp).clickable { }`），也可能在**后续行**
        # （modifier 链纵向写）。⚠️ 只看后续行会漏掉单行写法 —— 自证用例
        # 「同行写法的 size(...).clickable」就是为此设的（首版护栏在此空转）。
        chain = []
        j = i + 1
        while j < len(lines) and len(chain) < 12:
            nxt = lines[j]
            if not nxt.lstrip().startswith("."):
                break
            chain.append(nxt)
            if CLICK.match(nxt):
                break
            j += 1
        same_line = CLICK_SAME_LINE.search(line[idx:].split("//")[0])
        if same_line is None and not any(CLICK.match(c) for c in chain):
            continue
        window = line + "\n" + "\n".join(chain)
        if any(w in window for w in WIDENING):
            continue
        hits.append(i + 1)
    return hits


def selftest():
    """负向自证：修复前必须命中、修复后必须不命中"""
    cases = [
        (
            "修复前（动态尺寸 + clickable）",
            """Box(
                modifier = Modifier
                    .size(if (active) 8.dp else 6.dp)
                    .clip(CircleShape)
                    .clickable { onSwitch(m) }
            )""",
            True,
        ),
        (
            "修复后（受认可的 portraitTouchTarget）",
            """Box(
                modifier = Modifier
                    .size(portraitTouchTarget(44.dp))
                    .clickable { onSwitch(m) }
            )""",
            False,
        ),
        (
            "Spacer 不算触摸目标",
            """Spacer(
                modifier = Modifier
                    .size(8.dp)
                    .clickable { x() }
            )""",
            False,
        ),
        (
            "无 clickable 的纯视觉元素",
            """Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(Color.Red)
            )""",
            False,
        ),
        (
            "链上有 fillMaxSize 撑大",
            """Box(
                modifier = Modifier
                    .size(6.dp)
                    .fillMaxSize()
                    .clickable { x() }
            )""",
            False,
        ),
        (
            "同行写法的 size(...).clickable（首版护栏在此空转）",
            """Box(modifier = Modifier.size(8.dp).clickable { x() })""",
            True,
        ),
        (
            "注释里举例的 size(...).clickable 不算违规",
            """// 反例：Box(Modifier.size(8.dp).clickable { })""",
            False,
        ),
    ]
    ok = True
    for label, src, expect in cases:
        got = len(scan_lines(src.splitlines())) > 0
        status = "PASS" if got == expect else "FAIL"
        if got != expect:
            ok = False
        print("  [%s] %s -> 命中=%s（期望 %s）" % (status, label, got, expect))
    return ok


def main(argv):
    if "--selftest" in argv:
        print("自证：")
        return 0 if selftest() else 1

    root = argv[1] if len(argv) > 1 else DEFAULT_ROOT
    if not os.path.isdir(root):
        print("找不到源码根目录：%s" % root)
        return 2

    hits = []
    files = 0
    for dirpath, _, filenames in os.walk(root):
        for fn in filenames:
            if not fn.endswith(".kt"):
                continue
            files += 1
            path = os.path.join(dirpath, fn)
            lines = open(path, encoding="utf-8", errors="replace").read().splitlines()
            for ln in scan_lines(lines):
                hits.append((path.replace("\\", "/"), ln))

    print("扫描 .kt 文件：%d" % files)
    print("可疑「小尺寸(<40dp) + 同链 clickable」：%d 处" % len(hits))
    for p, ln in hits:
        print("  %s:%d" % (p, ln))
    return 1 if hits else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
