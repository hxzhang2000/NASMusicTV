# -*- coding: utf-8 -*-
"""扫描 test 源码里所有含 JVM 非法字符的反引号函数名。
JVM \u65b9\u6cd5\u540d\u4e0d\u5141\u8bb8: . ; [ / < >
Kotlin \u6e90\u7801\u5141\u8bb8\u53cd\u5f15\u53f7\u91cc\u653e\u4efb\u610f\u5b57\u7b26\uff0c\u4f46 JVM \u540e\u7aef\u62d2\u7edd \u2192 \u53ea\u5728\u6d4b\u8bd5\u6e90\u7801\u7f16\u8bd1\u65f6\u624d\u7206\u3002
\u672c\u811a\u672c\u53ea\u626b\u63cf\uff0c\u4e0d\u4fee\u6539\u3002
"""
import io, os, re, glob

ILLEGAL = set('.;[/<>')
FUN = re.compile(r'fun\s+`([^`]*)`')

root = 'app/src/test/java'
rows = []
for path in glob.glob(os.path.join(root, '**', '*.kt'), recursive=True):
    src = io.open(path, encoding='utf-8').read()
    lines = src.split('\n')
    for i, line in enumerate(lines, 1):
        m = FUN.search(line)
        if not m:
            continue
        name = m.group(1)
        bad = sorted(set(name) & ILLEGAL)
        if bad:
            # \u627e\u5bf9\u524d\u4e00\u884c\u662f\u5426 @Test
            prev = lines[i - 2].strip() if i >= 2 else ''
            rows.append((path, i, prev, name, ''.join(bad)))

print('=== \u542b JVM \u975e\u6cd5\u5b57\u7b26\u7684\u53cd\u5f15\u53f7\u51fd\u6570\u540d ===')
n_test = 0
for path, ln, prev, name, bad in rows:
    tag = '[TEST]' if prev.startswith('@Test') else '[  \u2007]'
    if prev.startswith('@Test'):
        n_test += 1
    print('%s %s:%d  \u975e\u6cd5=%-4s %s' % (tag, os.path.basename(path), ln, repr(bad), name))
print()
print('\u5408\u8ba1 %d \u5904\uff08\u5176\u4e2d @Test %d \u5904\uff09' % (len(rows), n_test))
