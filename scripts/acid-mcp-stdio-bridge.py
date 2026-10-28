#!/usr/bin/env python3
"""
Acid Browser MCP Stdio Bridge
Bridges standard input/output (stdio) MCP clients (e.g. Claude Desktop, Cursor)
to Acid Browser's native MCP Server running on Android (http://127.0.0.1:3090/mcp).
"""

import sys
import json
import urllib.request

MCP_ENDPOINT = "http://127.0.0.1:3090/mcp"

def main():
    while True:
        line = sys.stdin.readline()
        if not line:
            break
        line = line.strip()
        if not line:
            continue
        try:
            req_data = json.loads(line)
            req = urllib.request.Request(
                MCP_ENDPOINT,
                data=json.dumps(req_data).encode('utf-8'),
                headers={'Content-Type': 'application/json'}
            )
            with urllib.request.urlopen(req, timeout=30) as resp:
                resp_text = resp.read().decode('utf-8')
                sys.stdout.write(resp_text + "\n")
                sys.stdout.flush()
        except Exception as e:
            err_resp = {
                "jsonrpc": "2.0",
                "id": None,
                "error": {"code": -32603, "message": str(e)}
            }
            sys.stdout.write(json.dumps(err_resp) + "\n")
            sys.stdout.flush()

if __name__ == "__main__":
    main()
