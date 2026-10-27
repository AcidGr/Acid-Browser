#!/usr/bin/env python3
"""
Export cookies from Via Browser (mark.via) Chromium SQLite DB into a JSON
array consumable by Acid Browser's /api/cookies/import endpoint.

Chromium stores timestamps as microseconds since 1601-01-01 UTC.
"""
import json
import sqlite3
import sys
import os

VIA_DB = "/proc/1/root/data/user/0/mark.via/app_webview/Default/Cookies"
EPOCH_DELTA_SEC = 11644473600  # seconds between 1601-01-01 and 1970-01-01


def chromium_to_unix(us):
    if not us:
        return 0
    return int(us / 1000000) - EPOCH_DELTA_SEC


def main():
    db_path = sys.argv[1] if len(sys.argv) > 1 else VIA_DB
    out_path = sys.argv[2] if len(sys.argv) > 2 else "via_cookies.json"

    tmp = "/tmp/_via_cookies_snapshot.db"
    with open(db_path, "rb") as src, open(tmp, "wb") as dst:
        dst.write(src.read())

    con = sqlite3.connect(tmp)
    cur = con.cursor()
    cur.execute(
        "SELECT host_key, name, value, path, is_secure, is_httponly, samesite, "
        "has_expires, expires_utc, is_persistent FROM cookies "
        "WHERE value IS NOT NULL AND value != '' ORDER BY host_key"
    )
    cookies = []
    for (host, name, value, path, secure, httponly, samesite, has_exp, expires, persistent) in cur.fetchall():
        if has_exp and expires:
            exp = chromium_to_unix(expires)
        else:
            exp = 0  # session cookie
        cookies.append({
            "host": host,
            "name": name,
            "value": value,
            "path": path or "/",
            "secure": bool(secure),
            "httpOnly": bool(httponly),
            "sameSite": int(samesite),
            "expires": exp,
        })
    con.close()
    os.remove(tmp)

    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(cookies, f, ensure_ascii=False)

    domains = {}
    for c in cookies:
        domains[c["host"]] = domains.get(c["host"], 0) + 1
    top = sorted(domains.items(), key=lambda kv: -kv[1])[:15]
    print(f"[OK] Exported {len(cookies)} cookies from {len(domains)} domains -> {out_path}")
    print("[TOP DOMAINS]")
    for d, n in top:
        print(f"  {n:4d}  {d}")


if __name__ == "__main__":
    main()
