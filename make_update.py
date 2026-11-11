# -*- coding: utf-8 -*-
"""
生成应用内更新用的 update.json，并打印需要上传的文件信息。

用法：
  python make_update.py --apk release\\塔斯汀签到.apk --notes "修复了xxx"

输出：
  update.json   提交到仓库根目录，供应用内更新使用
  APK 上传到 GitHub Release 附件

发布后：
  update.json 里的 apkUrl / sha256 就是 App 拉取时校验的依据
"""

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys

if sys.stdout.encoding and sys.stdout.encoding.lower() not in ("utf-8", "utf8"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

HERE = os.path.dirname(os.path.abspath(__file__))

AAPT = os.path.join(
    os.environ.get("LOCALAPPDATA", ""),
    r"Android\Sdk\build-tools\34.0.0\aapt2.exe")


def read_apk_info(apk):
    """从 APK 读取 versionCode / versionName"""
    out = subprocess.run([AAPT, "dump", "badging", apk],
                         capture_output=True, text=True, encoding="utf-8",
                         errors="replace")
    text = out.stdout or ""
    m = re.search(r"versionCode='(\d+)'", text)
    v = re.search(r"versionName='([^']+)'", text)
    if not m or not v:
        raise SystemExit("[错误] 无法从 APK 读取版本信息，检查 aapt2 路径：" + AAPT)
    return int(m.group(1)), v.group(1)


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", required=True, help="要发布的 APK 路径")
    ap.add_argument("--url", required=True,
    help="APK 的 https 直链。请使用 GitHub Release 附件地址：\n"
                         "https://github.com/用户名/仓库/releases/download/v1.5/塔斯汀签到.apk")
    ap.add_argument("--notes", default="", help="更新说明")
    ap.add_argument("--force", action="store_true", help="强制更新（不给稍后）")
    ap.add_argument("--out", default=os.path.join(HERE, "update.json"))
    args = ap.parse_args()

    if not os.path.exists(args.apk):
        raise SystemExit("[错误] 找不到 APK：" + args.apk)
    if not args.url.startswith("https://"):
        raise SystemExit("[错误] apkUrl 必须是 https:// 开头（App 端有校验）")

    code, name = read_apk_info(args.apk)
    digest = sha256_of(args.apk)
    size_mb = os.path.getsize(args.apk) / 1024 / 1024

    manifest = {
        "versionCode": code,
        "versionName": name,
        "apkUrl": args.url,
        "sha256": digest,
        "notes": args.notes,
        "force": bool(args.force),
    }

    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)

    print("=" * 60)
    print("update.json 已生成:", args.out)
    print("=" * 60)
    print(f"版本      : {name} (versionCode {code})")
    print(f"大小      : {size_mb:.1f} MB")
    print(f"SHA-256   : {digest}")
    print(f"下载地址  : {args.url}")
    print(f"强制更新  : {args.force}")
    print()
    print("接下来：")
    print("  1. 提交 update.json 到仓库根目录")
    print("  2. 把 APK 上传到 GitHub Release 附件")
    print("  3. 确认地址与上面的 apkUrl 完全一致")
    print()
    print("自检：")
    print("  - App 里 MANIFEST_URL 指向 update.json 的 raw 地址")
    print("  - sha256 必须和实际上传的 APK 一致，否则 App 会拒绝安装")
    return 0


if __name__ == "__main__":
    sys.exit(main())
