#!/bin/sh
# Test helper: tap the first on-screen element whose text is $1.
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1
B=$(python3 - "$1" <<'PY'
import sys,re
x=open('/tmp/ui.xml',encoding='utf-8').read()
for m in re.finditer(r'<node [^>]*>', x):
    n=m.group(0)
    t=re.search(r' text="([^"]*)"',n); d=re.search(r'content-desc="([^"]*)"',n)
    if (t and t.group(1)==sys.argv[1]) or (d and d.group(1)==sys.argv[1]):
        a=list(map(int,re.findall(r'\d+',re.search(r'bounds="([^"]*)"',n).group(1))))
        print((a[0]+a[2])//2,(a[1]+a[3])//2); break
PY
)
echo "tap $1 -> $B"
[ -n "$B" ] && adb shell input tap $B
