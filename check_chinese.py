import re
import sys

files = [
    r'app/src/main/java/com/nasmusic/tv/net/BackupTransferServer.kt',
    r'app/src/main/java/com/nasmusic/tv/net/ModelTransferServer.kt',
    r'app/src/main/java/com/nasmusic/tv/net/RemoteControlHtml.kt',
]

for fpath in files:
    try:
        with open(fpath, encoding='utf-8') as f:
            content = f.read()
        # Find Chinese strings in quotes
        chinese = re.findall(r'"([^"]*[\u4e00-\u9fff][^"]*?)"', content)
        if chinese:
            fname = fpath.split('\\')[-1]
            print(f'\n=== {fname} === ({len(chinese)} strings)')
            for s in chinese[:15]:
                print(f'  {s[:60]}')
            if len(chinese) > 15:
                print(f'  ... and {len(chinese)-15} more')
    except Exception as e:
        print(f'Error reading {fpath}: {e}')
