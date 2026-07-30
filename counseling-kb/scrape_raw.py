# -*- coding: utf-8 -*-
"""下载  全部案例逐字稿 JSON（礼貌限速）"""
import json
import os
import sys
import time
import urllib.request

BASE = "https:///data/generated/raw/{}.json"
HERE = os.path.dirname(os.path.abspath(__file__))
RAW_DIR = os.path.join(HERE, "raw")
UA = "Mozilla/5.0 (personal-study; contact-none) counseling-kb-fetch/1.0"

def main():
    cases = json.load(open(os.path.join(HERE, "cases.json"), encoding="utf-8"))["cases"]
    slugs = [c["slug"] for c in cases]
    ok, fail = 0, []
    for i, slug in enumerate(slugs, 1):
        dst = os.path.join(RAW_DIR, slug + ".json")
        if os.path.exists(dst) and os.path.getsize(dst) > 1000:
            ok += 1
            continue
        req = urllib.request.Request(BASE.format(slug), headers={"User-Agent": UA})
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                data = r.read()
            # SPA fallback 返回的是 HTML，校验是 JSON 才落盘
            json.loads(data.decode("utf-8"))
            with open(dst, "wb") as f:
                f.write(data)
            ok += 1
        except Exception as e:
            fail.append((slug, str(e)))
        if i % 50 == 0:
            print(f"progress {i}/{len(slugs)} ok={ok} fail={len(fail)}", flush=True)
        time.sleep(0.4)
    print(f"DONE ok={ok} fail={len(fail)}")
    for s, e in fail:
        print("FAIL", s, e)

if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main()
